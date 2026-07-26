package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class WorkspaceSaveTest {

    // Fake crypto: sealManifest tags the json, openManifest strips the tag.
    private val fakeCrypto = object : Crypto {
        override fun sealManifest(json: String, vk: ByteArray) = "SEALED:$json"
        override fun openManifest(ciphertext: String, vk: ByteArray) = ciphertext.removePrefix("SEALED:")
        override fun sealValue(data: ByteArray, key: ByteArray) = "V:" + String(data, Charsets.ISO_8859_1)
        override fun openValue(cn: String, key: ByteArray) = cn.removePrefix("V:").toByteArray(Charsets.ISO_8859_1)
        override fun genericHash(input: ByteArray, outLen: Int) = ByteArray(outLen)
        override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) = ByteArray(32)
        override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray) = ByteArray(0)
        override fun genericHash32(input: ByteArray) = ByteArray(32)
        override fun b64decode(s: String) = s.toByteArray()
        override fun b64encode(b: ByteArray) = String(b)
        override fun fromHex(s: String) = s.toByteArray()
        override val contentChunkSize = 1
        override fun u32le(n: Int) = ByteArray(4)
        override fun readU32le(b: ByteArray, o: Int) = 0
        override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor = object : Crypto.ContentEncryptor {
            override val header = ByteArray(24)
            override fun encryptChunk(chunk: ByteArray, isLast: Boolean) = chunk
            override fun sealKey() = """{"c":"x","n":"y"}"""
        }
        override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = throw NotImplementedError()
    }

    /**
     * Fake per-module store API. The `notes` module: initial GET → v5 with note A;
     * first PUT → 409; the conflict reload → v6 with A + a server-added note; second
     * PUT → 200. All other modules are empty (null ciphertext, v0) so load succeeds
     * and they are never PUT (unchanged).
     */
    private class FakeApi : NotImplementedApi() {
        var puts = 0
        var notesGets = 0

        override suspend fun moduleStore(module: String): Response<StoreResponse> {
            if (module != "notes") return Response.success(StoreResponse(null, 0))
            notesGets++
            return if (notesGets == 1) {
                Response.success(StoreResponse("""SEALED:{"v":3,"notes":[{"id":"n1","title":"A"}]}""", 5))
            } else {
                // Conflict reload: the server has since added note n2.
                Response.success(StoreResponse("""SEALED:{"v":3,"notes":[{"id":"n1","title":"A"},{"id":"n2","title":"Server"}]}""", 6))
            }
        }

        override suspend fun putModuleStore(module: String, body: StorePutRequest): Response<StoreResponse> {
            assertEquals("notes", module)
            puts++
            return if (puts == 1) Response.error(409, ResponseBody.create(null, ""))
            else Response.success(StoreResponse(body.ciphertext, body.version + 1))
        }

        // Sharded files store: start empty; capture the PUT + its shards[] guard.
        var filesPuts = 0
        var lastFilesShards: List<String>? = null
        override suspend fun filesStore(): Response<StoreResponse> = Response.success(StoreResponse(null, 0))
        override suspend fun uploadFile(file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse> =
            Response.success(de.ledgerline.app.data.remote.dto.UploadResponse("uploaded-blob"))
        override suspend fun filesStorePut(body: StorePutRequest): Response<StoreResponse> {
            filesPuts++
            lastFilesShards = body.shards
            return Response.success(StoreResponse(body.ciphertext, body.version + 1))
        }
    }

    @Test fun save_merges_notes_module_on_409_and_retries() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = FakeApi()

        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { fakeApi })

        val result = repo.save { m ->
            m.copy(notes = m.notes + Note(id = "n3", title = "New"))
        }

        assertTrue(result is Outcome.Ok)
        // After the 409 the repo reloads (server has A + Server) and re-applies the
        // mutate → all three notes present (last-write-wins merge).
        val titles = (result as Outcome.Ok).value.manifest.notes.map { it.title }.toSet()
        assertEquals(setOf("A", "Server", "New"), titles)
        assertEquals(2, fakeApi.puts) // 409 then success
    }

    @Test fun save_writes_folder_mutation_to_sharded_files_store() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = FakeApi()
        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { fakeApi })

        // A folder mutation now seals + PUTs the sharded /files/store (no longer rejected).
        val result = repo.save { m ->
            m.copy(fileFolders = m.fileFolders + de.ledgerline.app.domain.model.NamedFolder("d1", "Docs", null))
        }

        assertTrue(result is Outcome.Ok)
        assertEquals(1, fakeApi.filesPuts)
        // The folders collection blob ref is carried in the referential-integrity guard.
        assertTrue(fakeApi.lastFilesShards!!.contains("uploaded-blob"))
    }
}
