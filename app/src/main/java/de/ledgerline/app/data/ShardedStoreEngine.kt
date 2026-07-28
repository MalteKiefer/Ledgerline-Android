package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import de.ledgerline.app.data.remote.dto.UploadResponse
import de.ledgerline.app.domain.model.GalleryShard
import de.ledgerline.app.domain.model.ShardRoot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObject as KJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

/**
 * Reusable Store-v3 sharded module store (the engine behind the notes + passwords migration). It
 * mirrors the files slice in [WorkspaceRepository]: load = root → shard/collection blobs assembled
 * cache-first; save = dirty-save via [SealedShardWriter] → seal root → PUT with the `shards[]`
 * referential-integrity guard. Records + collection items flow as raw [JsonObject]s so the calling
 * repository owns the typed codec (raw-overlay = no field loss). The 409-rebase loop lives in the
 * repository (it holds the typed mutation); this engine does one load and one seal+PUT attempt.
 */
class ShardedStoreEngine(
    private val crypto: Crypto,
    private val blobCache: BlobDiskCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val rootCacheKey: String,
    private val storeGet: suspend () -> Response<StoreResponse>,
    private val storePut: suspend (StorePutRequest) -> Response<StoreResponse>,
    private val rawBlob: suspend (String) -> Response<ResponseBody>,
    private val uploadBlobApi: suspend (MultipartBody.Part) -> Response<UploadResponse>,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonEnc = Json { encodeDefaults = true; explicitNulls = false }

    @Volatile var version: Int = 0
        private set
    @Volatile private var priorRoot = SealedShardWriter.RootState()

    class AuthException : RuntimeException()

    data class Loaded(val records: List<JsonObject>, val folders: List<JsonObject>, val present: Boolean)

    /** Load the sharded store: root → shard blobs (parallel) + optional folders collection. */
    suspend fun load(vk: ByteArray): Loaded {
        val res = try { storeGet() } catch (_: Exception) { return cachedOr(vk) }
        if (res.code() == HttpURLConnection.HTTP_UNAUTHORIZED) throw AuthException()
        if (!res.isSuccessful) return cachedOr(vk)
        val body = res.body()!!
        version = body.version
        val ct = body.ciphertext ?: run {
            priorRoot = SealedShardWriter.RootState()
            return Loaded(emptyList(), emptyList(), present = false)
        }
        if (offlineFlags.enabled()) storeCache.put(rootCacheKey, StoreEnvelope(ct, body.version))
        val plain = crypto.openManifest(ct, vk) ?: error("decrypt failed")
        val root = json.decodeFromString(ShardRoot.serializer(), plain)
        return assemble(root, vk, allowNetwork = true)
    }

    private suspend fun cachedOr(vk: ByteArray): Loaded {
        if (offlineFlags.enabled()) {
            storeCache.get(rootCacheKey)?.let { env ->
                env.ciphertext?.let { ct ->
                    crypto.openManifest(ct, vk)?.let { plain ->
                        runCatching {
                            val root = json.decodeFromString(ShardRoot.serializer(), plain)
                            version = env.version
                            return assemble(root, vk, allowNetwork = false)
                        }
                    }
                }
            }
        }
        return Loaded(emptyList(), emptyList(), present = false)
    }

    private suspend fun assemble(root: ShardRoot, vk: ByteArray, allowNetwork: Boolean): Loaded {
        priorRoot = SealedShardWriter.RootState(
            shardBits = root.shardBits,
            shards = root.shards,
            folders = root.foldersRef?.let { SealedShardWriter.CollDesc(it, root.foldersKey ?: "", root.foldersHash ?: "") },
        )
        val records = coroutineScope {
            root.shards.map { s -> async { fetchShard(s.ref, s.key ?: "", vk, allowNetwork) } }.awaitAll()
        }.flatMap { it ?: emptyList() }
        val folders = if (root.foldersRef != null) {
            fetchShard(root.foldersRef!!, root.foldersKey ?: "", vk, allowNetwork).orEmpty()
        } else emptyList()
        return Loaded(records, folders, present = true)
    }

    private suspend fun fetchShard(ref: String, key: String, vk: ByteArray, allowNetwork: Boolean): List<JsonObject>? {
        blobCache.get(ref)?.let { cipher ->
            val bytes = BlobDownloader.decrypt(cipher, key, vk, crypto)
            return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
        }
        if (!allowNetwork) return null
        var attempt = 0
        while (true) {
            val r = rawBlob(ref)
            if (r.isSuccessful) {
                val cipher = r.body()!!.bytes()
                if (offlineFlags.enabled()) blobCache.put(ref, cipher)
                val bytes = BlobDownloader.decrypt(cipher, key, vk, crypto)
                return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
            }
            if (r.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                if (attempt < 3) { delay(500L * (1 shl attempt)); attempt++; continue }
                error("sharded blob $ref durably missing (404)") // fail-safe: never silently drop records
            }
            error("sharded blob $ref: http ${r.code()}")
        }
    }

    sealed interface PutOutcome {
        data class Ok(val newVersion: Int) : PutOutcome
        data object Conflict : PutOutcome
        data object Error : PutOutcome
    }

    /** One seal+PUT attempt at [atVersion]. Records are (id, encoded JSON); folders are encoded. */
    suspend fun sealAndPut(
        vk: ByteArray,
        records: List<Pair<String, JsonObject>>,
        folders: List<JsonObject>,
        atVersion: Int,
    ): PutOutcome {
        val writer = SealedShardWriter { bytes, name -> uploadBlob(vk, bytes, name) }
        val result = writer.build(records, folders, priorRoot) ?: return PutOutcome.Error
        val rootCipher = crypto.sealManifest(jsonEnc.encodeToString(JsonObject.serializer(), result.rootJson), vk)
        val putRes = try {
            storePut(StorePutRequest(rootCipher, atVersion, result.shardRefs))
        } catch (_: Exception) { return PutOutcome.Error }
        return when {
            putRes.isSuccessful -> {
                val nv = putRes.body()?.version ?: (atVersion + 1)
                version = nv
                priorRoot = result.state
                if (offlineFlags.enabled()) storeCache.put(rootCacheKey, StoreEnvelope(rootCipher, nv))
                PutOutcome.Ok(nv)
            }
            putRes.code() == HttpURLConnection.HTTP_CONFLICT -> PutOutcome.Conflict
            else -> PutOutcome.Error
        }
    }

    private suspend fun uploadBlob(vk: ByteArray, bytes: ByteArray, name: String): UploadedBlob? {
        val enc = crypto.newContentEncryptor(vk)
        val reqBody = EncryptedUpload.body(enc, crypto.contentChunkSize, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        return try {
            val part = MultipartBody.Part.createFormData("file", name, reqBody)
            val res = uploadBlobApi(part)
            if (!res.isSuccessful) null else UploadedBlob(res.body()!!.id, enc.sealKey(), bytes.size.toLong())
        } catch (_: Exception) { null }
    }
}
