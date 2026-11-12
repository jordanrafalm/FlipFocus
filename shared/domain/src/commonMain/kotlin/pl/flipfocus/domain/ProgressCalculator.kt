@file:OptIn(kotlin.time.ExperimentalTime::class)

package pl.flipfocus.domain

import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

data class FocusDay(
    val date: LocalDate,
    val totalFocusedMillis: Long,
    val completedSessions: Int,
    val qualifiesForStreak: Boolean,
)

data class DailyProgress(
    val today: LocalDate,
    val totalFocusedMillis: Long,
    val completedSessions: Int,
    val qualifiesForStreak: Boolean,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
)

object ProgressCalculator {
    /** Completion time determines the day. Interrupted time never counts as completed focus. */
    fun calendar(
        history: List<FocusHistoryEntry>,
        timeZone: TimeZone,
        minimumStreakMinutes: Int = 15,
    ): List<FocusDay> {
        require(minimumStreakMinutes in 1..180)
        return history.distinctBy { it.id }
            .filter { it.outcome == FocusState.COMPLETED }
            .groupBy { Instant.fromEpochMilliseconds(it.endedAtEpochMillis).toLocalDateTime(timeZone).date }
            .map { (date, sessions) ->
                FocusDay(
                    date = date,
                    totalFocusedMillis = sessions.sumOf { it.focusedMillis },
                    completedSessions = sessions.size,
                    qualifiesForStreak = sessions.any { it.durationMinutes >= minimumStreakMinutes },
                )
            }.sortedBy { it.date }
    }

    fun calculate(
        history: List<FocusHistoryEntry>,
        today: LocalDate,
        timeZone: TimeZone,
        minimumStreakMinutes: Int = 15,
    ): DailyProgress {
        val days = calendar(history, timeZone, minimumStreakMinutes)
        val day = days.firstOrNull { it.date == today }
        val qualifyingDates = days.filter { it.qualifiesForStreak && it.date <= today }.map { it.date }.toSet()
        var cursor = if (today in qualifyingDates) today else today.minus(1, DateTimeUnit.DAY)
        var currentStreak = 0
        while (cursor in qualifyingDates) {
            currentStreak++
            cursor = cursor.minus(1, DateTimeUnit.DAY)
        }
        var longestStreak = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in qualifyingDates.sorted()) {
            run = if (previous == date.minus(1, DateTimeUnit.DAY)) run + 1 else 1
            longestStreak = maxOf(longestStreak, run)
            previous = date
        }
        return DailyProgress(
            today = today,
            totalFocusedMillis = day?.totalFocusedMillis ?: 0,
            completedSessions = day?.completedSessions ?: 0,
            qualifiesForStreak = day?.qualifiesForStreak ?: false,
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
        )
    }
}
