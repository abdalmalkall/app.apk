package com.naveenapps.expensemanager.commerce

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** Recalculate financial invariants; used only against staged imports or a transaction under review. */
internal object CommerceValidation {
    private fun money(value: Long) = require(value in 0..Money.MAX) { "مبلغ خارج النطاق" }
    private fun quantity(text: String, positive: Boolean = false): BigDecimal = Money.quantity(text, positive)
    internal fun validateSettings(settings: Map<String, String>) {
        require(settings["currency"].orEmpty().matches(Regex("[A-Z]{3}")) && !settings["business"].isNullOrBlank()) { "إعدادات المؤسسة أو العملة غير صحيحة" }
        Money.totals(listOf(1000),0,settings["tax"] ?: "0")
        require(settings["paper"] == null || settings["paper"] in listOf("A4", "58mm", "80mm")) { "مقاس الطباعة غير مدعوم" }
        require(settings["theme"] == null || settings["theme"] in listOf("light", "dark")) { "المظهر غير مدعوم" }
        settings["units"]?.let { units ->
            val entries = units.split(',', '،').map { it.trim() }
            require(entries.isNotEmpty() && entries.all { it.isNotBlank() } && entries.distinct().size == entries.size) { "أدخل وحدات قياس واضحة دون تكرار أو عناصر فارغة" }
        }
        val logo=settings["logo"].orEmpty()
        if(logo.isNotBlank()) {
            require(logo.length<=2_800_000) { "شعار كبير جدًا" }
            val bytes=try { android.util.Base64.decode(logo,android.util.Base64.DEFAULT) } catch(e:Exception) { throw IllegalArgumentException("شعار غير صالح",e) }
            require(bytes.size<=2_000_000) { "شعار كبير جدًا" }
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512) { "صورة الشعار غير صالحة" }
        }
    }
    fun validate(db: SQLiteDatabase, invoices: List<Invoice>, items: List<InvoiceItem>, products: List<Product>) {
        fun rows(sql: String): List<JSONObject> = CommerceStore.query(db, sql) { c -> JSONObject().apply {
            c.columnNames.forEachIndexed { n, key -> put(key, when(c.getType(n)) { android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(n); else -> c.getString(n) }) }
        } }
        require(CommerceStore.query(db,"PRAGMA integrity_check") { it.getString(0) } == listOf("ok")) { "قاعدة غير سليمة" }
        require(CommerceStore.query(db,"PRAGMA foreign_key_check") { it.getString(0) }.isEmpty()) { "علاقات غير صحيحة" }
        val settings = rows("SELECT * FROM settings").associate { it.getString("key") to it.getString("value") }
        val currency = settings["currency"].orEmpty()
        validateSettings(settings)
        products.forEach { p ->
            require(p.id.isNotBlank() && p.name.isNotBlank() && p.sku.isNotBlank() && p.unit.isNotBlank()) { "منتج غير صالح" }
            money(p.price); p.cost?.let(::money); quantity(p.stock); quantity(p.minimum)
        }
        rows("SELECT * FROM products").forEach { require(it.getLong("track_stock") in 0..1 && it.getLong("active") in 0..1) { "حالة منتج غير صحيحة" } }
        rows("SELECT * FROM customers").forEach { require(it.getString("id").isNotBlank() && it.getString("name").isNotBlank()) { "عميل غير صالح" } }
        val payments = rows("SELECT * FROM payments ORDER BY rowid")
        val credits = rows("SELECT * FROM credit_notes ORDER BY rowid")
        val movements = rows("SELECT * FROM financial_transactions ORDER BY rowid")
        val invoiceIds = invoices.map { it.id }.toSet()
        val itemsByInvoice = items.groupBy { it.invoiceId }
        val paymentsByInvoice = payments.groupBy { it.getString("invoice_id") }
        val creditsByInvoice = credits.groupBy { it.getString("invoice_id") }
        val movementsByInvoice = movements.filter { !it.isNull("invoice_id") }.groupBy { it.getString("invoice_id") }
        movements.forEach { m ->
            val kind = m.getString("kind"); val amount = m.getLong("amount")
            require(m.getString("id").isNotBlank() && m.getString("method").isNotBlank() && m.getString("source").isNotBlank() && m.getLong("created") >= 0) { "حركة مالية غير صالحة" }
            if (kind == "OTHER_INCOME") {
                require(m.isNull("invoice_id") && amount in 1..Money.MAX && m.getString("source").isNotBlank()) { "دخل آخر غير صالح" }
            } else {
                require(!m.isNull("invoice_id") && m.getString("invoice_id") in invoiceIds) { "حركة مالية بلا فاتورة" }
                require(if (kind in listOf("RETURN","REFUND")) amount in -Money.MAX..0 else amount in 0..Money.MAX) { "إشارة أو قيمة حركة غير صحيحة" }
            }
        }
        val archivedTransactions=rows("SELECT * FROM legacy_archive").firstOrNull { it.getString("name")=="transaction" }?.let { JSONArray(it.getString("json")).objects() }.orEmpty()
        val unclassified=archivedTransactions.filter { classifyLegacy(it) in listOf("SUPPLIER","AMBIGUOUS") }.map { "legacy:${it.getString("id")}" }.toSet()
        rows("SELECT * FROM invoices").forEach { require(it.getString("id") !in unclassified || it.getLong("excluded")==1L) { "ترحيل سابق خاطئ؛ صحح الدفتر من الإعدادات ثم أنشئ نسخة جديدة" } }
        val repairs=rows("SELECT * FROM operations").map { it.getString("token") }.toSet()
        rows("SELECT * FROM invoices").forEach {
            require(it.getLong("excluded") in 0..1 && it.getString("token").isNotBlank())
            if(it.getLong("excluded")==1L) require(it.getString("id") in unclassified && "legacy-repair:${it.getString("id")}" in repairs) { "سجل معزول بلا أصل أو أثر تصحيح" }
        }
        invoices.forEach { i ->
            require(i.id.isNotBlank() && i.created >= 0 && (i.due == null || i.due >= 0) && i.currency == currency && i.method.isNotBlank()) { "فاتورة أو عملة غير صالحة" }
            val lines = itemsByInvoice[i.id].orEmpty(); require(lines.isNotEmpty()) { "فاتورة بلا بنود" }
            lines.forEach { l ->
                require(l.id > 0 && l.name.isNotBlank() && l.sku.isNotBlank() && l.unit.isNotBlank())
                quantity(l.quantity,true); quantity(l.returned); money(l.price); l.cost?.let { money(it); Money.line(l.quantity,it) }
                require(Money.line(l.quantity,l.price) == l.total && quantity(l.returned) <= quantity(l.quantity)) { "بند أو مرتجع غير متوازن" }
            }
            val computed = Money.totals(lines.map { it.total },i.discount,i.taxRate)
            require(computed == Totals(i.subtotal,i.discount,i.tax,i.total)) { "إجمالي لا يطابق البنود والخصم والضريبة" }
            val ps = paymentsByInvoice[i.id].orEmpty()
            ps.forEach { p -> require(p.getString("id").isNotBlank() && p.getLong("amount") in 1..Money.MAX && p.getString("token").isNotBlank() && p.getString("method").isNotBlank() && p.getLong("created") >= i.created && (p.getString("origin") != "INVOICE_PAYMENT" || p.getLong("created") == i.created)) { "دفعة غير صالحة" } }
            require(ps.count { it.getString("origin") == "INVOICE_PAYMENT" } <= 1) { "دفعات إصدار مكررة" }
            val cs = creditsByInvoice[i.id].orEmpty()
            val returned = lines.associate { it.id.toString() to BigDecimal.ZERO }.toMutableMap()
            var missingHistoricalQuantities = false
            var cumulativeStarted = false
            var historicalNotes = 0
            var credited = 0L
            cs.forEachIndexed { index, c ->
                money(c.getLong("amount")); money(c.getLong("refund"))
                require(c.getString("id").isNotBlank() && c.getString("reason").isNotBlank() && c.getString("token").isNotBlank() && c.getLong("created") >= i.created) { "تسوية غير صالحة" }
                val detail = JSONObject(c.getString("quantities"))
                val cumulative = detail.has("rounding")
                val qs = if (cumulative) {
                    require(detail.keys().asSequence().toSet() == setOf("rounding", "items", "before") && detail.getString("rounding") == "CUMULATIVE_V1") { "سياسة تقريب المرتجع غير مدعومة" }
                    val before = detail.getJSONObject("before")
                    require(before.keys().asSequence().toSet() == returned.keys) { "لقطة المرتجع لا تغطي بنود الفاتورة" }
                    val previous = before.keys().asSequence().associateWith { key ->
                        require(before.get(key) is String)
                        quantity(before.getString(key)).also { q -> require(q <= quantity(lines.first { it.id.toString() == key }.returned)) }
                    }
                    if (missingHistoricalQuantities) {
                        require(!cumulativeStarted && previous.all { (key, q) -> q >= returned.getValue(key) }) { "لقطة مرتجع لا تطابق التاريخ المحفوظ" }
                        val priorItems = lines.map { it.copy(returned = previous.getValue(it.id.toString()).toPlainString()) }
                        require(kotlin.math.abs(Money.returnCredit(priorItems, i.total, i.subtotal) - credited) <= index / 2L) { "رصيد المرتجع التاريخي لا يطابق اللقطة" }
                        returned.putAll(previous)
                        missingHistoricalQuantities = false
                    } else require(previous == returned) { "لقطة المرتجع لا تطابق الكميات السابقة" }
                    cumulativeStarted = true
                    detail.getJSONObject("items").also { require(it.keys().asSequence().toSet() == returned.keys) { "مرتجع لا يغطي البنود" } }
                } else {
                    historicalNotes++
                    if (detail.length() == 0) {
                        require(!cumulativeStarted) { "تسوية قديمة دون كميات بعد مرتجع حديث" }
                        missingHistoricalQuantities = true
                    }
                    detail
                }
                var noteBase = BigDecimal.ZERO
                qs.keys().forEach { key ->
                    val line = lines.firstOrNull { it.id.toString()==key } ?: error("مرتجع مرتبط ببند آخر")
                    require(qs.get(key) is String) { "كمية المرتجع يجب أن تكون نصًا عشريًا" }
                    val q = quantity(qs.getString(key))
                    noteBase += BigDecimal.valueOf(line.total).multiply(q).divide(quantity(line.quantity),12,RoundingMode.HALF_UP)
                    returned[key]=returned.getValue(key)+q
                    require(returned.getValue(key)<=quantity(line.returned)) { "تسويات تتجاوز كمية المرتجع" }
                }
                if (cumulative) {
                    val afterItems = lines.map { it.copy(returned = returned.getValue(it.id.toString()).toPlainString()) }
                    val expected = (Money.returnCredit(afterItems, i.total, i.subtotal) - credited).coerceIn(0, i.total - credited)
                    require(c.getLong("amount") == expected && qs.keys().asSequence().any { quantity(qs.getString(it)) > BigDecimal.ZERO }) { "التسوية لا تطابق القيمة التراكمية للمرتجع" }
                } else if (!missingHistoricalQuantities) {
                    val entire=lines.all { returned.getValue(it.id.toString())==quantity(it.quantity) }
                    val expected=if(entire) i.total-credited else if(i.subtotal==0L) 0L else noteBase.multiply(BigDecimal.valueOf(i.total)).divide(BigDecimal.valueOf(i.subtotal),0,RoundingMode.HALF_UP).longValueExact().coerceAtMost(i.total-credited)
                    require(c.getLong("amount")==expected && qs.keys().asSequence().any { quantity(qs.getString(it))>BigDecimal.ZERO }) { "تسوية لا تطابق بنودها" }
                }
                credited=Math.addExact(credited,c.getLong("amount"))
            }
            if (!missingHistoricalQuantities) require(lines.all { returned.getValue(it.id.toString())==quantity(it.returned) }) { "كميات المرتجعات لا تطابق التسويات" }
            val allReturned=lines.all { quantity(it.returned)==quantity(it.quantity) }
            val weighted=lines.fold(BigDecimal.ZERO) { a,l -> a+BigDecimal.valueOf(l.total).multiply(quantity(l.returned)).divide(quantity(l.quantity),18,RoundingMode.HALF_UP) }
            val expectedCredit=if(allReturned) i.total else if(i.subtotal==0L) 0L else weighted.multiply(BigDecimal.valueOf(i.total)).divide(BigDecimal.valueOf(i.subtotal),0,RoundingMode.HALF_UP).longValueExact()
            val credit=cs.fold(0L) { a,c -> Math.addExact(a,c.getLong("amount")) }
            require(credit in 0..i.total && kotlin.math.abs(credit-expectedCredit)<=historicalNotes/2L && (weighted>BigDecimal.ZERO || credit==0L)) { "مبالغ التسوية لا تطابق الكميات المرتجعة" }
            val grossPaid=ps.fold(0L) { a,p -> Math.addExact(a,p.getLong("amount")) }
            require(grossPaid <= i.total) { "مقبوضات تتجاوز إجمالي الفاتورة الأصلي" }
            val refund=cs.fold(0L) { a,c -> Math.addExact(a,c.getLong("refund")) }
            require(refund==(grossPaid-(i.total-credit)).coerceAtLeast(0L)) { "رد مبالغ لا يطابق المقبوض والصافي" }
            require(i.paid==grossPaid-refund && i.paid>=0 && i.remaining>=0 && i.credit==credit) { "دفعات أو ذمة غير متوازنة" }
            require(i.customerId!=null || i.remaining==0L) { "بيع آجل بلا عميل" }
            // Equal timestamps do not establish order; validate the complete millisecond group.
            var eventNet = i.total
            var eventPaid = 0L
            val receiptsByTime = ps.groupBy { it.getLong("created") }
            val notesByTime = cs.groupBy { it.getLong("created") }
            (receiptsByTime.keys + notesByTime.keys).sorted().forEach { time ->
                val received = receiptsByTime[time].orEmpty().sumOf { it.getLong("amount") }
                val notes = notesByTime[time].orEmpty()
                eventNet -= notes.sumOf { it.getLong("amount") }
                val refunded = notes.sumOf { it.getLong("refund") }
                require(eventNet >= 0 && refunded == (eventPaid + received - eventNet).coerceAtLeast(0)) { "رد المبالغ لا يطابق حالة الفاتورة وقت التسوية" }
                eventPaid += received - refunded
                require(eventPaid in 0..eventNet) { "دفع زائد أو رصيد غير صحيح في تسلسل العمليات" }
            }
            require(if(i.state=="ISSUED") !allReturned else allReturned && i.net==0L && i.remaining==0L) { "حالة الفاتورة لا تطابق المرتجعات" }
            val ms=movementsByInvoice[i.id].orEmpty()
            require(ms.all { it.getLong("created") >= i.created }) { "حركة مالية تسبق إصدار الفاتورة" }
            require(ms.count { it.getString("kind")=="SALE" }==1 && ms.single { it.getString("kind")=="SALE" }.let { it.getLong("amount")==i.total && it.getLong("created")==i.created }) { "حركة بيع مفقودة أو مكررة أو بتاريخ غير مطابق" }
            fun financial(kind:String)=ms.filter { it.getString("kind")==kind }.map { it.getLong("amount") }.sorted()
            require(financial("RETURN")==cs.map { -it.getLong("amount") }.sorted() && financial("REFUND")==cs.filter { it.getLong("refund")>0 }.map { -it.getLong("refund") }.sorted()) { "حركات رد أو مرتجع لا تطابق التسويات" }
            listOf("INVOICE_PAYMENT","DEBT_COLLECTION").forEach { kind ->
                val expected=ps.filter { it.getString("origin")==kind }.map { Triple(it.getLong("amount"), it.getString("method"), it.getLong("created")) }.groupingBy { it }.eachCount()
                val actual=ms.filter { it.getString("kind")==kind }.map { Triple(it.getLong("amount"), it.getString("method"), it.getLong("created")) }.groupingBy { it }.eachCount()
                require(expected==actual) { "حركات القبض لا تطابق السندات" }
            }
        }
        rows("SELECT * FROM invoice_items").forEach { require(it.getLong("tracked") in 0..1) }
        listOf("invoice" to "INV", "receipt" to "REC").forEach { (sequence,prefix) ->
            val used=if(sequence=="invoice") invoices.map { it.number } else payments.map { it.getString("receipt") }
            val max=used.maxOfOrNull { require(it.matches(Regex("$prefix-[0-9]{6,}"))) { "رقم فاتورة أو سند غير صالح" }; it.substringAfter('-').toLong() } ?: 0L
            val current=rows("SELECT * FROM sequences").firstOrNull { it.getString("name")==sequence }?.getLong("value") ?: 0L
            require(current>=max && current<Long.MAX_VALUE) { "تسلسل لا يغطي الأرقام المستخدمة" }
        }
        rows("SELECT * FROM legacy_archive").forEach { JSONArray(it.getString("json")) }
    }
}

internal fun classifyLegacy(t: JSONObject): String {
        fun present(key: String) = t.optString(key).trim().let { it.isNotEmpty() && it != "null" }
        val supplier = present("supplier_id") || present("supplier_name")
        val customer = present("customer_id") || present("customer_name")
        return when {
            supplier && customer -> "AMBIGUOUS"
            supplier -> "SUPPLIER"
            t.optString("invoice_number").startsWith("PUR-", true) -> "AMBIGUOUS"
            customer -> "CUSTOMER"
            t.optString("type") in listOf("3", "DEBT") -> "AMBIGUOUS"
            else -> "OTHER"
        }
}

