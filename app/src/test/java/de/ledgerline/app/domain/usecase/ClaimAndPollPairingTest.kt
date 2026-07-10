package de.ledgerline.app.domain.usecase

import de.ledgerline.app.domain.model.PairingFailure
import de.ledgerline.app.domain.model.PairingState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimAndPollPairingTest {
    @Test fun rejects_non_https_url() = runTest {
        val useCase = ClaimAndPollPairing(FakePairingGateway())
        val states = useCase.run(baseUrl = "http://insecure.example", code = "c", deviceName = "d").toList()
        assertTrue(states.last() is PairingState.Failed)
        assertEquals(PairingFailure.NOT_HTTPS, (states.last() as PairingState.Failed).reason)
    }

    @Test fun claims_then_polls_until_approved() = runTest {
        val gw = FakePairingGateway(pollSequence = listOf("pending", "pending", "approved"))
        val useCase = ClaimAndPollPairing(gw, pollIntervalMs = 0)
        val states = useCase.run("https://host.example", "code1", "Pixel").toList()
        assertTrue(states.any { it is PairingState.Claiming })
        assertTrue(states.any { it is PairingState.Polling })
        assertTrue(states.last() is PairingState.Approved)
        assertEquals("tok", (states.last() as PairingState.Approved).session.token)
    }

    @Test fun maps_410_to_consumed_or_expired() = runTest {
        val gw = FakePairingGateway(pollGone = true)
        val useCase = ClaimAndPollPairing(gw, pollIntervalMs = 0)
        val states = useCase.run("https://host.example", "code1", "Pixel").toList()
        assertEquals(PairingFailure.CONSUMED_OR_EXPIRED, (states.last() as PairingState.Failed).reason)
    }
}
