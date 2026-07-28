package de.ledgerline.app.data

import de.ledgerline.app.core.AuthEventBus
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.dto.MeResponse
import de.ledgerline.app.data.remote.dto.MeUsage
import de.ledgerline.app.data.remote.dto.MeUser
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/** `AccountRepository.snapshot()`: the combined (files+gallery) storage figures from `/me`. */
class AccountRepositoryTest {

    private fun repo(body: MeResponse): AccountRepository {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val api = object : NotImplementedApi() {
            override suspend fun me(): Response<MeResponse> = Response.success(body)
        }
        return AccountRepository(sh, AuthEventBus(), apiProvider = { api })
    }

    @Test fun snapshot_sums_files_and_gallery_and_keeps_quota() = runBlocking {
        val snap = repo(
            MeResponse(user = MeUser(name = "Ada"), usage = MeUsage(files = 300, gallery = 700, quota = 5000)),
        ).snapshot()
        assertEquals("Ada", snap!!.name)
        assertEquals(1000L, snap.usedBytes)
        assertEquals(5000L, snap.quotaBytes)
    }

    @Test fun snapshot_null_quota_means_unlimited() = runBlocking {
        val snap = repo(
            MeResponse(user = MeUser(name = "Ada"), usage = MeUsage(files = 10, gallery = 20, quota = null)),
        ).snapshot()
        assertEquals(30L, snap!!.usedBytes)
        assertNull(snap.quotaBytes) // unlimited → the ring renders "—"
    }

    @Test fun snapshot_tolerates_missing_usage() = runBlocking {
        val snap = repo(MeResponse(user = MeUser(name = "Ada"), usage = null)).snapshot()
        assertEquals(0L, snap!!.usedBytes)
        assertNull(snap.quotaBytes)
    }
}
