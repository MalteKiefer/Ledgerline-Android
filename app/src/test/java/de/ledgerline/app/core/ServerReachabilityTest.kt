package de.ledgerline.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerReachabilityTest {

    @Test fun online_when_no_session_yet() = runBlocking {
        // Before pairing there is no base URL to probe — the app must NOT force offline.
        val r = ServerReachability(SessionHolder(), CoroutineScope(Job()))
        assertTrue(r.checkNow())
        assertTrue(r.online.value)
    }
}
