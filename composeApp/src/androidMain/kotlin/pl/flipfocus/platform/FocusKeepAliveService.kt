package pl.flipfocus.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process and the orientation sensor alive while a focus session runs, so turning the screen
 * off (or leaving the app) does not interrupt it. Runs only while a session is FOCUSING/GRACE_PERIOD.
 */
class FocusKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aktywne skupienie", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            })
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FlipFocus")
            .setContentText("Skupienie trwa. Odłożony telefon jest mierzony w tle.")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flipfocus:session")
                ?.apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "focus_keep_alive"
        private const val NOTIFICATION_ID = 1802
        // Sessions are capped at 180 minutes; the lock never outlives that by much.
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 200L * 60_000L

        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, FocusKeepAliveService::class.java)) } catch (_: Exception) { }
        }

        fun stop(context: Context) { context.stopService(Intent(context, FocusKeepAliveService::class.java)) }
    }
}
