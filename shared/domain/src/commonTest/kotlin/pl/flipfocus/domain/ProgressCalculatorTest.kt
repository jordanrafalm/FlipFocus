@file:OptIn(kotlin.time.ExperimentalTime::class)

package pl.flipfocus.domain

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProgressCalculatorTest {
    private val zone = TimeZone.UTC
    private fun entry(id: String, ended: String, minutes: Int = 25, completed: Boolean = true): FocusHistoryEntry {
        val end = Instant.parse(ended).toEpochMilliseconds()
        return FocusHistoryEntry(id, minutes, minutes * 60_000L, end - minutes * 60_000L, end,
            if (completed) FocusState.COMPLETED else FocusState.ABANDONED,
            if (completed) FocusEndReason.TARGET_REACHED else FocusEndReason.USER_ABANDONED)
    }

    @Test fun completionDateCountsEvenWhenStartWasYesterday() {
        val progress = ProgressCalculator.calculate(listOf(entry("a", "2026-09-22T00:10:00Z")), LocalDate(2026, 9, 22), zone)
        assertEquals(25 * 60_000L, progress.totalFocusedMillis)
        assertEquals(1, progress.currentStreakDays)
        val yesterday = ProgressCalculator.calculate(listOf(entry("a", "2026-09-22T00:10:00Z")), LocalDate(2026, 9, 21), zone)
        assertEquals(0, yesterday.currentStreakDays)
    }

    @Test fun completionDateUsesSelectedTimezoneAcrossDstBoundary() {
        val history = listOf(entry("a", "2026-03-28T23:30:00Z"), entry("b", "2026-03-29T22:30:00Z"))
        val progress = ProgressCalculator.calculate(history, LocalDate(2026, 3, 30), TimeZone.of("Europe/Warsaw"))
        assertEquals(2, progress.currentStreakDays)
        assertEquals(1, progress.completedSessions)
    }

    @Test fun yesterdayStreakSurvivesUntilEndOfTodayButThenExpires() {
        val history = listOf(entry("a", "2026-09-20T12:00:00Z"), entry("b", "2026-09-21T12:00:00Z"))
        assertEquals(2, ProgressCalculator.calculate(history, LocalDate(2026, 9, 22), zone).currentStreakDays)
        val later = ProgressCalculator.calculate(history, LocalDate(2026, 9, 23), zone)
        assertEquals(0, later.currentStreakDays)
        assertEquals(2, later.longestStreakDays)
        assertEquals(2, ProgressCalculator.calendar(history, zone).size)
    }

    @Test fun abortedAndShortSessionsNeverQualifyForStreak() {
        val history = listOf(entry("short", "2026-09-21T10:00:00Z", minutes = 10),
            entry("cancel", "2026-09-21T12:00:00Z", completed = false))
        val progress = ProgressCalculator.calculate(history, LocalDate(2026, 9, 21), zone)
        assertEquals(10 * 60_000L, progress.totalFocusedMillis)
        assertEquals(1, progress.completedSessions)
        assertFalse(progress.qualifiesForStreak)
        assertEquals(0, progress.currentStreakDays)
    }

    @Test fun multipleCompletionsAndDuplicatesCountCorrectly() {
        val a = entry("a", "2026-09-21T10:00:00Z")
        val b = entry("b", "2026-09-21T11:00:00Z", minutes = 15)
        val progress = ProgressCalculator.calculate(listOf(a, a, b), LocalDate(2026, 9, 21), zone)
        assertEquals(40 * 60_000L, progress.totalFocusedMillis)
        assertEquals(2, progress.completedSessions)
        assertEquals(1, progress.currentStreakDays)
    }
}
