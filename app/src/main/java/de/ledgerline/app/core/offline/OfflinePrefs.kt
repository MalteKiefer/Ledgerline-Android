package de.ledgerline.app.core.offline

import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronous, always-current view of the offline settings. Kept as a narrow interface
 * so repositories depend on the seam (not the Hilt-wired [OfflinePrefs]); JVM unit
 * tests supply a trivial fake without needing a [SettingsStore] + [Context].
 */
interface OfflineFlags {
    /** Latest value of the master offline-cache switch. */
    fun enabled(): Boolean

    /** Latest file-content blob caching policy. */
    fun filesPolicy(): FileBlobPolicy

    /** Latest photo blob caching policy. */
    fun photosPolicy(): PhotoBlobPolicy

    /** Latest contact-avatar blob caching policy. */
    fun contactsPolicy(): ContactBlobPolicy

    /** Cache size limit in bytes (`0` = unlimited). */
    fun maxBytes(): Long

    /** Whether prefetch is restricted to unmetered (Wi-Fi) networks. */
    fun wifiOnly(): Boolean

    /** Whether prefetch is restricted to when the device is charging. */
    fun chargingOnly(): Boolean
}

/**
 * Synchronous, always-current view of the offline settings so repositories can read
 * them without suspending on every request. Seeds each value synchronously at
 * construction (`runBlocking { first() }`) then keeps them live via collectors on an
 * internal scope — mirrors how [de.ledgerline.app.core.ops.OperationManager] caches
 * its background-ops flag.
 */
@Singleton
class OfflinePrefs @Inject constructor(settings: SettingsStore) : OfflineFlags {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var enabled: Boolean = runBlocking { settings.offlineEnabled.first() }

    @Volatile
    private var filesPolicy: FileBlobPolicy = runBlocking { settings.filesPolicy.first() }

    @Volatile
    private var photosPolicy: PhotoBlobPolicy = runBlocking { settings.photosPolicy.first() }

    @Volatile
    private var contactsPolicy: ContactBlobPolicy = runBlocking { settings.contactsPolicy.first() }

    @Volatile
    private var cacheMaxMb: Int = runBlocking { settings.cacheMaxMb.first() }

    @Volatile
    private var wifiOnly: Boolean = runBlocking { settings.prefetchWifiOnly.first() }

    @Volatile
    private var chargingOnly: Boolean = runBlocking { settings.prefetchChargingOnly.first() }

    init {
        scope.launch { settings.offlineEnabled.collect { enabled = it } }
        scope.launch { settings.filesPolicy.collect { filesPolicy = it } }
        scope.launch { settings.photosPolicy.collect { photosPolicy = it } }
        scope.launch { settings.contactsPolicy.collect { contactsPolicy = it } }
        scope.launch { settings.cacheMaxMb.collect { cacheMaxMb = it } }
        scope.launch { settings.prefetchWifiOnly.collect { wifiOnly = it } }
        scope.launch { settings.prefetchChargingOnly.collect { chargingOnly = it } }
    }

    override fun enabled(): Boolean = enabled

    override fun filesPolicy(): FileBlobPolicy = filesPolicy

    override fun photosPolicy(): PhotoBlobPolicy = photosPolicy

    override fun contactsPolicy(): ContactBlobPolicy = contactsPolicy

    override fun maxBytes(): Long = cacheMaxMb.toLong() * 1024L * 1024L

    override fun wifiOnly(): Boolean = wifiOnly

    override fun chargingOnly(): Boolean = chargingOnly
}
