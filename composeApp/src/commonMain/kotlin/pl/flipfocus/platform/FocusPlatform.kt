package pl.flipfocus.platform

import kotlinx.coroutines.flow.StateFlow
import pl.flipfocus.domain.MonitoringState
import pl.flipfocus.domain.TimeProvider
import pl.flipfocus.presentation.FocusEffect

/** Native capabilities. Sensor observation is valid only while the app is foregrounded. */
interface FocusPlatform {
    val languageCode: String get() = "en"
    val timeProvider: TimeProvider
    val monitoring: StateFlow<MonitoringState>
    val isForeground: StateFlow<Boolean>

    fun startMonitoring()
    fun stopMonitoring()
    fun keepScreenAwake(enabled: Boolean) = Unit
    suspend fun setRemindersEnabled(enabled: Boolean, hour: Int = 11): ReminderPermission
    fun feedback(kind: FeedbackKind, sound: Boolean, haptics: Boolean)
    fun shareText(text: String)
    fun shareExport(content: String, fileName: String) = shareText(content)
    fun openSystemSettings()

    /**
     * Acquires a native Google credential to link to the current Firebase account (see
     * ADR-0004). Returns null if the user cancels or the platform hasn't wired native
     * sign-in yet; never throws for a plain cancellation.
     */
    suspend fun googleSignIn(): GoogleSignInResult? = null

    /**
     * Renders (or clears) the lock-screen/Live-Activity surface for [effect]. No-op by default so
     * hosts without a native surface (desktop, tests) compile unchanged. [social] carries only data
     * the user has already consented to share (opted-in, currently live room); it must stay null
     * otherwise, and neither parameter may include history or per-user statistics.
     */
    fun publishInterruption(effect: FocusEffect.LockScreenInterruption, social: LiveClanInfo? = null) = Unit

    fun dispose()
}

enum class ReminderPermission { GRANTED, DENIED, UNAVAILABLE }

enum class FeedbackKind { STARTED, RESUMED, COMPLETED, INTERRUPTED }

/** Minimal, consent-gated social context for the interruption surface. No avatars/images: initials only. */
data class LiveClanInfo(val roomName: String, val memberCount: Int, val liveNicknames: List<String>)

/** Google ID token (+ optional OAuth access token) ready to hand to `SocialRepository.linkGoogleIdToken`. */
data class GoogleSignInResult(val idToken: String, val accessToken: String? = null)
