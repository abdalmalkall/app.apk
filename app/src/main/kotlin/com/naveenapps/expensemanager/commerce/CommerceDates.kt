package com.naveenapps.expensemanager.commerce

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar boundaries in the business zone, including historical 23/25-hour days. */
internal object CommerceDates {
    val zone: ZoneId = ZoneId.of("Asia/Amman")

    fun parse(text: String): LocalDate? = text.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        val normalized = raw.map { c -> when (c) {
            in '٠'..'٩' -> '0' + (c - '٠')
            in '۰'..'۹' -> '0' + (c - '۰')
            else -> c
        } }.joinToString("")
        LocalDate.parse(normalized)
    }

    fun start(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
    fun end(day: LocalDate): Long = start(day.plusDays(1)) - 1
    fun parseStart(text: String): Long? = parse(text)?.let(::start)
    fun endOfDay(time: Long): Long = end(Instant.ofEpochMilli(time).atZone(zone).toLocalDate())

    fun range(from: String, to: String, defaultStart: Long = 0, defaultEnd: Long = Long.MAX_VALUE): Pair<Long, Long> {
        val first = parse(from)?.let(::start) ?: defaultStart
        val last = parse(to)?.let(::end) ?: defaultEnd
        require(first <= last) { "نهاية الفترة قبل بدايتها" }
        return first to last
    }
}
