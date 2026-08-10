package de.ledgerline.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.ledgerline.app.MainActivity
import de.ledgerline.app.R
import de.ledgerline.app.data.remote.dto.PushPayload

/**
 * Turns a server [PushPayload] into an Android system notification. Channels are split by the
 * notification's `level` so the user can tune importance per severity in system settings.
 * Lockscreen visibility is caller-controlled: private (title only) unless the user opts in to
 * showing content. Receiving needs no bearer token — this runs from the UnifiedPush service.
 */
object PushNotifier {

    /** One channel per level. Channel id is stable so system-settings tuning sticks. */
    private data class Level(val id: String, val importance: Int, val nameRes: Int)

    private val LEVELS = mapOf(
        "error" to Level("push_error", NotificationManager.IMPORTANCE_HIGH, R.string.push_channel_error),
        "warning" to Level("push_warning", NotificationManager.IMPORTANCE_HIGH, R.string.push_channel_warning),
        "success" to Level("push_success", NotificationManager.IMPORTANCE_DEFAULT, R.string.push_channel_success),
        "info" to Level("push_info", NotificationManager.IMPORTANCE_DEFAULT, R.string.push_channel_info),
    )
    private val DEFAULT_LEVEL = LEVELS.getValue("info")

    /** Idempotently (re)create the channels. Safe to call repeatedly. */
    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        LEVELS.values.distinctBy { it.id }.forEach { lvl ->
            val ch = NotificationChannel(lvl.id, context.getString(lvl.nameRes), lvl.importance)
            mgr.createNotificationChannel(ch)
        }
    }

    /** True if we're allowed to post notifications (POST_NOTIFICATIONS granted). */
    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Post [payload] as a system notification. [showContent] promotes lockscreen visibility to
     * public (body visible); otherwise the body is hidden on the lock screen (title only).
     */
    fun show(context: Context, payload: PushPayload, showContent: Boolean) {
        if (!canPost(context)) return
        ensureChannels(context)
        val level = LEVELS[payload.level] ?: DEFAULT_LEVEL

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NOTIFICATIONS)
        }
        val pending = PendingIntent.getActivity(
            context,
            payload.id.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = payload.title.ifBlank { context.getString(R.string.push_default_title) }
        val builder = NotificationCompat.Builder(context, level.id)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setVisibility(if (showContent) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
        payload.body?.takeIf { it.isNotBlank() }?.let {
            builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it))
        }

        // Distinct id per notification-centre row so multiple pushes stack instead of replacing.
        val notifyId = if (payload.id != 0L) payload.id.toInt() else (payload.title.hashCode())
        NotificationManagerCompat.from(context).notify(notifyId, builder.build())
    }
}
