package pro.masterdoc.dashboard

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

object WeekDates {
    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    fun parseDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value, fmt)
        } catch (_: DateTimeParseException) {
            null
        }

    fun format(date: LocalDate): String = date.format(fmt)

    fun isMonday(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.MONDAY

    fun mondayOnOrBefore(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
