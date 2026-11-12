@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pl.flipfocus.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.CoreMotion.CMMotionManager
import platform.Foundation.*
import platform.UIKit.*
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np
import pl.flipfocus.domain.DeviceOrientation
import pl.flipfocus.domain.MonitoringState
import pl.flipfocus.domain.OrientationStabilityFilter
import pl.flipfocus.domain.SensorAvailability
import pl.flipfocus.domain.TimeProvider
import kotlin.math.sqrt

class IosFocusPlatform : FocusPlatform {
    override val languageCode: String get() =
        (platform.Foundation.NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-') ?: "en"
    override val timeProvider = object : TimeProvider {
        override fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000).toLong()
        // Unlike uptime, continuous time includes device sleep, so grace still expires after a lock.
        override fun monotonicMillis(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()) / 1_000_000uL).toLong()
    }
    private val mutableMonitoring = MutableStateFlow(MonitoringState())
    override val monitoring = mutableMonitoring.asStateFlow()
    private val foreground = MutableStateFlow(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    override val isForeground = foreground.asStateFlow()
    private val motion = CMMotionManager()
    private val filter = OrientationStabilityFilter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchdog: Job? = null
    private var running = false
    private var lastSample = 0L
    private var gravity: DoubleArray? = null
    private val notifications = NSNotificationCenter.defaultCenter
    private val observers = listOf(
        notifications.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
            foreground.value = true
        },
        notifications.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
            foreground.value = false
            stopMonitoring()
        },
    )

    init {
        IosReminders.refreshIfEnabled()
    }

    override fun startMonitoring() {
        if (running || !foreground.value) return
        filter.invalidate()
        gravity = null
        mutableMonitoring.value = MonitoringState()
        lastSample = timeProvider.monotonicMillis()
        when {
            motion.deviceMotionAvailable -> {
                running = true
                motion.deviceMotionUpdateInterval = 0.05
                motion.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { sample, error ->
                    if (!running || !foreground.value) return@startDeviceMotionUpdatesToQueue
                    if (error != null || sample == null) {
                        measurementFailed()
                    } else {
                        sample.gravity.useContents { acceptSample(x, y, z) }
                    }
                }
            }
            motion.accelerometerAvailable -> {
                running = true
                motion.accelerometerUpdateInterval = 0.05
                motion.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { sample, error ->
                    if (!running || !foreground.value) return@startAccelerometerUpdatesToQueue
                    if (error != null || sample == null) {
                        measurementFailed()
                    } else sample.acceleration.useContents {
                        val raw = doubleArrayOf(x, y, z)
                        val previous = gravity
                        val filtered = DoubleArray(3) { index -> previous?.let { it[index] * 0.8 + raw[index] * 0.2 } ?: raw[index] }
                        gravity = filtered
                        acceptSample(filtered[0], filtered[1], filtered[2])
                    }
                }
            }
            else -> mutableMonitoring.value = MonitoringState(
                availability = SensorAvailability.UNAVAILABLE,
                message = "To urządzenie nie udostępnia czujnika położenia. Pomiar wymaga fizycznego iPhone’a.",
            )
        }
        if (running) watchdog = scope.launch {
            while (isActive) {
                delay(500)
                if (timeProvider.monotonicMillis() - lastSample > 2_000) measurementFailed()
            }
        }
    }

    private fun acceptSample(x: Double, y: Double, z: Double) {
        lastSample = timeProvider.monotonicMillis()
        // Core Motion reports gravitational acceleration in the opposite direction to Android.
        mutableMonitoring.value = MonitoringState(
            orientation = filter.update(-z, sqrt(x * x + y * y + z * z), lastSample),
        )
    }

    private fun measurementFailed() {
        filter.invalidate()
        mutableMonitoring.value = MonitoringState(
            orientation = DeviceOrientation.UNKNOWN,
            availability = SensorAvailability.UNAVAILABLE,
            message = "Pomiar położenia jest niedostępny. Wróć do aplikacji, aby wznowić skupienie.",
        )
    }

    override fun stopMonitoring() {
        running = false
        motion.stopDeviceMotionUpdates()
        motion.stopAccelerometerUpdates()
        watchdog?.cancel()
        watchdog = null
        filter.invalidate()
        mutableMonitoring.value = MonitoringState()
    }

    override fun keepScreenAwake(enabled: Boolean) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
    }

    override suspend fun setRemindersEnabled(enabled: Boolean, hour: Int): ReminderPermission = IosReminders.setEnabled(enabled, hour)

    override fun feedback(kind: FeedbackKind, sound: Boolean, haptics: Boolean) {
        if (haptics) {
            val style = if (kind == FeedbackKind.INTERRUPTED) UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy
            else UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
            val count = when (kind) {
                FeedbackKind.INTERRUPTED -> 3
                FeedbackKind.COMPLETED -> 2
                else -> 1
            }
            scope.launch {
                repeat(count) { index ->
                    if (index > 0) delay(110L)
                    UIImpactFeedbackGenerator(style).impactOccurred()
                }
            }
        }
        if (sound) AudioServicesPlaySystemSound(if (kind == FeedbackKind.COMPLETED) 1057u else 1104u)
    }

    override fun shareText(text: String) {
        presentShare(listOf(text))
    }

    override fun shareExport(content: String, fileName: String) {
        require(fileName.matches(Regex("[a-zA-Z0-9._-]+")))
        val path = NSTemporaryDirectory() + fileName
        if (NSString.create(string = content).writeToFile(path, true, NSUTF8StringEncoding, null)) {
            presentShare(listOf(NSURL.fileURLWithPath(path)))
        } else shareText(content)
    }

    private fun presentShare(items: List<Any>) {
        val presenter = topViewController() ?: return
        val share = UIActivityViewController(items, null)
        // iPad requires an anchor for the system share sheet.
        share.popoverPresentationController?.let {
            it.sourceView = presenter.view
            it.sourceRect = presenter.view.bounds
        }
        presenter.presentViewController(share, true, null)
    }

    override fun openSystemSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
        }
    }

    /**
     * Delegates to the Swift [GoogleSignInBridge] (see `GoogleSignInController.swift`), which
     * returns null on cancellation, missing native config, or any other failure — never throws.
     */
    override suspend fun googleSignIn(): GoogleSignInResult? {
        val bridge = GoogleSignInBridgeHolder.bridge ?: return null
        return suspendCancellableCoroutine { continuation ->
            bridge.signIn { idToken, accessToken ->
                continuation.resume(idToken?.let { GoogleSignInResult(it, accessToken) }) { _, _, _ -> }
            }
        }
    }

    override fun publishInterruption(effect: pl.flipfocus.presentation.FocusEffect.LockScreenInterruption, social: LiveClanInfo?) {
        val bridge = FocusLiveActivityBridgeHolder.bridge ?: return
        when (effect.action) {
            pl.flipfocus.presentation.InterruptionAction.START -> bridge.start(
                sessionId = effect.sessionId, startedAtEpochMillis = effect.startedAtEpochMillis,
                deadlineEpochMillis = effect.deadlineEpochMillis, remainingMillis = effect.remainingMillis,
                language = effect.language, roomName = social?.roomName, memberCount = (social?.memberCount ?: 0).toLong(),
            )
            pl.flipfocus.presentation.InterruptionAction.UPDATE -> bridge.update(
                effect.sessionId, effect.deadlineEpochMillis, effect.remainingMillis)
            pl.flipfocus.presentation.InterruptionAction.END -> bridge.end(effect.sessionId)
        }
    }

    override fun dispose() {
        stopMonitoring()
        keepScreenAwake(false)
        observers.forEach(notifications::removeObserver)
        scope.cancel()
    }

    private fun topViewController(): UIViewController? {
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (controller?.presentedViewController != null) controller = controller.presentedViewController
        return controller
    }
}
