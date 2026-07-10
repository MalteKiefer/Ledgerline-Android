package de.ledgerline.app.core.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import de.ledgerline.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (type `dataSync`) that keeps the process alive while a tracked
 * operation runs in the background. It observes [OperationManager.active] and shows an
 * ongoing low-priority notification summarising the active ops. The notification
 * exposes **counts only** — never names, paths, or any decrypted content — to preserve
 * the zero-knowledge model. It stops itself once no operations remain.
 */
@AndroidEntryPoint
class BackgroundOpService : Service() {

    @Inject
    lateinit var operationManager: OperationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // Enter the foreground immediately with the current snapshot so the platform's
        // 5s startForeground deadline is met even before the first flow emission.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(operationManager.active.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (collectJob == null) {
            collectJob = serviceScope.launch {
                operationManager.active.collect { ops ->
                    if (ops.isEmpty()) {
                        ServiceCompat.stopForeground(this@BackgroundOpService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIF_ID, buildNotification(ops))
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

    private fun buildNotification(ops: List<OpProgress>): Notification {
        val text = if (ops.isEmpty()) {
            getString(R.string.ops_notification_idle)
        } else {
            ops.joinToString(separator = " · ") { op ->
                val label = getString(kindLabel(op.kind))
                if (op.total > 0) "$label ${op.current}/${op.total}" else label
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ledgerline_logo)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun kindLabel(kind: OpKind): Int = when (kind) {
        OpKind.FACE_SCAN -> R.string.ops_kind_face_scan
        OpKind.DUPLICATE_SCAN -> R.string.ops_kind_duplicate_scan
        OpKind.UPLOAD -> R.string.ops_kind_upload
        OpKind.BLOB_CLEANUP -> R.string.ops_kind_blob_cleanup
    }

    private fun ensureChannel() {
        val mgr = notificationManager()
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ops_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.ops_channel_desc) },
            )
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "ledgerline_ops"
        private const val NOTIF_ID = 1001
    }
}
