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
import de.ledgerline.app.domain.model.TodoItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
    private open class FakeApi : NotImplementedApi() {
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

    @Test fun offline_note_save_queues_then_replays() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = FakeApi()
        val outbox = tmpOutbox()
        val conn = FakeConnectivity(online = true)
        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), outbox, conn, apiProvider = { fakeApi })

        repo.load() // establish base + versions online (all stores empty)
        conn.online = false
        val r = repo.save { m -> m.copy(notes = m.notes + Note(id = "n3", title = "Offline")) }
        assertTrue(r is Outcome.Ok)
        assertEquals(0, fakeApi.notesPuts) // nothing pushed while offline
        assertTrue(cache.value.value!!.manifest.notes.any { it.id == "n3" }) // optimistic local state
        assertTrue(outbox.hasPending())

        conn.online = true
        assertTrue(repo.replayPending())
        assertTrue("the queued note must be pushed on replay", fakeApi.notesPuts >= 1)
        assertTrue(!outbox.hasPending()) // drained
    }

    /**
     * Regression (data loss): a todo created while ONLINE but whose module-store PUT fails
     * with a transient/recoverable error (5xx, 429, or exhausted 409 retries → ErrorKind.HTTP)
     * must NOT be silently discarded. Before the fix, save() reverted the optimistic cache and
     * did NOT enqueue, so the edit vanished from the device and never reached the server. It
     * must instead land in the durable outbox (and stay in the cache) so it replays later.
     */
    @Test fun online_save_with_recoverable_error_is_queued_not_dropped() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = object : FakeApi() {
            override suspend fun moduleStore(module: String): Response<StoreResponse> =
                Response.success(StoreResponse(null, 0)) // empty base for the todos module
            override suspend fun putModuleStore(module: String, body: StorePutRequest): Response<StoreResponse> =
                Response.error(500, "".toResponseBody("text/plain".toMediaType())) // transient server error
        }
        val outbox = tmpOutbox()
        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), outbox, FakeConnectivity(online = true), apiProvider = { fakeApi })

        repo.load()
        repo.save { m -> m.copy(todos = m.todos + TodoItem(id = "t1", title = "Must not vanish")) }

        assertTrue("edit kept in the optimistic cache", cache.value.value!!.manifest.todos.any { it.id == "t1" })
        assertTrue("edit persisted to the durable outbox for replay", outbox.hasPending())
    }

    /**
     * Regression (silent clobber → data loss): the phone's cached (base, version) can drift so a
     * module PUT's version matches the server while the base is MISSING records the server already
     * has (e.g. a concurrent/replay write bumped the version without refreshing the cache). The old
     * code PUT `base + edit` at the matching version and the server accepted it with NO 409 — the
     * server's records were silently overwritten (todos/health/notes vanished). The fetch-first fix
     * re-reads the current server slice before every PUT, so the write is only ever additive.
     */
    @Test fun online_save_does_not_clobber_server_records_when_base_is_stale() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        var serverTodos = """{"v":3,"todos":[]}""" // empty at load → drives base todos = []
        var lastTodosPut: String? = null
        val fakeApi = object : FakeApi() {
            override suspend fun moduleStore(module: String): Response<StoreResponse> =
                if (module == "todos") Response.success(StoreResponse("SEALED:$serverTodos", 9))
                else Response.success(StoreResponse(null, 0))
            // Lenient server: always accepts (simulates the drifted version happening to match → no 409).
            override suspend fun putModuleStore(module: String, body: StorePutRequest): Response<StoreResponse> {
                if (module == "todos") lastTodosPut = body.ciphertext
                return Response.success(StoreResponse(body.ciphertext, body.version + 1))
            }
        }
        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(online = true), apiProvider = { fakeApi })

        repo.load() // base todos = [] (server empty at load time)
        // A concurrent client adds a todo the phone's cache never saw:
        val web1 = WorkspaceRecordCodec.encodeTodo(TodoItem(id = "web1", title = "From web"))
        serverTodos = """{"v":3,"todos":[$web1]}"""
        repo.save { m -> m.copy(todos = m.todos + TodoItem(id = "t1", title = "From phone")) }

        assertTrue("the server's record must survive the save", lastTodosPut!!.contains("web1"))
        assertTrue("the phone's edit must be written", lastTodosPut!!.contains("t1"))
    }

    @Test fun save_writes_notes_to_sharded_store() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = FakeApi()

        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(), apiProvider = { fakeApi })

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

        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(), apiProvider = { fakeApi })

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
        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(), apiProvider = { fakeApi })

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
