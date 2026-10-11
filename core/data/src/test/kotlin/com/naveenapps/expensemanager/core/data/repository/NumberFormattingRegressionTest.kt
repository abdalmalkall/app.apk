package com.naveenapps.expensemanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.settings.data.repository.ApplicationNumberFormatter
import com.naveenapps.expensemanager.core.settings.data.repository.NumberFormatRepositoryImpl
import com.naveenapps.expensemanager.core.settings.domain.model.NumberFormatType
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatSettingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import java.util.Locale

class NumberFormattingRegressionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    @After fun close() { scope.cancel() }

    private fun repository(locale: Locale, grouped: Boolean = false): NumberFormatRepositoryImpl {
        val settings = object : NumberFormatSettingRepository {
            private val values = MutableStateFlow(if (grouped) NumberFormatType.WITH_COMMA_SEPARATOR else NumberFormatType.WITHOUT_ANY_SEPARATOR)
            override fun getNumberFormatType() = values
            override suspend fun saveNumberFormatType(numberFormatType: NumberFormatType) { values.value = numberFormatType }
        }
        return NumberFormatRepositoryImpl(locale, settings, scope)
    }

    @Test fun incompleteOrMalformedNumericInputIsRejectedWithoutPartialParsing() {
        val numbers = repository(Locale.US, grouped = true)
        for (text in listOf("1x", "1e3", "1,23", "1,,234", "1.2.3", "NaN", "∞", "", "12 34")) {
            assertThat(numbers.parseToDouble(text)).isNull()
        }
        assertThat(numbers.parseToDouble("1,234.567")).isEqualTo(1234.567)
    }

    @Test fun germanEditingRoundTripsWithoutGroupingOrDisplayRounding() {
        val numbers = repository(Locale.GERMANY, grouped = true)
        assertThat(numbers.formatForEditing(1.2345)).isEqualTo("1,2345")
        assertThat(numbers.parseToDouble(numbers.formatForEditing(1.2345))).isEqualTo(1.2345)
        assertThat(numbers.formatForEditing("1.234,567")).isEqualTo("1234,567")
    }

    @Test fun arabicInputAndAsciiNumberPadBothPreserveMilliunits() {
        val numbers = repository(Locale.forLanguageTag("ar-JO"))
        assertThat(numbers.parseToDouble("١٢٫٣٤٥")).isEqualTo(12.345)
        assertThat(numbers.parseToDouble("12.345")).isEqualTo(12.345)
        assertThat(numbers.parseToDouble(numbers.formatForEditing(12.345))).isEqualTo(12.345)
        assertThat(numbers.parseToDouble(numbers.formatForEditing(-12.345))).isEqualTo(-12.345)
    }

    @Test fun callerCannotMutateACachedNumberFormatterUsedByOtherScreens() {
        val first = ApplicationNumberFormatter.getNumberFormatWithoutGrouping(Locale.US)
        val second = ApplicationNumberFormatter.getNumberFormatWithoutGrouping(Locale.US)
        assertThat(first).isNotSameInstanceAs(second)
        first.maximumFractionDigits = 0
        first.isGroupingUsed = true
        assertThat(second.format(1234.567)).isEqualTo("1234.567")
        assertThat(ApplicationNumberFormatter.getNumberFormatWithoutGrouping(Locale.US).format(1234.567)).isEqualTo("1234.567")
    }
}
