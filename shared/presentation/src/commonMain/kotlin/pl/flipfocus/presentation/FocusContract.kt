package pl.flipfocus.presentation

import kotlinx.datetime.LocalDate
import pl.flipfocus.domain.*

enum class FocusScreen { FOCUS, HISTORY, SETTINGS }
enum class ExportFormat { JSON, CSV }
enum class FeedbackKind { STARTED, RESUMED, COMPLETED, INTERRUPTED }
enum class FeedbackPrompt { FIRST_SESSION, SEVEN_DAYS }

/** Lock-screen/Live-Activity host action. RESUME/END never come from this contract: platforms only forward user taps. */
enum class InterruptionAction { START, UPDATE, END }

data class FocusUiState(
    val session: FocusSession? = null,
    val settings: FocusSettings = FocusSettings(),
    val history: List<FocusHistoryEntry> = emptyList(),
    val selectedDurationMinutes: Int = 25,
    val dailyProgress: DailyProgress = DailyProgress(LocalDate(1970, 1, 1), 0, 0, false, 0, 0),
    val isLoading: Boolean = true,
    val storageError: String? = null,
    val sensorError: String? = null,
    val isForeground: Boolean = true,
    val isFaceDown: Boolean = false,
    val graceRemainingMillis: Long = 0,
    val breakRemainingMillis: Long? = null,
    val screen: FocusScreen = FocusScreen.FOCUS,
    val feedbackPrompt: FeedbackPrompt? = null,
)

sealed interface FocusIntent {
    data class PhonePositionChanged(val isFaceDown: Boolean) : FocusIntent
    data class ForegroundChanged(val isForeground: Boolean) : FocusIntent
    data class MeasurementUnavailable(val message: String) : FocusIntent
    data class SelectDuration(val minutes: Int) : FocusIntent
    data class UpdateSettings(val settings: FocusSettings) : FocusIntent
    data class StartBreak(val durationMinutes: Int = 5) : FocusIntent
    data class OpenScreen(val screen: FocusScreen) : FocusIntent
    data class ExportHistory(val format: ExportFormat) : FocusIntent
    data class SubmitFeedback(val helpful: Boolean) : FocusIntent
    data object DismissFeedback : FocusIntent
    data object ReportMeasurementIssue : FocusIntent
    data object Abandon : FocusIntent
    data object NewSession : FocusIntent
    data object EndBreak : FocusIntent
    data object ClearHistory : FocusIntent
    data object Tick : FocusIntent
    data object RetryStorage : FocusIntent
}

sealed interface FocusEffect {
    data class Feedback(val kind: FeedbackKind) : FocusEffect
    data class ReminderPreferenceChanged(val enabled: Boolean, val hour: Int) : FocusEffect
    data class ExportReady(val content: String, val fileName: String) : FocusEffect
    data class Message(val text: String) : FocusEffect

    /**
     * Host-agnostic lock-screen interruption contract (Android ongoing notification / iOS Live Activity).
     * Carries no sensor data, history or friend information; platforms render it as-is.
     */
    data class LockScreenInterruption(
        val action: InterruptionAction,
        val sessionId: String,
        val startedAtEpochMillis: Long,
        val deadlineEpochMillis: Long,
        val remainingMillis: Long,
        val language: String,
    ) : FocusEffect
}
