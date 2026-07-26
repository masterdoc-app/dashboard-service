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

    fun spanWorkingDays(start: LocalDate, durationHours: Int): List<LocalDate> {
        val spanDays = maxOf(1, (durationHours + 7) / 8) // ceil for positive ints
        val out = ArrayList<LocalDate>(spanDays)
        var d = start
        while (out.size < spanDays) {
            val dow = d.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                out.add(d)
            }
            d = d.plusDays(1)
        }
        return out
    }

    fun intersectsWeek(occupied: List<LocalDate>, weekMonday: LocalDate): Boolean {
        val end = weekMonday.plusDays(7) // exclusive
        return occupied.any { !it.isBefore(weekMonday) && it.isBefore(end) }
    }

    fun intersectsRange(occupied: List<LocalDate>, rangeStart: LocalDate, rangeEndExclusive: LocalDate): Boolean =
        occupied.any { !it.isBefore(rangeStart) && it.isBefore(rangeEndExclusive) }
}
