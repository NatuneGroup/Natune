package moe.rukamori.archivetune.shared.domain.repository

/**
 * The My Top periods. Each one is the listening window `(from, now]`, and [ALL_TIME] has no lower bound.
 *
 * The baseline built these bounds from wall-clock fields read as UTC, which moved every bound by the device offset.
 * The bounds here are real instants. The host supplies the zone offset at the moment it asks. The window then steps
 * back by whole units on the local clock, so a month ago keeps the local day of the month, and a day that does not
 * exist in the earlier month clamps to that month's last day, as java.time does.
 */
enum class MyTopPeriod(private val months: Int, private val days: Int) {
    ALL_TIME(months = 0, days = 0),
    DAY(months = 0, days = 1),
    WEEK(months = 0, days = 7),
    MONTH(months = 1, days = 0),
    YEAR(months = 12, days = 0),
    ;

    /** The window of this period as of [nowMs], given the host zone's offset from UTC at that instant. */
    fun window(nowMs: Long, zoneOffsetMs: Long): ListeningWindow {
        if (this == ALL_TIME) return ListeningWindow(Long.MIN_VALUE, nowMs)
        val wallMs = nowMs + zoneOffsetMs
        val (year, month, day) = civilFromEpochDay(wallMs.floorDiv(MY_TOP_DAY_MS))
        val monthIndex = year * 12L + (month - 1) - months
        val startYear = monthIndex.floorDiv(12L)
        val startMonth = monthIndex.mod(12L).toInt() + 1
        val startDay = minOf(day, daysInMonth(startYear, startMonth))
        val startWallDay = epochDayFromCivil(startYear, startMonth, startDay) - days
        val startWallMs = startWallDay * MY_TOP_DAY_MS + wallMs.mod(MY_TOP_DAY_MS)
        return ListeningWindow(startWallMs - zoneOffsetMs, nowMs)
    }
}

private const val MY_TOP_DAY_MS = 86_400_000L

/** The proleptic Gregorian year, 1-based month and day of [epochDay], using Howard Hinnant's civil calendar rules. */
private fun civilFromEpochDay(epochDay: Long): Triple<Long, Int, Int> {
    val shifted = epochDay + 719_468L
    val era = (if (shifted >= 0L) shifted else shifted - 146_096L) / 146_097L
    val dayOfEra = shifted - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    // March-based month, so February, which may have 29 days, closes the year.
    val marchMonth = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * marchMonth + 2L) / 5L + 1L).toInt()
    val month = (if (marchMonth < 10L) marchMonth + 3L else marchMonth - 9L).toInt()
    val yearAdjust = if (month <= 2) 1L else 0L
    return Triple(yearOfEra + era * 400L + yearAdjust, month, day)
}

/** The epoch day of a civil date, the inverse of [civilFromEpochDay]. [month] is 1-based. */
private fun epochDayFromCivil(year: Long, month: Int, day: Int): Long {
    val adjustedYear = if (month <= 2) year - 1L else year
    val era = (if (adjustedYear >= 0L) adjustedYear else adjustedYear - 399L) / 400L
    val yearOfEra = adjustedYear - era * 400L
    val marchMonth = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153L * marchMonth + 2L) / 5L + day - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

private fun daysInMonth(year: Long, month: Int): Int =
    when (month) {
        2 -> if (isLeapYear(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

private fun isLeapYear(year: Long): Boolean = (year % 4L == 0L && year % 100L != 0L) || year % 400L == 0L
