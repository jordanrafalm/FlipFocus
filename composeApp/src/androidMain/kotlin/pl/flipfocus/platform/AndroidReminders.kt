package pl.flipfocus.platform

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import pl.flipfocus.app.MainActivity
import pl.flipfocus.app.R
import java.time.ZonedDateTime
import kotlinx.datetime.LocalDate

/** Opt-in local reminder on weekdays. No exact-alarm permission. */
object AndroidReminders {
    private const val CHANNEL = "flipfocus_reminders"
    private const val REQUEST_CODE = 1701
    private const val ENABLED = "enabled"
    private const val HOUR = "hour"

    private fun preferences(context: Context) = context.getSharedPreferences("flipfocus_reminders", Context.MODE_PRIVATE)
    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, FocusReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun isPermissionGranted(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return granted && manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun schedule(context: Context, hour: Int = preferences(context).getInt(HOUR, 11)): Boolean = runCatching {
        require(hour in 0..23)
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Przypomnienia o skupieniu", NotificationManager.IMPORTANCE_DEFAULT))
        if (!isPermissionGranted(context)) return false
        check(preferences(context).edit().putBoolean(ENABLED, true).putInt(HOUR, hour).commit())
        scheduleNext(context)
        true
    }.getOrDefault(false)

    private fun scheduleNext(context: Context) {
        val now = ZonedDateTime.now()
        val hour = preferences(context).getInt(HOUR, 11)
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        do {
            if (next.isAfter(now) && ReminderCalendar.isWorkday(LocalDate(next.year, next.monthValue, next.dayOfMonth))) break
            next = next.plusDays(1).withHour(hour)
        } while (true)
        requireNotNull(context.getSystemService(AlarmManager::class.java)).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        context.getSystemService(NotificationManager::class.java)?.cancel(REQUEST_CODE)
        preferences(context).edit().putBoolean(ENABLED, false).commit()
    }

    fun restore(context: Context) {
        if (preferences(context).getBoolean(ENABLED, false)) schedule(context, preferences(context).getInt(HOUR, 11))
    }

    fun show(context: Context) {
        if (!preferences(context).getBoolean(ENABLED, false) || !isPermissionGranted(context)) return
        val now = ZonedDateTime.now()
        if (!ReminderCalendar.isWorkday(LocalDate(now.year, now.monthValue, now.dayOfMonth))) {
            scheduleNext(context)
            return
        }
        scheduleNext(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_CODE, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Chwila dla Twojego skupienia")
            .setContentText("Odłóż telefon ekranem w dół. Zacznij spokojne 25 minut z FlipFocus.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(REQUEST_CODE, notification) }
    }
}

class FocusReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AndroidReminders.show(context)
}

class FocusReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            AndroidReminders.restore(context)
        }
    }
}
