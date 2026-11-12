package pl.flipfocus.platform

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderCalendarTest {
    @Test
    fun regularWeekdayIsAllowed() {
        assertTrue(ReminderCalendar.isWorkday(LocalDate(2026, 9, 25)))
    }

    @Test
    fun weekendsAndPolishHolidaysAreSkipped() {
        assertFalse(ReminderCalendar.isWorkday(LocalDate(2026, 9, 26)))
        assertFalse(ReminderCalendar.isWorkday(LocalDate(2026, 11, 11)))
        assertFalse(ReminderCalendar.isWorkday(LocalDate(2026, 4, 6)))
        assertFalse(ReminderCalendar.isWorkday(LocalDate(2026, 6, 4)))
    }
}