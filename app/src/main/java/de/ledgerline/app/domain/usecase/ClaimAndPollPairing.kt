package de.ledgerline.app.domain.usecase

import de.ledgerline.app.domain.model.PairingFailure
import de.ledgerline.app.domain.model.PairingState
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Result of one poll, decoupled from Retrofit so the use case is pure/testable. */
sealed interface PollResult {
    data object Pending : PollResult
    data class Approved(val token: String, val spkiPin: String, val userName: String?) : PollResult
    data object Gone : PollResult
    data object RateLimited : PollResult
    data object NetworkError : PollResult
}

interface PairingGateway {
    suspend fun claim(baseUrl: String, code: String, deviceName: String): PollResult
    suspend fun poll(baseUrl: String, code: String): PollResult
}

class ClaimAndPollPairing(
    private val gateway: PairingGateway,
    private val pollIntervalMs: Long = 2000,
    private val maxPolls: Int = 60,
) {
    fun run(baseUrl: String, code: String, deviceName: String): Flow<PairingState> = flow {
        emit(PairingState.Idle)
        if (!baseUrl.startsWith("https://")) { emit(PairingState.Failed(PairingFailure.NOT_HTTPS)); return@flow }

        emit(PairingState.Claiming)
        when (gateway.claim(baseUrl, code, deviceName)) {
            is PollResult.NetworkError -> { emit(PairingState.Failed(PairingFailure.NETWORK)); return@flow }
            is PollResult.RateLimited -> { emit(PairingState.Failed(PairingFailure.RATE_LIMITED)); return@flow }
            is PollResult.Gone -> { emit(PairingState.Failed(PairingFailure.CONSUMED_OR_EXPIRED)); return@flow }
            else -> {}
        }

        emit(PairingState.Polling)
        repeat(maxPolls) {
            when (val r = gateway.poll(baseUrl, code)) {
                is PollResult.Approved -> {
                    emit(PairingState.Approved(Session(baseUrl, r.token, r.spkiPin, r.userName))); return@flow
                }
                is PollResult.Gone -> { emit(PairingState.Failed(PairingFailure.CONSUMED_OR_EXPIRED)); return@flow }
                is PollResult.NetworkError -> { emit(PairingState.Failed(PairingFailure.NETWORK)); return@flow }
                is PollResult.RateLimited, is PollResult.Pending -> delay(pollIntervalMs)
            }
        }
        emit(PairingState.Failed(PairingFailure.CONSUMED_OR_EXPIRED))
    }
}
