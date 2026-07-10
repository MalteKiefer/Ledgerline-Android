package de.ledgerline.app.domain.usecase

class FakePairingGateway(
    private val pollSequence: List<String> = listOf("approved"),
    private val pollGone: Boolean = false,
) : PairingGateway {
    private var idx = 0
    override suspend fun claim(baseUrl: String, code: String, deviceName: String): PollResult = PollResult.Pending
    override suspend fun poll(baseUrl: String, code: String): PollResult {
        if (pollGone) return PollResult.Gone
        val step = pollSequence[idx.coerceAtMost(pollSequence.size - 1)]; idx++
        return when (step) {
            "approved" -> PollResult.Approved(token = "tok", spkiPin = "sha256/AAA", userName = "Malte")
            else -> PollResult.Pending
        }
    }
}
