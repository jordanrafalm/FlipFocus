package pl.flipfocus.platform

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Polish workdays used by the local focus reminder schedule. */
object ReminderCalendar {
    fun isWorkday(date: LocalDate): Boolean {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        val easter = easterSunday(date.year)
        val fixedHoliday = date.monthNumber to date.dayOfMonth in setOf(
            1 to 1, 1 to 6, 5 to 1, 5 to 3, 8 to 15,
            11 to 1, 11 to 11, 12 to 24, 12 to 25, 12 to 26,
        )
        return !fixedHoliday && date != easter.plus(1, DateTimeUnit.DAY) && date != easter.plus(49, DateTimeUnit.DAY) &&
            date != easter.plus(60, DateTimeUnit.DAY)
    }

    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate(year, month, day)
    }
}