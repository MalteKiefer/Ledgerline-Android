package de.ledgerline.app.domain.model

sealed interface PairingState {
    data object Idle : PairingState
    data object Claiming : PairingState
    data object Polling : PairingState
    data class Approved(val session: Session) : PairingState
    data class Failed(val reason: PairingFailure) : PairingState
}
enum class PairingFailure { INVALID_LINK, NOT_HTTPS, CONSUMED_OR_EXPIRED, NETWORK, RATE_LIMITED, UNKNOWN }
