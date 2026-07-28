package de.ledgerline.app.core.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * One-shot current-location helper shared by the Karte and the Tracker. Tries the freshest cached
 * fix (fused/GPS/network); if none, requests a single fresh fix, preferring the FUSED provider
 * (combines GPS + network + Wi-Fi → works indoors) and falling back to network, then GPS. Calls
 * [onResult] on the main thread with the first fix, or never if permission/providers are missing.
 */
object LocationOnce {
    fun current(context: Context, onResult: (Double, Double) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) { onResult(last.latitude, last.longitude); return }
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        requestFresh(context, lm, providers, 0, onResult)
    }

    private fun requestFresh(context: Context, lm: LocationManager, providers: List<String>, idx: Int, onResult: (Double, Double) -> Unit) {
        if (idx >= providers.size) return
        runCatching {
            LocationManagerCompat.getCurrentLocation(
                lm,
                providers[idx],
                null as android.os.CancellationSignal?,
                ContextCompat.getMainExecutor(context),
                androidx.core.util.Consumer { loc: Location? ->
                    if (loc != null) onResult(loc.latitude, loc.longitude)
                    else requestFresh(context, lm, providers, idx + 1, onResult)
                },
            )
        }.onFailure { requestFresh(context, lm, providers, idx + 1, onResult) }
    }
}
