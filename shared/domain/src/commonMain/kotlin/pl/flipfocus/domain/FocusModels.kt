package pl.flipfocus.domain

import kotlinx.coroutines.flow.StateFlow

/** Only validated durations enter the session state machine. */
data class FocusDuration(val minutes: Int) {
    init { require(minutes in 1..180) }
    val millis: Long get() = minutes * 60_000L

    companion object {
        fun ofMinutes(minutes: Int): FocusDuration {
            require(minutes in 1..180) { "Focus duration must be between 1 and 180 minutes" }
            return FocusDuration(minutes)
        }

        val presets: List<FocusDuration> = listOf(15, 25, 45).map(::ofMinutes)
    }
}

enum class FocusState { READY, FOCUSING, GRACE_PERIOD, COMPLETED, ABANDONED }
enum class FocusEndReason { TARGET_REACHED, USER_ABANDONED, GRACE_EXPIRED, MEASUREMENT_LOST }
enum class AppLanguage { SYSTEM, PL, EN }

data class FocusSettings(
    val graceSeconds: Int = 10,
    val minimumStreakMinutes: Int = 1,
    val soundEnabled: Boolean = true,
    /** While a session runs, the device's Do Not Disturb is switched on (where the OS allows it). */
    val silentModeEnabled: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val remindersEnabled: Boolean = false,
    val reminderHour: Int = 11,
    val analyticsEnabled: Boolean = false,
    val liveStatusEnabled: Boolean = false,
    val leaderboardEnabled: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
) {
    init {
        require(graceSeconds in 1..60)
        require(minimumStreakMinutes in 1..180)
        require(reminderHour in 0..23)
    }
}

data class TimeReading(val epochMillis: Long, val monotonicMillis: Long)

interface TimeProvider {
    fun epochMillis(): Long
    fun monotonicMillis(): Long
    fun now(): TimeReading = TimeReading(epochMillis(), monotonicMillis())
}

enum class DeviceOrientation { FACE_DOWN, LIFTED, UNKNOWN }
enum class SensorAvailability { AVAILABLE, UNAVAILABLE, PERMISSION_DENIED }

data class MonitoringState(
    val orientation: DeviceOrientation = DeviceOrientation.UNKNOWN,
    val availability: SensorAvailability = SensorAvailability.AVAILABLE,
    val message: String? = null,
)

interface ScreenDownSource {
    val state: StateFlow<MonitoringState>
    fun start()
    fun stop()
}

/** Monotonic checkpoints are valid only in the current process. Use restore after loading. */
data class FocusSession(
    val id: String,
    val duration: FocusDuration,
    val state: FocusState = FocusState.READY,
    val startedAtEpochMillis: Long? = null,
    val focusedMillis: Long = 0,
    val lastTickMonotonicMillis: Long? = null,
    val lastObservedAtEpochMillis: Long? = null,
    val graceDeadlineMonotonicMillis: Long? = null,
    val graceDeadlineEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val endReason: FocusEndReason? = null,
    val gracePeriodMillis: Long = 10_000,
) {
    init {
        require(id.isNotBlank())
        require(focusedMillis in 0..duration.millis)
        require(gracePeriodMillis in 1_000..60_000)
    }

    val isRunning: Boolean get() = state == FocusState.FOCUSING || state == FocusState.GRACE_PERIOD
    val isTerminal: Boolean get() = state == FocusState.COMPLETED || state == FocusState.ABANDONED
    val remainingMillis: Long get() = (duration.millis - focusedMillis).coerceAtLeast(0)
    val progress: Float get() = (focusedMillis.toDouble() / duration.millis).toFloat()
}

data class FocusHistoryEntry(
    val id: String,
    val durationMinutes: Int,
    val focusedMillis: Long,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val outcome: FocusState,
    val endReason: FocusEndReason,
) {
    init {
        require(id.isNotBlank())
        require(durationMinutes in 1..180)
        require(focusedMillis in 0..durationMinutes * 60_000L)
        require(outcome == FocusState.COMPLETED || outcome == FocusState.ABANDONED)
        require(outcome != FocusState.COMPLETED || focusedMillis == durationMinutes * 60_000L)
    }

    companion object {
        fun from(session: FocusSession): FocusHistoryEntry? {
            if (!session.isTerminal) return null
            return FocusHistoryEntry(
                id = session.id,
                durationMinutes = session.duration.minutes,
                focusedMillis = session.focusedMillis,
                startedAtEpochMillis = session.startedAtEpochMillis ?: return null,
                endedAtEpochMillis = session.endedAtEpochMillis ?: return null,
                outcome = session.state,
                endReason = session.endReason ?: return null,
            )
        }
    }
}

/** Save as one atomic document: completion and its history entry must never diverge. */
data class FocusSnapshot(
    val schemaVersion: Int = 1,
    val settings: FocusSettings = FocusSettings(),
    val activeSession: FocusSession? = null,
    val history: List<FocusHistoryEntry> = emptyList(),
    val selectedDurationMinutes: Int = 25,
) {
    init { require(selectedDurationMinutes in 1..180) }

    fun withSession(session: FocusSession): FocusSnapshot {
        val entry = FocusHistoryEntry.from(session)
        return copy(
            activeSession = session,
            history = if (entry == null || history.any { it.id == entry.id }) history else history + entry,
        )
    }
}

interface FocusRepository {
    suspend fun load(): FocusSnapshot
    suspend fun save(snapshot: FocusSnapshot)
    suspend fun clearHistory(): FocusSnapshot
}

enum class AnalyticsEvent {
    SESSION_STARTED, GRACE_ENTERED, SESSION_RECOVERED, SESSION_ABANDONED,
    SESSION_COMPLETED, RETURN_NEXT_DAY, REMINDERS_DISABLED, MEASUREMENT_PROBLEM,
    FEEDBACK_HELPFUL, FEEDBACK_NEEDS_WORK,
}

/** Implementations store aggregate event counts only, and callers must obtain opt-in first. */
interface AnalyticsRecorder {
    suspend fun record(event: AnalyticsEvent)
    suspend fun clear()
}
