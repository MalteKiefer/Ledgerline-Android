package de.ledgerline.app.core.offline

import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.SettingsStore.Companion.DEFAULT_CACHE_MAX_MB
import de.ledgerline.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 * them without suspending on every request. Seeds each value with the SettingsStore
 * default (NOT a blocking DataStore read — that would risk an ANR when this @Singleton
 * is first injected on the main thread), then keeps them live via collectors on an
 * internal scope. The first collector emission (the real stored value) lands within
 * milliseconds; until then the defaults — identical to the store defaults — apply.
 */
@Singleton
class OfflinePrefs @Inject constructor(
    settings: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) : OfflineFlags {

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var filesPolicy: FileBlobPolicy = FileBlobPolicy.ON_DEMAND

    @Volatile
    private var contactsPolicy: ContactBlobPolicy = ContactBlobPolicy.ON_DEMAND

    @Volatile
    private var cacheMaxMb: Int = DEFAULT_CACHE_MAX_MB

    @Volatile
    private var wifiOnly: Boolean = true

    @Volatile
    private var chargingOnly: Boolean = true

    init {
        scope.launch { settings.offlineEnabled.collect { enabled = it } }
        scope.launch { settings.filesPolicy.collect { filesPolicy = it } }
        scope.launch { settings.contactsPolicy.collect { contactsPolicy = it } }
        scope.launch { settings.cacheMaxMb.collect { cacheMaxMb = it } }
        scope.launch { settings.prefetchWifiOnly.collect { wifiOnly = it } }
        scope.launch { settings.prefetchChargingOnly.collect { chargingOnly = it } }
    }

    override fun enabled(): Boolean = enabled

    override fun filesPolicy(): FileBlobPolicy = filesPolicy

    override fun contactsPolicy(): ContactBlobPolicy = contactsPolicy

    override fun maxBytes(): Long = cacheMaxMb.toLong() * 1024L * 1024L

    override fun wifiOnly(): Boolean = wifiOnly

    override fun chargingOnly(): Boolean = chargingOnly
}
