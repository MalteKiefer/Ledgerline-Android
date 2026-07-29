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
        override fun contentDecryptorFromKey(fileKey: ByteArray): de.ledgerline.app.core.crypto.Crypto.ContentDecryptor = throw NotImplementedError()
        override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = throw NotImplementedError()
    }

    /**
     * Fake sharded store API. Notes + files both live in the Store-v3 sharded stores
     * (`/notes/store`, `/files/store`): start empty, capture each PUT + its shards[] guard.
     * The notes monolith (`/store/notes`) is empty (base default) so the one-time migration
     * is a no-op. All modules empty so load succeeds and unchanged modules are never PUT.
     */
    private class FakeApi : NotImplementedApi() {
        var notesPuts = 0
        var lastNotesShards: List<String>? = null
        override suspend fun uploadNote(file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse> =
            Response.success(de.ledgerline.app.data.remote.dto.UploadResponse("uploaded-note-blob"))
        override suspend fun notesStorePut(body: StorePutRequest): Response<StoreResponse> {
            notesPuts++
            lastNotesShards = body.shards
            return Response.success(StoreResponse(body.ciphertext, body.version + 1))
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

    @Test fun save_writes_notes_to_sharded_store() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = FakeApi()

        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { fakeApi })

        // A note mutation seals + PUTs the sharded /notes/store (no longer a monolith module).
        val result = repo.save { m ->
            m.copy(notes = m.notes + Note(id = "n3", title = "New"))
        }

        assertTrue(result is Outcome.Ok)
        assertEquals("New", (result as Outcome.Ok).value.manifest.notes.single().title)
        assertEquals(1, fakeApi.notesPuts)
        // The note's shard blob ref is carried in the referential-integrity guard.
        assertTrue(fakeApi.lastNotesShards!!.contains("uploaded-note-blob"))
    }

    /**
     * The one-time monolith→sharded migration: the old `/store/notes` still holds a note, the
     * sharded `/notes/store` is empty. Loading moves the note into the sharded store and blanks
     * the monolith byte-exact (`{v:3,notes:[]}`) so a later "delete all" can't re-import it.
     */
    @Test fun load_migrates_notes_from_monolith_to_sharded() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()

        val fakeApi = object : NotImplementedApi() {
            var notesPuts = 0
            var monolithBlank: String? = null
            var monolithVersion = -1
            override suspend fun moduleStore(module: String): Response<StoreResponse> =
                if (module == "notes") Response.success(StoreResponse("""SEALED:{"v":3,"notes":[{"id":"n1","title":"Legacy"}]}""", 7))
                else Response.success(StoreResponse(null, 0))
            override suspend fun putModuleStore(module: String, body: StorePutRequest): Response<StoreResponse> {
                assertEquals("notes", module)
                monolithBlank = body.ciphertext; monolithVersion = body.version
                return Response.success(StoreResponse(body.ciphertext, body.version + 1))
            }
            override suspend fun uploadNote(file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse> =
                Response.success(de.ledgerline.app.data.remote.dto.UploadResponse("migrated-note-blob"))
            override suspend fun notesStorePut(body: StorePutRequest): Response<StoreResponse> {
                notesPuts++
                return Response.success(StoreResponse(body.ciphertext, body.version + 1))
            }
        }

        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { fakeApi })

        val result = repo.load()
        assertTrue(result is Outcome.Ok)
        assertEquals("Legacy", (result as Outcome.Ok).value.manifest.notes.single().title)
        assertEquals(1, fakeApi.notesPuts) // moved into the sharded store
        // Monolith blanked byte-exact at its own version.
        assertEquals("""SEALED:{"v":3,"notes":[]}""", fakeApi.monolithBlank)
        assertEquals(7, fakeApi.monolithVersion)
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
