package pl.flipfocus.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import pl.flipfocus.app.BuildConfig
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.flipfocus.domain.DeviceOrientation
import pl.flipfocus.domain.MonitoringState
import pl.flipfocus.domain.OrientationStabilityFilter
import pl.flipfocus.domain.SensorAvailability
import pl.flipfocus.domain.TimeProvider
import kotlin.math.sqrt

class AndroidFocusPlatform(private val activity: ComponentActivity) : FocusPlatform, SensorEventListener {
    override val languageCode: String get() = java.util.Locale.getDefault().language
    override val timeProvider = object : TimeProvider {
        override fun epochMillis(): Long = System.currentTimeMillis()
        override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
    }
    private val mutableMonitoring = MutableStateFlow(MonitoringState())
    override val monitoring = mutableMonitoring.asStateFlow()
    private val foreground = MutableStateFlow(false)
    override val isForeground = foreground.asStateFlow()
    private val sensorManager = activity.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val filter = OrientationStabilityFilter()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastSampleMillis = 0L
    private var gravity: DoubleArray? = null
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionResult?.complete(it)
        permissionResult = null
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (timeProvider.monotonicMillis() - lastSampleMillis > 2_000L) {
                filter.invalidate()
                mutableMonitoring.value = MonitoringState(
                    orientation = DeviceOrientation.UNKNOWN,
                    availability = SensorAvailability.UNAVAILABLE,
                    message = "Brak bieżącego odczytu czujnika. Pomiar skupienia jest wstrzymany.",
                )
            }
            handler.postDelayed(this, 500L)
        }
    }

    fun foregroundChanged(isForeground: Boolean) {
        foreground.value = isForeground
        if (!isForeground) stopMonitoring()
    }

    override fun startMonitoring() {
        if (running || !foreground.value) return
        filter.invalidate()
        gravity = null
        if (sensor == null || sensorManager == null) {
            mutableMonitoring.value = MonitoringState(
                availability = SensorAvailability.UNAVAILABLE,
                message = "To urządzenie nie udostępnia czujnika położenia.",
            )
            return
        }
        try {
            running = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            mutableMonitoring.value = if (running) MonitoringState() else MonitoringState(
                availability = SensorAvailability.UNAVAILABLE,
                message = "Nie udało się uruchomić czujnika. Spróbuj ponownie otworzyć aplikację.",
            )
            lastSampleMillis = timeProvider.monotonicMillis()
            if (running) handler.postDelayed(watchdog, 2_000L)
        } catch (_: SecurityException) {
            mutableMonitoring.value = MonitoringState(
                availability = SensorAvailability.PERMISSION_DENIED,
                message = "Urządzenie zablokowało dostęp do pomiaru położenia.",
            )
        }
    }

    override fun stopMonitoring() {
        running = false
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(watchdog)
        filter.invalidate()
        mutableMonitoring.value = MonitoringState()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || !foreground.value || event.values.size < 3) return
        val raw = DoubleArray(3) { event.values[it].toDouble() }
        val vector = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val previous = gravity
            DoubleArray(3) { index -> previous?.let { it[index] * 0.8 + raw[index] * 0.2 } ?: raw[index] }
                .also { gravity = it }
        } else raw
        lastSampleMillis = timeProvider.monotonicMillis()
        val magnitude = sqrt(vector.sumOf { it * it })
        mutableMonitoring.value = MonitoringState(
            orientation = filter.update(vector[2], magnitude, lastSampleMillis),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun keepScreenAwake(enabled: Boolean) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override suspend fun setRemindersEnabled(enabled: Boolean, hour: Int): ReminderPermission {
        if (!enabled) {
            AndroidReminders.cancel(activity)
            return ReminderPermission.GRANTED
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val pending = permissionResult ?: CompletableDeferred<Boolean>().also {
                permissionResult = it
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!pending.await()) return ReminderPermission.DENIED
        }
        if (!AndroidReminders.isPermissionGranted(activity)) return ReminderPermission.DENIED
        return if (AndroidReminders.schedule(activity, hour)) ReminderPermission.GRANTED else ReminderPermission.UNAVAILABLE
    }

    override fun feedback(kind: FeedbackKind, sound: Boolean, haptics: Boolean) {
        if (haptics) runCatching {
            val (timings, amplitudes) = when (kind) {
                FeedbackKind.INTERRUPTED -> longArrayOf(0, 100, 80, 100, 80, 100) to intArrayOf(0, 255, 0, 255, 0, 255)
                FeedbackKind.COMPLETED -> longArrayOf(0, 25, 80, 25) to intArrayOf(0, 80, 0, 80)
                else -> longArrayOf(0, 25) to intArrayOf(0, 80)
            }
            activity.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
        if (sound) runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
            tone.startTone(if (kind == FeedbackKind.COMPLETED) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ tone.release() }, 200L)
        }
    }

    override fun shareText(text: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "FlipFocus — historia sesji")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(send, "Eksportuj historię"))
        }
    }

    override fun shareExport(content: String, fileName: String) {
        require(fileName.matches(Regex("[a-zA-Z0-9._-]+")))
        runCatching {
            val directory = java.io.File(activity.cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(directory, fileName).apply { writeText(content) }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.exports", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (fileName.endsWith(".json")) "application/json" else "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Eksportuj historię"))
        }.onFailure { shareText(content) }
    }

    override fun openSystemSettings() {
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
    }

    /**
     * Requires GOOGLE_WEB_CLIENT_ID (the Firebase "Web client" OAuth client ID from
     * Authentication > Sign-in method > Google in the Firebase console), sourced at build time
     * from gitignored local.properties/env — see docs/adr/0004 follow-ups. Cancellation returns
     * null; other failures also return null so linking simply stays unavailable rather than
     * crashing the flow.
     */
    override suspend fun googleSignIn(): GoogleSignInResult? {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return null
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val response = CredentialManager.create(activity).getCredential(activity, request)
            val googleCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
            GoogleSignInResult(googleCredential.idToken)
        } catch (_: GetCredentialException) { null } catch (_: Exception) { null }
    }

    override fun publishInterruption(effect: pl.flipfocus.presentation.FocusEffect.LockScreenInterruption, social: LiveClanInfo?) {
        runCatching { AndroidFocusInterruption.publish(activity, effect, social) }
    }

    override fun dispose() {
        stopMonitoring()
        keepScreenAwake(false)
        permissionResult?.complete(false)
        permissionResult = null
        permissionLauncher.unregister()
    }
}
