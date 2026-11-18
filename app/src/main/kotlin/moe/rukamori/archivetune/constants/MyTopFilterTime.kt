package moe.rukamori.archivetune.constants

import java.time.LocalDateTime
import java.time.ZoneOffset

/** The start of [this] window as epoch millis, reading local wall time as UTC as the history queries always have. */
fun MyTopFilter.toTimeMillis(): Long =
    when (this) {
        MyTopFilter.DAY -> LocalDateTime.now().minusDays(1).toInstant(ZoneOffset.UTC).toEpochMilli()
        MyTopFilter.WEEK -> LocalDateTime.now().minusWeeks(1).toInstant(ZoneOffset.UTC).toEpochMilli()
        MyTopFilter.MONTH -> LocalDateTime.now().minusMonths(1).toInstant(ZoneOffset.UTC).toEpochMilli()
        MyTopFilter.YEAR -> LocalDateTime.now().minusMonths(12).toInstant(ZoneOffset.UTC).toEpochMilli()
        MyTopFilter.ALL_TIME -> 0
    }
