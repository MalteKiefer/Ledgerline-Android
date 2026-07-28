package de.ledgerline.app.core.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.MainActivity
import de.ledgerline.app.R
import de.ledgerline.app.core.explore.TrackStats
import de.ledgerline.app.core.units.MeasureFormatter
import de.ledgerline.app.core.units.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (type `location`) that keeps GPS fixes flowing while the Tracker records
 * in the background, behind a visible ongoing notification. It observes [TrackerEngine.ui] to
 * refresh the elapsed/distance line and stops itself when recording ends. The notification shows
 * the user's own live run stats (their data, on their device) — never any server/vault content.
 */
@AndroidEntryPoint
class TrackingService : Service() {

    @Inject lateinit var engine: TrackerEngine
    @Inject lateinit var settings: de.ledgerline.app.data.SettingsStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var unit: UnitSystem = UnitSystem.METRIC

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(engine.ui.value.elapsedMs, engine.ui.value.stats),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (collectJob == null) {
            serviceScope.launch { settings.unitSystem.collect { unit = it } }
            collectJob = serviceScope.launch {
                engine.ui.collect { ui ->
                    if (ui.state == RecordingState.IDLE) {
                        ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIF_ID, buildNotification(ui.elapsedMs, ui.stats))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(elapsedMs: Long, stats: TrackStats?): Notification {
        val dist = MeasureFormatter.distance(stats?.distanceM ?: 0.0, unit)
        val text = "${MeasureFormatter.duration(elapsedMs / 1000.0)} · $dist"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ledgerline_logo)
            .setContentTitle(getString(R.string.tracker_notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun ensureChannel() {
        val mgr = notificationManager()
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.tracker_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.tracker_channel_desc) },
            )
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "ledgerline_tracker"
        private const val NOTIF_ID = 1002
    }
}
