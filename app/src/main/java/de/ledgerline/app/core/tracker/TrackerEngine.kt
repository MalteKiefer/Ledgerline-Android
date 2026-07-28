package de.ledgerline.app.core.tracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.explore.TrackPoint
import de.ledgerline.app.core.explore.TrackPointFilter
import de.ledgerline.app.core.explore.TrackStats
import de.ledgerline.app.core.explore.TrackStatsComputer
import de.ledgerline.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Activity being recorded (drives speed-vs-pace display + notification icon). */
enum class ActivityKind { HIKE, RUN, CYCLE }

enum class RecordingState { IDLE, RECORDING, PAUSED }

/** Live recording snapshot the UI + notification render. */
data class TrackerUi(
    val state: RecordingState = RecordingState.IDLE,
    val activity: ActivityKind = ActivityKind.HIKE,
    val points: List<TrackPoint> = emptyList(),
    val stats: TrackStats? = null,
    val elapsedMs: Long = 0,
)

/**
 * The GPS track recorder. Owns the raw AOSP [LocationManager] fix stream (no Play Services),
 * applies the byte-exact [TrackPointFilter], accumulates points **in RAM only** (persisted
 * encrypted on save), and recomputes live [TrackStats]. A singleton so the foreground
 * [TrackingService] and the Tracker UI observe the same recording. ZK: coordinates never leave
 * the device except in the sealed manifest on save.
 */
@Singleton
class TrackerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _ui = MutableStateFlow(TrackerUi())
    val ui: StateFlow<TrackerUi> = _ui

    private val points = mutableListOf<TrackPoint>()
    private var segmentStartElapsed = 0L   // monotonic (elapsedRealtime) start of current active segment
    private var accumulatedMs = 0L         // elapsed accrued before the current segment (survives pauses)
    private var tickJob: Job? = null

    private val listener = LocationListener { onLocation(it) }

    val isRecording: Boolean get() = _ui.value.state != RecordingState.IDLE

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun setActivity(a: ActivityKind) { if (_ui.value.state == RecordingState.IDLE) _ui.value = _ui.value.copy(activity = a) }

    fun start(activity: ActivityKind) {
        if (isRecording) return
        if (!hasPermission()) return
        points.clear()
        accumulatedMs = 0
        segmentStartElapsed = SystemClock.elapsedRealtime()
        _ui.value = TrackerUi(state = RecordingState.RECORDING, activity = activity)
        requestUpdates()
        startTicker()
        startService()
    }

    fun pause() {
        if (_ui.value.state != RecordingState.RECORDING) return
        accumulatedMs += SystemClock.elapsedRealtime() - segmentStartElapsed
        _ui.value = _ui.value.copy(state = RecordingState.PAUSED, elapsedMs = accumulatedMs)
    }

    fun resume() {
        if (_ui.value.state != RecordingState.PAUSED) return
        segmentStartElapsed = SystemClock.elapsedRealtime()
        _ui.value = _ui.value.copy(state = RecordingState.RECORDING)
    }

    /** Stop recording; the recorded points remain in [ui] for the save/summary step. */
    fun stop() {
        if (!isRecording) return
        if (_ui.value.state == RecordingState.RECORDING) {
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStartElapsed
        }
        removeUpdates()
        tickJob?.cancel(); tickJob = null
        stopService()
        _ui.value = _ui.value.copy(state = RecordingState.IDLE, elapsedMs = accumulatedMs, stats = computeStats())
    }

    /** Discard the recording and reset to a clean idle state. */
    fun reset() {
        stop()
        points.clear()
        accumulatedMs = 0
        _ui.value = TrackerUi()
    }

    /** Snapshot the recorded points (for building an [de.ledgerline.app.domain.model.ExploreTrack]). */
    fun snapshotPoints(): List<TrackPoint> = points.toList()

    private fun onLocation(loc: Location) {
        if (_ui.value.state != RecordingState.RECORDING) return
        val prev = points.lastOrNull()
        val ele = if (loc.hasAltitude() && loc.hasVerticalAccuracy()) loc.altitude else if (loc.hasAltitude()) loc.altitude else null
        if (!TrackPointFilter.accept(prev, loc.latitude, loc.longitude, loc.accuracy.toDouble())) return
        points.add(TrackPoint(loc.latitude, loc.longitude, ele, System.currentTimeMillis()))
        _ui.value = _ui.value.copy(points = points.toList(), stats = computeStats())
    }

    private fun computeStats(): TrackStats? = if (points.size >= 2) TrackStatsComputer.computeStats(points) else null

    private fun liveElapsedMs(): Long =
        if (_ui.value.state == RecordingState.RECORDING) accumulatedMs + (SystemClock.elapsedRealtime() - segmentStartElapsed) else accumulatedMs

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                if (_ui.value.state == RecordingState.RECORDING) _ui.value = _ui.value.copy(elapsedMs = liveElapsedMs())
                delay(1000)
            }
        }
    }

    private fun requestUpdates() {
        // Request from every available provider so we get fixes indoors / before a GPS lock
        // (FUSED combines GPS + network + Wi-Fi). The point filter keeps only accurate fixes.
        val providers = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        try {
            providers.forEach { lm.requestLocationUpdates(it, 1000L, 0f, listener) }
        } catch (_: SecurityException) { /* permission revoked mid-flight */ }
    }

    private fun removeUpdates() {
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
    }

    private fun startService() {
        val i = Intent(context, TrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
    }

    private fun stopService() {
        context.stopService(Intent(context, TrackingService::class.java))
    }
}
