package de.ledgerline.app.core.calendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import de.ledgerline.app.MainActivity
import de.ledgerline.app.R

/**
 * Fires when a scheduled calendar reminder is due and posts a notification. Registered in the
 * manifest (not exported). The event title (if present) shows only on this device; tapping opens
 * the app.
 */
class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CalendarNotifier.CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CalendarNotifier.CHANNEL, context.getString(R.string.calendar_reminders), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val nid = intent.getIntExtra("nid", CalendarNotifier.BASE)
        val title = intent.getStringExtra("title") ?: context.getString(R.string.calendar_reminders)

        val open = PendingIntent.getActivity(
            context, nid,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CalendarNotifier.CHANNEL)
            .setSmallIcon(R.drawable.ic_ledgerline_logo)
            .setContentTitle(context.getString(R.string.calendar_reminders))
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) nm.notify(nid, notif)
    }
}
