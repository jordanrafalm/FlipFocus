package pl.flipfocus.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import pl.flipfocus.app.MainActivity
import pl.flipfocus.app.R
import pl.flipfocus.presentation.FocusEffect
import pl.flipfocus.presentation.InterruptionAction

/**
 * Ongoing lock-screen notification for [FocusEffect.LockScreenInterruption]. Carries only the
 * countdown and session id — no sensor readings, history or friend statistics. RESUME/ABANDON only
 * (re)open [MainActivity]; the domain layer decides the actual outcome once the sensor confirms it.
 */
object AndroidFocusInterruption {
    const val CHANNEL_ID = "focus_interruption_silent"
    private const val LEGACY_CHANNEL_ID = "focus_interruption"
    const val ACTION_RESUME = "pl.flipfocus.action.RESUME_FOCUS"
    const val ACTION_ABANDON = "pl.flipfocus.action.ABANDON_FOCUS"
    const val EXTRA_SESSION_ID = "pl.flipfocus.extra.SESSION_ID"
    private const val NOTIFICATION_ID = 1801
    private val totalMillisBySession = mutableMapOf<String, Long>()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Channel sound settings are immutable once created, so the old audible channel is replaced.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Przerwanie skupienia", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Odliczanie powrotu po podniesieniu telefonu podczas aktywnej sesji."
            // Content never includes sensitive data, but keep default private visibility on the lock screen.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
            // The app plays its own interruption sound; the system notification stays silent.
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun publish(context: Context, effect: FocusEffect.LockScreenInterruption, social: LiveClanInfo?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (effect.action == InterruptionAction.END) {
            totalMillisBySession.remove(effect.sessionId)
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!manager.areNotificationsEnabled()) return

        val pl = effect.language == "pl"
        if (effect.action == InterruptionAction.START) totalMillisBySession[effect.sessionId] = effect.remainingMillis.coerceAtLeast(1)
        val total = totalMillisBySession[effect.sessionId] ?: effect.remainingMillis.coerceAtLeast(1)
        val seconds = (effect.remainingMillis + 999) / 1000
        val body = if (pl) "Skupienie wstrzymane. Odłóż telefon w ciągu ${seconds}s, aby kontynuować."
        else "Focus paused. Put your phone face down within ${seconds}s to continue."

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (pl) "Odłóż telefon" else "Put your phone down")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(effect.deadlineEpochMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setProgress(1_000, ((effect.remainingMillis.toDouble() / total) * 1_000).toInt().coerceIn(0, 1_000), false)
            .setContentIntent(activityIntent(context, effect.sessionId, null))
            .addAction(action(context, effect.sessionId, ACTION_RESUME, if (pl) "ODŁOŻYŁEM TELEFON" else "PHONE DOWN"))
            .addAction(action(context, effect.sessionId, ACTION_ABANDON, if (pl) "ZAKOŃCZ" else "END"))
        // Only ever shown for a room the user already opted into and is currently live in.
        if (social != null) builder.setSubText("${social.roomName} · ${social.memberCount}")
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelAll(context: Context) {
        totalMillisBySession.clear()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun action(context: Context, sessionId: String, action: String, label: String): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification), label,
            activityIntent(context, sessionId, action),
        ).build()

    private fun activityIntent(context: Context, sessionId: String, action: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action ?: Intent.ACTION_MAIN
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val requestCode = (action ?: "flipfocus.open").hashCode()
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
