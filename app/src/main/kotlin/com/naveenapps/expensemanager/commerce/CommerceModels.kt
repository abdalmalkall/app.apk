package com.naveenapps.expensemanager.commerce

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Monetary values are integer thousandths; quantities and tax rates are exact decimals. */
object Money {
    const val MAX = 1_000_000_000_000L
    fun decimal(text: String): BigDecimal {
        val normalized = text.trim().map { when (it) {
            in '٠'..'٩' -> '0' + (it - '٠')
            in '۰'..'۹' -> '0' + (it - '۰')
            '٫' -> '.'
            else -> it
        } }.joinToString("")
        require(normalized.length <= 64 && normalized.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) { "أدخل رقمًا عشريًا صحيحًا" }
        return normalized.toBigDecimalOrNull() ?: error("أدخل رقمًا صحيحًا")
    }
    fun parse(text: String): Long {
        val number = decimal(text.ifBlank { "0" })
        require(number >= BigDecimal.ZERO && number <= BigDecimal.valueOf(MAX, 3)) { "المبلغ خارج النطاق أو سالب" }
        require(number.stripTrailingZeros().scale() <= 3) { "دقة المبلغ حتى ثلاث منازل عشرية" }
        return number.setScale(3, RoundingMode.UNNECESSARY).movePointRight(3).longValueExact()
    }
    fun format(value: Long): String = BigDecimal.valueOf(value, 3).toPlainString()
    fun quantity(text: String, positive: Boolean = false): BigDecimal {
        val q = decimal(text).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        require(q.scale() <= 6 && q <= BigDecimal("1000000000") && (if (positive) q > BigDecimal.ZERO else q >= BigDecimal.ZERO)) { "الكمية خارج النطاق أو تتجاوز ست منازل عشرية" }
        return q
    }
    fun line(quantity: String, price: Long): Long {
        val q = Money.quantity(quantity, true)
        require(price in 0..MAX) { "سعر غير صالح" }
        val amount = q.multiply(BigDecimal.valueOf(price)).setScale(0, RoundingMode.HALF_UP)
        require(amount <= BigDecimal.valueOf(MAX)) { "قيمة البند كبيرة جدًا" }
        return amount.longValueExact()
    }
    fun totals(lines: List<Long>, discount: Long, taxRate: String): Totals {
        val subtotal = lines.fold(0L, Math::addExact)
        require(subtotal <= MAX && discount in 0..subtotal) { "الخصم يتجاوز قيمة الفاتورة" }
        val rate = decimal(taxRate.ifBlank { "0" })
        require(rate >= BigDecimal.ZERO && rate <= BigDecimal("100")) { "الضريبة بين 0 و100%" }
        val tax = BigDecimal.valueOf(subtotal - discount).multiply(rate).divide(BigDecimal("100"), 0, RoundingMode.HALF_UP).longValueExact()
        val total = Math.addExact(subtotal - discount, tax)
        require(total <= MAX) { "الإجمالي خارج النطاق" }
        return Totals(subtotal, discount, tax, total)
    }
    /** Round the cumulative returned value once, independent of how many notes were issued. */
    internal fun returnCredit(items: List<InvoiceItem>, total: Long, subtotal: Long): Long {
        if (items.all { decimal(it.returned) == decimal(it.quantity) }) return total
        if (subtotal == 0L) return 0L
        val weighted = items.fold(BigDecimal.ZERO) { a, l ->
            a + BigDecimal.valueOf(l.total).multiply(decimal(l.returned)).divide(decimal(l.quantity), 18, RoundingMode.HALF_UP)
        }
        return weighted.multiply(BigDecimal.valueOf(total)).divide(BigDecimal.valueOf(subtotal), 0, RoundingMode.HALF_UP).longValueExact()
    }
}
data class Totals(val subtotal: Long, val discount: Long, val tax: Long, val total: Long)
data class Product(
    val id: String = "", val name: String, val sku: String, val barcode: String = "",
    val category: String = "رمل", val type: String = "سلعة", val description: String = "",
    val price: Long = 0, val cost: Long? = null, val unit: String = "طن",
    val trackStock: Boolean = false, val stock: String = "0", val minimum: String = "0",
    val notes: String = "", val active: Boolean = true,
)
data class Customer(val id: String = "", val name: String, val phone: String = "", val address: String = "", val type: String = "فرد", val notes: String = "")
data class DraftLine(val productId: String, val quantity: String, val price: Long, val unit: String)
data class InvoiceItem(val id: Long, val invoiceId: String, val productId: String, val name: String, val sku: String, val quantity: String, val unit: String, val price: Long, val cost: Long?, val total: Long, val returned: String = "0", val tracked: Boolean = false)
data class Invoice(
    val id: String, val number: String, val customerId: String?, val customerName: String,
    val created: Long, val due: Long?, val subtotal: Long, val discount: Long, val taxRate: String,
    val tax: Long, val total: Long, val method: String, val notes: String, val state: String,
    val currency: String, val credit: Long = 0, val paid: Long = 0,
) {
    val net: Long get() = total - credit
    val remaining: Long get() = net - paid
    val status: String get() = when {
        state == "CANCELLED" -> "ملغاة"
        state == "RETURNED" -> "مرتجعة"
        net == 0L && credit > 0 -> "مرتجعة"
        remaining == 0L -> "مدفوعة"
        paid > 0L -> "جزئية"
        else -> "غير مدفوعة"
    }
}
data class Payment(val id: String, val receipt: String, val invoiceId: String, val amount: Long, val created: Long, val method: String, val notes: String, val origin: String)
data class Movement(val id: String, val invoiceId: String?, val kind: String, val amount: Long, val created: Long, val source: String, val method: String, val notes: String)
data class Audit(val id: Long, val created: Long, val action: String, val target: String, val details: String)
data class Snapshot(val products: List<Product> = emptyList(), val customers: List<Customer> = emptyList(), val invoices: List<Invoice> = emptyList(), val items: List<InvoiceItem> = emptyList(), val payments: List<Payment> = emptyList(), val movements: List<Movement> = emptyList(), val settings: Map<String, String> = emptyMap(), val audit: List<Audit> = emptyList(), val legacyReviewPending: Int = 0) {
    private val customerBalances by lazy { invoices.filter { it.customerId != null }.groupBy { it.customerId }.mapValues { (_, records) -> records.sumOf { it.remaining } } }
    fun balance(customerId: String): Long = customerBalances[customerId] ?: 0L
    fun sales(from: Long = 0, to: Long = Long.MAX_VALUE): Long = movements.filter { it.kind in listOf("SALE", "RETURN") && it.created in from..to }.sumOf { it.amount }
    fun cash(from: Long = 0, to: Long = Long.MAX_VALUE): Long = movements.filter { it.kind in listOf("INVOICE_PAYMENT", "DEBT_COLLECTION", "OTHER_INCOME", "REFUND") && it.created in from..to }.sumOf { it.amount }
    val debt: Long get() = invoices.sumOf { it.remaining }
    val currency: String get() = settings["currency"] ?: "JOD"
}
val paymentMethods = listOf("نقدي", "تحويل بنكي", "محفظة إلكترونية")
fun dateText(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Amman")).format(Instant.ofEpochMilli(time))
fun parseDate(text: String): Long? = CommerceDates.parseStart(text)

internal fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var count = 0
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, limit - count + 1))
        if (read < 0) break
        count += read
        require(count <= limit) { "حجم الملف يتجاوز الحد المسموح" }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

fun Snapshot.findProductByCode(code: String): Product? {
    val value = code.trim()
    if (value.isBlank()) return null
    return products.firstOrNull { it.active && it.barcode == value }
        ?: products.firstOrNull { it.active && it.sku == value }
}
