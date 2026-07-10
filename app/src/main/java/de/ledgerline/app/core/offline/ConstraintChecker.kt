package de.ledgerline.app.core.offline

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow seam over the prefetch constraints (Wi-Fi-only / charging-only) so the
 * [Prefetcher] depends on this interface, not on Android APIs — JVM tests supply a
 * fake. AOSP only.
 */
interface Constraints {
    /** True if the Wi-Fi-only constraint is satisfied (constraint off, or unmetered). */
    fun wifiConstraintMet(wifiOnly: Boolean): Boolean

    /** True if the charging-only constraint is satisfied (constraint off, or charging). */
    fun chargingConstraintMet(chargingOnly: Boolean): Boolean
}

@Singleton
class ConstraintChecker @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val connectivity: Connectivity,
) : Constraints {

    override fun wifiConstraintMet(wifiOnly: Boolean): Boolean =
        !wifiOnly || connectivity.isUnmetered()

    override fun chargingConstraintMet(chargingOnly: Boolean): Boolean =
        !chargingOnly || isCharging()

    /**
     * Reads the sticky [Intent.ACTION_BATTERY_CHANGED] broadcast for the charge state;
     * charging or full both count. Null-safe → assume not charging.
     */
    private fun isCharging(): Boolean {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }
}
