package de.ledgerline.app.data.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of the camera-roll backup for the UI. */
data class BackupStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val lastError: String? = null,
)

/**
 * Opt-in camera-roll auto-backup. The device photos/videos added after the last run are uploaded to
 * the gallery. Runs ONLY while the app is unlocked (the bearer token lives in-memory in [SessionHolder]
 * — there is no background sync), triggered on unlock and by a manual "Back up now". The server dedups
 * by sha256, so an occasional re-upload is harmless. Progress is tracked by a DATE_ADDED cursor
 * (`galleryBackupSince`), not a giant id set.
 */
@Singleton
class GalleryBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: GalleryRepository,
    private val settings: SettingsStore,
    private val connectivity: Connectivity,
    private val sessionHolder: SessionHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    /** True if we can read the camera roll (READ_MEDIA_IMAGES). */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Fire-and-forget backup on unlock (no-op unless enabled). Called from MainActivity. */
    fun runIfEnabled() {
        scope.launch {
            if (!settings.galleryBackupEnabled.first()) return@launch
            runInternal(includeExisting = false)
        }
    }

    /** Manual "Back up now" — [includeExisting] uploads the whole roll, else only new media. */
    fun runNow(includeExisting: Boolean = false) {
        scope.launch { runInternal(includeExisting) }
    }

    private suspend fun runInternal(includeExisting: Boolean) = mutex.withLock {
        if (_status.value.running) return@withLock
        if (sessionHolder.get() == null) return@withLock          // locked → no token
        if (!hasPermission()) { _status.value = BackupStatus(lastError = "permission"); return@withLock }
        if (!connectivity.isOnline()) return@withLock
        if (settings.galleryBackupWifiOnly.first() && !connectivity.isUnmetered()) return@withLock

        val since = if (includeExisting) 0L else settings.galleryBackupSince.first()
        val includeVideos = settings.galleryBackupVideos.first()
        val items = queryNewMedia(since, includeVideos)
        if (items.isEmpty()) {
            if (since == 0L) settings.setGalleryBackupSince(nowSeconds())
            return@withLock
        }

        _status.value = BackupStatus(running = true, done = 0, total = items.size)
        var maxSeen = since
        var done = 0
        for (item in items) {
            if (sessionHolder.get() == null) break // locked mid-run
            val tmp = copyToCache(item) ?: continue
            val ok = repo.upload(tmp, item.name, item.mime) is Outcome.Ok
            runCatching { tmp.delete() }
            if (ok) {
                done++
                if (item.dateAdded > maxSeen) maxSeen = item.dateAdded
                settings.setGalleryBackupSince(maxSeen) // durable cursor after each success
                _status.value = _status.value.copy(done = done)
            }
        }
        _status.value = BackupStatus(running = false, done = done, total = items.size)
    }

    private data class Media(val id: Long, val name: String, val mime: String?, val dateAdded: Long, val isVideo: Boolean)

    private fun queryNewMedia(sinceSeconds: Long, includeVideos: Boolean): List<Media> {
        val out = ArrayList<Media>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
        val args = arrayOf(sinceSeconds.toString())
        val order = "${MediaStore.MediaColumns.DATE_ADDED} ASC"

        fun scan(collection: android.net.Uri, isVideo: Boolean) {
            runCatching {
                context.contentResolver.query(collection, projection, selection, args, order)?.use { c ->
                    val idc = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val namec = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimec = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val datec = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    while (c.moveToNext()) {
                        out += Media(c.getLong(idc), c.getString(namec) ?: "media_${c.getLong(idc)}", c.getString(mimec), c.getLong(datec), isVideo)
                    }
                }
            }
        }
        scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        if (includeVideos) scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return out.sortedBy { it.dateAdded }
    }

    private suspend fun copyToCache(item: Media): File? = withContext(Dispatchers.IO) {
        val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(collection, item.id)
        val dir = File(context.cacheDir, "backup").apply { mkdirs() }
        val dest = File(dir, "${item.id}_${item.name}")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000
}
