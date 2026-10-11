package com.naveenapps.expensemanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.common.utils.*
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class DateFormattingRegressionTest {
    private val originalLocale = Locale.getDefault()
    private val originalZone = java.util.TimeZone.getDefault()

    @After fun restoreDefaults() {
        Locale.setDefault(originalLocale)
        java.util.TimeZone.setDefault(originalZone)
    }

    private fun instant(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test fun monthlyBudgetBoundariesUseFirstDayAndNextMonthAcrossLeapYearAndDst() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        val february = Date(instant("2024-02-20T12:00:00-05:00[America/New_York]"))
        assertThat(february.getStartOfTheMonth()).isEqualTo(instant("2024-02-01T00:00:00-05:00[America/New_York]"))
        assertThat(february.getEndOfTheMonth()).isEqualTo(instant("2024-03-01T00:00:00-05:00[America/New_York]"))
        val march = Date(instant("2024-03-20T12:00:00-04:00[America/New_York]"))
        assertThat(march.getStartOfTheMonth()).isEqualTo(instant("2024-03-01T00:00:00-05:00[America/New_York]"))
        assertThat(march.getEndOfTheMonth()).isEqualTo(instant("2024-04-01T00:00:00-04:00[America/New_York]"))
    }

    @Test fun yearlyBudgetBoundariesStartJanuaryFirstAndEndNextJanuary() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Amman"))
        val sample = Date(instant("2024-06-20T12:00:00+03:00[Asia/Amman]"))
        assertThat(sample.getStartOfTheYear()).isEqualTo(instant("2024-01-01T00:00:00+03:00[Asia/Amman]"))
        assertThat(sample.getEndOfTheYear()).isEqualTo(instant("2025-01-01T00:00:00+03:00[Asia/Amman]"))
    }

    @Test fun sundayBelongsToTheNewSundayBasedWeek() {
        val sunday = instant("2026-10-11T12:00:00+03:00[Asia/Amman]")
        val range = getThisWeekRange(TimeZone.of("Asia/Amman"), sunday)
        assertThat(range.first()).isEqualTo(instant("2026-10-11T00:00:00+03:00[Asia/Amman]"))
        assertThat(range.last()).isEqualTo(instant("2026-10-18T00:00:00+03:00[Asia/Amman]"))
    }

    @Test fun localizedDatesFollowRuntimeLocaleChangesWhileKeysRemainStable() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val sample = Date(instant("2026-03-15T12:00:00Z[UTC]"))
        Locale.setDefault(Locale.US)
        assertThat(sample.toMonthAndYear()).isEqualTo("March 2026")
        Locale.setDefault(Locale.GERMANY)
        assertThat(sample.toMonthAndYear()).isEqualTo("März 2026")
        assertThat(sample.toMonthAndYearKey()).isEqualTo("March 2026")
    }

    @Test fun numericDateFormattingFollowsTimeZoneChangesAndIsSafeUnderConcurrentUse() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val nearMidnight = Date(instant("2026-10-10T22:00:00Z[UTC]"))
        assertThat(nearMidnight.toCompleteDateWithDate()).isEqualTo("10/10/2026")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Amman"))
        assertThat(nearMidnight.toCompleteDateWithDate()).isEqualTo("11/10/2026")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..400).map { index -> executor.submit<Boolean> {
                val day = index % 28 + 1
                val date = Date(ZonedDateTime.of(2026, 10, day, 12, 0, 0, 0, ZoneId.of("Asia/Amman")).toInstant().toEpochMilli())
                val expected = "%02d/10/2026".format(Locale.US, day)
                date.toCompleteDateWithDate() == expected && date.toCompleteDateWithDate().fromCompleteDate().toCompleteDateWithDate() == expected
            } }
            assertThat(jobs.all { it.get() }).isTrue()
        } finally { executor.shutdownNow() }
    }
}
