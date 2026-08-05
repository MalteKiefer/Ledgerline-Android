package de.ledgerline.app.core.calendar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.R
import javax.inject.Inject
import javax.inject.Singleton

/** One local reminder to fire on this device. */
data class LocalReminder(val whenMillis: Long, val title: String)

/**
 * Schedules on-device calendar-reminder notifications via [AlarmManager] (Google-free — no FCM).
 * The client computes reminder fire-times while the vault is unlocked and hands them here with the
 * event title; the [CalendarReminderReceiver] posts a generic-channel notification at each time.
 * Titles live only on-device (in the alarm PendingIntent), mirroring iOS local notifications.
 *
 * Limitation: AlarmManager alarms do not survive a reboot; they are re-scheduled on the next unlock
 * (when reminders are recomputed). A bounded window ([CAP]) of the soonest reminders is scheduled.
 */
@Singleton
class CalendarNotifier @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val alarm get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun ensureChannel() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.calendar_reminders), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = ctx.getString(R.string.calendar_notifications_sub) },
            )
        }
    }

    /** Whether exact alarms can be scheduled (before API 31 always; after, gated by the permission). */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()

    /** Replace all scheduled reminders with the soonest [CAP] future ones from [items]. */
    fun schedule(items: List<LocalReminder>) {
        ensureChannel()
        cancelAll()
        val now = System.currentTimeMillis()
        val future = items.filter { it.whenMillis > now }.sortedBy { it.whenMillis }.take(CAP)
        val exact = canScheduleExact()
        future.forEachIndexed { i, r ->
            val pi = pending(i, r.title)
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMillis, pi)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMillis, pi)
        }
    }

    fun cancelAll() {
        for (i in 0 until CAP) alarm.cancel(pending(i, null))
    }

    private fun pending(slot: Int, title: String?): PendingIntent {
        val intent = Intent(ctx, CalendarReminderReceiver::class.java).apply {
            action = "de.ledgerline.app.CALENDAR_REMINDER.$slot"
            putExtra("nid", BASE + slot)
            if (title != null) putExtra("title", title)
        }
        return PendingIntent.getBroadcast(
            ctx, BASE + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL = "ledgerline_calendar"
        const val BASE = 70000
        const val CAP = 50
    }
}
