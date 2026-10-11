package com.naveenapps.expensemanager.core.settings.data.repository

import com.naveenapps.expensemanager.core.settings.domain.model.NumberFormatType
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatRepository
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatSettingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.text.NumberFormat
import java.text.DecimalFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NumberFormatRepositoryImpl(
    private val locale: Locale = Locale.getDefault(),
    numberFormatSettingRepository: NumberFormatSettingRepository,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : NumberFormatRepository {

    @Volatile
    private var numberFormatType: NumberFormatType = NumberFormatType.WITHOUT_ANY_SEPARATOR

    init {
        numberFormatSettingRepository.getNumberFormatType().onEach {
            numberFormatType = it
        }.launchIn(coroutineScope)
    }

    private fun getFormatter(): NumberFormat {
        return when (numberFormatType) {
            NumberFormatType.WITHOUT_ANY_SEPARATOR -> {
                ApplicationNumberFormatter.getNumberFormatWithoutGrouping(locale)
            }

            NumberFormatType.WITH_COMMA_SEPARATOR -> {
                ApplicationNumberFormatter.getNumberFormatWithGrouping(locale)
            }
        }
    }

    /**
     * Converts a Double or numeric string into localized display format
     * Example: 1234.5 → "1.234,5" (DE) or "1234.5" (US, no grouping)
     */
    override fun formatForDisplay(value: Double): String {
        return getFormatter().format(value)
    }

    /**
     * Removes grouping while retaining the locale's decimal separator for editing.
     * Example: "1.234,5" (DE) → "1234,5"
     */
    override fun formatForEditing(localizedValue: String): String {
        val parsed = parseToDouble(localizedValue)
        return parsed?.let(::formatForEditing) ?: localizedValue
    }

    /**
     * Formats an editable value without grouping or display rounding.
     */
    override fun formatForEditing(value: Double): String {
        return ApplicationNumberFormatter.getNumberFormatWithoutGrouping(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 340
        }.format(value)
    }

    /**
     * Parses localized string to Double
     * Example: "1.234,5" (DE) → 1234.5
     */
    override fun parseToDouble(localizedValue: String): Double? {
        val formatter = getFormatter()
        val decimalFormatter = formatter as? DecimalFormat ?: return null
        val symbols = decimalFormatter.decimalFormatSymbols
        val text = localizedValue.trim().let {
            // The number pad emits '.', including on Arabic devices. Without grouping
            // this is unambiguous and can be normalized to the local decimal separator.
            if (!formatter.isGroupingUsed && symbols.decimalSeparator != '.') {
                it.replace('.', symbols.decimalSeparator)
            } else it
        }
        val decimal = Regex.escape(symbols.decimalSeparator.toString())
        val grouping = Regex.escape(symbols.groupingSeparator.toString())
        val digit = "\\p{Nd}"
        val integer = if (formatter.isGroupingUsed) {
            "(?:$digit+|$digit{1,3}(?:$grouping$digit{3})+)"
        } else "$digit+"
        val sign = "(?:-|${Regex.escape(decimalFormatter.negativePrefix)})?"
        if (!text.matches(Regex("$sign$integer(?:$decimal$digit+)?"))) return null
        val position = ParsePosition(0)
        return formatter.parse(text, position)?.toDouble()?.takeIf {
            position.index == text.length && position.errorIndex < 0 && it.isFinite()
        }
    }
}


object ApplicationNumberFormatter {

    private val normalNumberFormatCache = ConcurrentHashMap<Locale, NumberFormat>()
    private val defaultNumberFormatCache = ConcurrentHashMap<Locale, NumberFormat>()

    fun getNumberFormatWithGrouping(locale: Locale): NumberFormat {
        return normalNumberFormatCache.computeIfAbsent(locale) {
            NumberFormat.getNumberInstance(locale).apply {
                isGroupingUsed = true
                maximumFractionDigits = 3
                minimumFractionDigits = 0
            }
        }.clone() as NumberFormat
    }

    fun getNumberFormatWithoutGrouping(locale: Locale): NumberFormat {
        return defaultNumberFormatCache.computeIfAbsent(locale) {
            NumberFormat.getNumberInstance(locale).apply {
                isGroupingUsed = false
                maximumFractionDigits = 3
                minimumFractionDigits = 1
            }
        }.clone() as NumberFormat
    }
}
