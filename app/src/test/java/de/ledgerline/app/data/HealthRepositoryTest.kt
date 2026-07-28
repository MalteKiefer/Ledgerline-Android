package de.ledgerline.app.data

import de.ledgerline.app.core.HealthCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.domain.model.HealthEntry
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Load + optimistic 409-rebase for the sealed `store/health` monolith. */
class HealthRepositoryTest {

    private val crypto = SealTagCrypto()

    private fun repo(api: NotImplementedApi): Pair<HealthRepository, HealthCache> {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = HealthCache()
        return HealthRepository(sh, vh, crypto, cache, tmpStoreCache(), FakeOfflineFlags(), apiProvider = { api }) to cache
    }

    @Test fun load_decodes_entries() = runBlocking {
        val api = object : NotImplementedApi() {
            override suspend fun moduleStore(module: String): Response<StoreResponse> {
                assertEquals("health", module)
                return Response.success(StoreResponse("""SEALED:{"v":3,"healthEntries":[{"id":"e1","ts":"2026-07-25T08:00:00Z","metric":"weight","v":80,"v2":null,"note":""}],"healthProfile":{},"healthFasts":[]}""", 5))
            }
        }
        val (r, _) = repo(api)
        val res = r.load()
        assertTrue(res is Outcome.Ok)
        assertEquals(1, (res as Outcome.Ok).value.manifest.entries.size)
        assertEquals(80.0, res.value.manifest.entries[0].v, 1e-9)
        assertEquals(5, res.value.version)
    }

    @Test fun save_rebases_on_409_and_keeps_both_entries() = runBlocking {
        var gets = 0
        var puts = 0
        val api = object : NotImplementedApi() {
            override suspend fun moduleStore(module: String): Response<StoreResponse> {
                gets++
                return if (gets == 1) {
                    Response.success(StoreResponse("""SEALED:{"v":3,"healthEntries":[{"id":"e1","ts":"2026-07-25T08:00:00Z","metric":"weight","v":80,"v2":null,"note":""}],"healthProfile":{},"healthFasts":[]}""", 5))
                } else {
                    // Conflict reload: the server has since added e3.
                    Response.success(StoreResponse("""SEALED:{"v":3,"healthEntries":[{"id":"e1","ts":"2026-07-25T08:00:00Z","metric":"weight","v":80,"v2":null,"note":""},{"id":"e3","ts":"2026-07-26T08:00:00Z","metric":"weight","v":79,"v2":null,"note":""}],"healthProfile":{},"healthFasts":[]}""", 6))
                }
            }
            override suspend fun putModuleStore(module: String, body: StorePutRequest): Response<StoreResponse> {
                puts++
                return if (puts == 1) Response.error(409, ResponseBody.create(null, ""))
                else Response.success(StoreResponse(body.ciphertext, body.version + 1))
            }
        }
        val (r, _) = repo(api)
        r.load()
        val res = r.save { m -> m.copy(entries = listOf(HealthEntry("e2", "2026-07-27T08:00:00Z", "weight", 78.0)) + m.entries) }
        assertTrue(res is Outcome.Ok)
        val ids = (res as Outcome.Ok).value.manifest.entries.map { it.id }.toSet()
        assertEquals(setOf("e1", "e2", "e3"), ids) // last-write-wins merge kept everyone
        assertEquals(2, puts)
    }
}
