@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.naveenapps.expensemanager.commerce

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

@Composable internal fun ProductsPage(s: Snapshot, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(50) }
    Button(onClick = onAdd) { Text("إضافة منتج") }
    Field("بحث بالاسم أو الرمز أو الباركود", search, { search = it })
    val products = s.products.filter { it.name.contains(search, true) || it.sku.contains(search, true) || it.barcode.contains(search, true) }
    products.take(limit).forEach { p ->
        Panel {
            Column(Modifier.fillMaxWidth().clickable { onOpen(p.id) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${Money.format(p.price)} ${s.currency} / ${p.unit}")
                Text("${p.sku} • ${if (p.active) "نشط" else "متوقف"} • ${p.category}")
                if (p.trackStock) Text("المخزون: ${p.stock} ${p.unit}", color = if (Money.decimal(p.stock) <= Money.decimal(p.minimum)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text("عرض المبيعات والتفاصيل ←", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (products.size > limit) TextButton(onClick = { limit += 50 }) { Text("عرض المزيد") }
}
@Composable internal fun ProductDetail(s: Snapshot, p: Product, onEdit: () -> Unit, onInvoice: (String) -> Unit, onBarcode: (String) -> Unit) {
    val lines = s.items.filter { it.productId == p.id }
    val stats = CommerceAnalytics.product(s, p.id)
    val quantity = stats.quantity.entries.joinToString("، ") { "${it.value.toPlainString()} ${it.key}" }.ifBlank { "0 ${p.unit}" }
    val sales = stats.grossSales
    val debt = stats.receivable
    Panel(p.name) {
        Text("${p.sku} • باركود ${p.barcode.ifBlank { "غير محدد" }}")
        Text("${p.category} • ${p.type} • ${p.description}")
        Text("سعر البيع ${Money.format(p.price)} ${s.currency} / ${p.unit}")
        Text("التكلفة ${p.cost?.let(Money::format) ?: "غير متاحة"}")
        if (p.trackStock) Text("المخزون ${p.stock} • الحد الأدنى ${p.minimum}")
        Text(p.notes)
        Button(onClick = onEdit) { Text("تعديل المنتج") }
    }
    Metrics(s, "صافي المبيعات" to sales, "الذمم الموزعة" to debt)
    Metric("الكمية المباعة الصافية", quantity, "الكميات مفصولة حسب وحدة الفاتورة")
    Text("توزع خصومات وضريبة وذمم الفواتير متعددة البنود نسبيًا، مع توزيع أجزاء الدينار المتبقية بدقة.", style = MaterialTheme.typography.labelSmall)
    Panel("باركود المنتج") {
        Text("يطبع Code 128 للرموز اللاتينية والرقمية وQR للرموز العربية. عند عدم وجود باركود، يحفظ SKU كرمز للمسح. يمكن مسح Code 128 أو QR بالكاميرا.")
        DocumentActions(onBarcode)
    }
    Text("الفواتير المرتبطة", style = MaterialTheme.typography.titleLarge)
    s.invoices.filter { i -> lines.any { it.invoiceId == i.id } }.forEach { InvoiceRow(it) { onInvoice(it.id) } }
}
@Composable internal fun CustomersPage(s: Snapshot, debtsOnly: Boolean, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("الكل") }
    var limit by rememberSaveable { mutableIntStateOf(50) }
    if (!debtsOnly) Button(onClick = onAdd) { Text("إضافة عميل") }
    if (debtsOnly) Metrics(s, "إجمالي الذمم" to s.debt)
    Field("بحث بالاسم أو الهاتف", search, { search = it })
    Choice("عرض", filter, listOf("الكل", "مدينون", "بدون مديونية", "متأخرون"), { filter = it })
    val filtered = s.customers.filter { c ->
        val debt = s.balance(c.id)
        (c.name.contains(search, true) || c.phone.contains(search)) && (!debtsOnly || debt > 0) && when (filter) {
            "مدينون" -> debt > 0; "بدون مديونية" -> debt == 0L
            "متأخرون" -> s.invoices.any { it.customerId == c.id && it.remaining > 0 && it.due != null && it.due < System.currentTimeMillis() }
            else -> true
        }
    }
    if (filtered.isEmpty()) Empty("لا توجد نتائج")
    filtered.take(limit).forEach { c -> Panel { Column(Modifier.fillMaxWidth().clickable { onOpen(c.id) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${c.phone} • ${c.type}")
        Text("المستحق: ${Money.format(s.balance(c.id))} ${s.currency}", color = MaterialTheme.colorScheme.primary)
        Text("آخر عملية: ${s.movements.filter { m -> s.invoices.any { it.id == m.invoiceId && it.customerId == c.id } }.maxOfOrNull { it.created }?.let(::dateText) ?: "لا توجد عمليات"}", style = MaterialTheme.typography.bodySmall)
    } } }
    if (filtered.size > limit) TextButton(onClick = { limit += 50 }) { Text("عرض المزيد") }
}
@Composable internal fun CustomerDetail(s: Snapshot, c: Customer, onEdit: () -> Unit, onCollect: () -> Unit, onInvoice: (String) -> Unit, onDocument: (String) -> Unit) {
    val invoices = s.invoices.filter { it.customerId == c.id }
    Panel(c.name) { Text("معرّف العميل: ${c.id}", style = MaterialTheme.typography.labelSmall); Text("${c.phone} • ${c.address}"); Text("${c.type} • ${c.notes}"); Button(onClick = onEdit) { Text("تعديل بيانات العميل") } }
    Metrics(s, "صافي مشترياته" to invoices.sumOf { it.net }, "صافي المدفوع" to invoices.sumOf { it.paid }, "الرصيد المستحق" to s.balance(c.id))
    if (s.balance(c.id) > 0) Button(onClick = onCollect) { Text("تحصيل دفعة • توزع على الأقدم أولًا") }
    Panel("كشف الحساب") { DocumentActions(onDocument) }
    Text("الفواتير والمشتريات", style = MaterialTheme.typography.titleLarge)
    invoices.forEach { InvoiceRow(it) { onInvoice(it.id) } }
    Panel("سجل المدفوعات") { s.payments.filter { p -> invoices.any { it.id == p.invoiceId } }.forEach { Text("${it.receipt} • ${Money.format(it.amount)} • ${it.method} • ${dateText(it.created)}") } }
}
@Composable internal fun InvoicesPage(s: Snapshot, onOpen: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("الكل") }
    var method by rememberSaveable { mutableStateOf("الكل") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(50) }
    Field("بحث برقم الفاتورة أو العميل أو الصنف", search, { search = it })
    Choice("الحالة", status, listOf("الكل", "مدفوعة", "جزئية", "غير مدفوعة", "ملغاة", "مرتجعة"), { status = it })
    Choice("الدفع", method, listOf("الكل") + s.invoices.map { it.method }.distinct(), { method = it })
    Field("من تاريخ YYYY-MM-DD", from, { from = it })
    Field("إلى تاريخ YYYY-MM-DD", to, { to = it })
    val dateResult = runCatching { CommerceDates.range(from,to) }
    if (dateResult.isFailure) Text("تحقق من صيغة التاريخ", color = MaterialTheme.colorScheme.error)
    val bounds = dateResult.getOrDefault(1L to 0L)
    val filtered = s.invoices.filter { i ->
        (i.number.contains(search, true) || i.customerName.contains(search, true) || s.items.any { it.invoiceId == i.id && it.name.contains(search, true) }) &&
            (status == "الكل" || status == i.status) && (method == "الكل" || method == i.method) && i.created in bounds.first..bounds.second
    }
    Text("${filtered.size} فاتورة")
    filtered.take(limit).forEach { InvoiceRow(it) { onOpen(it.id) } }
    if (filtered.size > limit) TextButton(onClick = { limit += 50 }) { Text("عرض المزيد") }
    if (filtered.isEmpty()) Empty("لا توجد فواتير تطابق البحث")
}
@Composable internal fun InvoiceDetail(s: Snapshot, i: Invoice, onDocument: (String) -> Unit, onCollect: () -> Unit, onReturn: () -> Unit, onCancel: () -> Unit, onReceipt: (Payment) -> Unit, onEdit: (() -> Unit)? = null) {
    Panel(i.number) { Text("${i.customerName} • ${dateText(i.created)}"); Text("${i.status} • ${i.method}"); i.due?.let { Text("الاستحقاق: ${dateText(it)}") }; DocumentActions(onDocument) }
    Panel("بنود الفاتورة") {
        s.items.filter { it.invoiceId == i.id }.forEach { l -> Text(l.name, fontWeight = FontWeight.Bold); Text("${l.quantity} ${l.unit} × ${Money.format(l.price)} = ${Money.format(l.total)} ${i.currency}"); if (Money.decimal(l.returned) > BigDecimal.ZERO) Text("مرتجع: ${l.returned} ${l.unit}"); HorizontalDivider() }
    }
    Panel("الحساب") { Text("المجموع ${Money.format(i.subtotal)} • الخصم ${Money.format(i.discount)} • الضريبة ${Money.format(i.tax)}"); Text("الإجمالي ${Money.format(i.total)} • المرتجع ${Money.format(i.credit)}"); Text("صافي الفاتورة ${Money.format(i.net)} ${i.currency}", fontWeight = FontWeight.Bold); Text("المدفوع ${Money.format(i.paid)} • المتبقي ${Money.format(i.remaining)}", fontWeight = FontWeight.Bold); if (i.notes.isNotBlank()) Text(i.notes) }
    if (onEdit != null && i.state == "ISSUED" && i.credit == 0L) OutlinedButton(onClick = onEdit) { Text("تعديل الفاتورة مع حفظ الأصل") }
    if (i.remaining > 0 && i.customerId != null) Button(onClick = onCollect) { Text("تحصيل من هذه الفاتورة") }
    if (i.state != "CANCELLED" && s.items.any { it.invoiceId == i.id && Money.decimal(it.returned) < Money.decimal(it.quantity) }) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onReturn) { Text("مرتجع جزئي أو كامل") }; OutlinedButton(onClick = onCancel) { Text("إلغاء الفاتورة") } }
    Text("التعديل يصدر بديلًا ويحفظ الأصل وسجل التدقيق. الفواتير ذات المرتجعات تُعالج بتسوية ولا تعدّل مباشرة.", style = MaterialTheme.typography.labelSmall)
    if (i.credit > 0) Panel("سجل التسويات") { s.movements.filter { it.invoiceId == i.id && it.kind == "RETURN" }.forEach { Text("${dateText(it.created)} • ${Money.format(-it.amount)} ${i.currency}\n${it.notes}") } }
    Panel("دفعات وسندات القبض") { s.payments.filter { it.invoiceId == i.id }.forEach { p -> Text("${p.receipt} • ${Money.format(p.amount)} ${i.currency}"); Text("${dateText(p.created)} • ${p.method} • ${movementLabel(p.origin)}"); TextButton(onClick = { onReceipt(p) }) { Text("مشاركة سند القبض") } }; s.movements.filter { it.invoiceId == i.id && it.kind == "REFUND" }.forEach { Text("رد نقدي ${Money.format(-it.amount)} • ${dateText(it.created)}") } }
}
@Composable internal fun IncomePage(s: Snapshot, onAdd: () -> Unit) {
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("الكل") }
    var limit by rememberSaveable { mutableIntStateOf(150) }
    Button(onClick = onAdd) { Text("إضافة دخل آخر") }
    Field("من تاريخ YYYY-MM-DD", from, { from = it })
    Field("إلى تاريخ YYYY-MM-DD", to, { to = it })
    Choice("المصدر", source, listOf("الكل") + s.movements.map { it.source }.distinct(), { source = it })
    val bounds = runCatching { CommerceDates.range(from,to) }
    if (bounds.isFailure) Text("تحقق من صيغة التاريخ", color = MaterialTheme.colorScheme.error)
    val range = bounds.getOrDefault(1L to 0L)
    val movements = s.movements.filter { it.created in range.first..range.second && (source == "الكل" || it.source == source) }
    fun amount(kind: String) = movements.filter { it.kind == kind }.sumOf { it.amount }
    Metrics(s, "المبيعات الصافية" to (amount("SALE") + amount("RETURN")), "قبض عند الإصدار" to amount("INVOICE_PAYMENT"), "تحصيل ذمم" to amount("DEBT_COLLECTION"), "دخل آخر" to amount("OTHER_INCOME"), "رد مبالغ" to -amount("REFUND"), "النقد الداخل الصافي" to movements.filter { it.kind !in listOf("SALE", "RETURN") }.sumOf { it.amount })
    Text("المبيعات لا تضاف إلى النقد؛ يحتسب النقد من الدفعات والتحصيل والدخل الآخر والردود فقط.")
    movements.take(limit).forEach { Panel { Text("${movementLabel(it.kind)} • ${it.source}", fontWeight = FontWeight.Bold); Text("${Money.format(it.amount)} ${s.currency} • ${it.method}"); Text(dateText(it.created), style = MaterialTheme.typography.bodySmall); if (it.notes.isNotBlank()) Text(it.notes) } }
    if (movements.size > limit) TextButton(onClick = { limit += 150 }) { Text("عرض المزيد") }
}
@Composable internal fun ReportsPage(s: Snapshot, onExport: (Long, Long, String, String, String) -> Unit) {
    var period by rememberSaveable { mutableStateOf("شهري") }
    var kind by rememberSaveable { mutableStateOf("المبيعات") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now(ZoneId.of("Asia/Amman"))
    val fromDate = when (period) { "يومي" -> today; "أسبوعي" -> today.minusDays(6); "سنوي" -> today.withDayOfYear(1); else -> today.withDayOfMonth(1) }
    val range = runCatching { CommerceDates.range(if (period == "مخصص") from else fromDate.toString(), if (period == "مخصص") to else today.toString(), defaultEnd = System.currentTimeMillis()) }
    Choice("الفترة", period, listOf("يومي", "أسبوعي", "شهري", "سنوي", "مخصص"), { period = it })
    Choice("التقرير", kind, listOf("المبيعات", "الأصناف", "الدخل والمقبوضات", "الذمم", "المدفوعات", "العملاء", "الفواتير", "الكميات", "الربح التقديري"), { kind = it })
    if (period == "مخصص") { Field("من تاريخ YYYY-MM-DD", from, { from = it }); Field("إلى تاريخ YYYY-MM-DD", to, { to = it }) }
    if (range.isFailure) { Text("تحقق من التاريخ", color = MaterialTheme.colorScheme.error); return }
    val (start, end) = range.getOrThrow()
    if (start > end) { Text("نهاية الفترة قبل بدايتها", color = MaterialTheme.colorScheme.error); return }
    Metrics(s, "المبيعات في الفترة" to s.sales(start, end), "المقبوضات في الفترة" to s.cash(start, end), "الذمم الحالية" to s.debt)
    Panel("تصدير التقرير") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Excel", "CSV", "PDF").forEach { format -> OutlinedButton(onClick = { onExport(start, end, kind, format, "تنزيل") }) { Text(format) } }; OutlinedButton(onClick = { onExport(start, end, kind, "PDF", "طباعة") }) { Text("طباعة") } }
        Text("تقارير الفواتير حسب تاريخ الإصدار مع رصيدها الحالي. إجماليات المبيعات والتحصيلات حسب تاريخ الحركة. الذمم رصيد حالي، والربح تقديري عندما تكون تكاليف البنود كاملة.", style = MaterialTheme.typography.labelSmall)
    }
    val rows = CommerceDocuments.reportRows(s, start, end, kind)
    rows.take(101).forEachIndexed { idx, cells -> Panel { Text(cells.joinToString(" | "), fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Normal) } }
    if (rows.size > 101) Text("يعرض أول 100 سجل؛ التصدير يشمل جميع النتائج")
}
@Composable internal fun SettingsPage(s: Snapshot, busy: Boolean, onSave: (Map<String, String>) -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onMigrate: () -> Unit, onArchive: () -> Unit, safetyFiles: List<String> = emptyList(), onSafetyRestore: (String) -> Unit = {}, onSafetyDownload: (String) -> Unit = {}, onRepair: () -> Unit = {}, review: String = "[]") {
    var business by rememberSaveable(s.settings["business"]) { mutableStateOf(s.settings["business"] ?: "مؤسستي") }
    var phone by rememberSaveable(s.settings["phone"]) { mutableStateOf(s.settings["phone"].orEmpty()) }
    var address by rememberSaveable(s.settings["address"]) { mutableStateOf(s.settings["address"].orEmpty()) }
    var currency by rememberSaveable(s.currency) { mutableStateOf(s.currency) }
    var tax by rememberSaveable(s.settings["tax"]) { mutableStateOf(s.settings["tax"] ?: "0") }
    var units by rememberSaveable(s.settings["units"]) { mutableStateOf(s.settings["units"] ?: "طن،متر مكعب،نقلة،كيس،قطعة") }
    var paper by rememberSaveable(s.settings["paper"]) { mutableStateOf(s.settings["paper"] ?: "A4") }
    var theme by rememberSaveable(s.settings["theme"]) { mutableStateOf(s.settings["theme"] ?: "light") }
    var footer by rememberSaveable(s.settings["footer"]) { mutableStateOf(s.settings["footer"] ?: "شكرًا لتعاملكم معنا") }
    var logo by rememberSaveable(s.settings["logo"]) { mutableStateOf(s.settings["logo"].orEmpty()) }
    var error by remember { mutableStateOf("") }
    var safetySelection by rememberSaveable(safetyFiles.firstOrNull()) { mutableStateOf(safetyFiles.firstOrNull().orEmpty()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadingLogo by remember { mutableStateOf(false) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loadingLogo = true
            try {
                logo = withContext(Dispatchers.IO) {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readLimited(2_000_000) } ?: error("تعذر قراءة الصورة")
                    CommerceLogo.encode(data)
                }
                error = ""
            } catch (e: Exception) { error = e.message.orEmpty() }
            finally { loadingLogo = false }
        }
    }
    Panel("المؤسسة والفاتورة") {
        Field("اسم المؤسسة", business, { business = it }); Field("الهاتف", phone, { phone = it }); Field("العنوان", address, { address = it })
        Field("العملة", currency, { currency = it.uppercase() }); Field("الضريبة الافتراضية %", tax, { tax = it }, numeric = true)
        Field("وحدات القياس مفصولة بفاصلة", units, { units = it }); Field("تذييل الفاتورة", footer, { footer = it })
        Choice("الطباعة", paper, listOf("A4", "58mm", "80mm"), { paper = it })
        Choice("المظهر", if (theme == "dark") "داكن" else "فاتح", listOf("فاتح", "داكن"), { theme = if (it == "داكن") "dark" else "light" })
        OutlinedButton(enabled = !busy && !loadingLogo, onClick = { logoPicker.launch("image/*") }) { Text(if (logo.isBlank()) "رفع الشعار" else "استبدال الشعار ✓") }
        if (logo.isNotBlank()) TextButton(onClick = { logo = "" }) { Text("إزالة الشعار") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy && !loadingLogo, onClick = { onSave(s.settings + mapOf("business" to business, "phone" to phone, "address" to address, "currency" to currency, "tax" to tax, "units" to units, "paper" to paper, "theme" to theme, "footer" to footer, "logo" to logo)) }) { Text("حفظ الإعدادات") }
    }
    Panel("حماية البيانات ونقلها") { Button(onClick = onBackup, enabled = !busy) { Text("تنزيل نسخة احتياطية") }; OutlinedButton(onClick = onRestore, enabled = !busy) { Text("استعادة نسخة") }; Text("تشمل النسخة الدفتر الجديد وقاعدة النظام القديم وإعداداته والشعار والصور والمرفقات والأرشيف. النسخ المحلية السابقة تنزّل منفصلة. احفظها في مكان آمن؛ تحتوي بيانات مالية.") }
    if (safetyFiles.isNotEmpty()) Panel("نسخ الأمان المحلية") {
        Text("تحفظ تلقائيًا قبل الاستعادة والترحيل. يمكنك استرجاع الدفتر السابق أو تنزيله إلى مكان آخر.")
        Choice("نسخة الأمان", safetySelection, safetyFiles, { safetySelection = it })
        OutlinedButton(enabled = !busy && !loadingLogo, onClick = { onSafetyDownload(safetySelection) }) { Text("تنزيل نسخة الأمان") }
        OutlinedButton(enabled = !busy && !loadingLogo, onClick = { onSafetyRestore(safetySelection) }) { Text("استعادة نسخة الأمان") }
    }
    Panel("الانتقال من النظام القديم") { Text("قاعدة النظام القديم محفوظة. الترحيل ينشئ أرشيفًا ويستورد المبيعات والذمم والدفعات دون حذف الأصل. تفاصيل الأصناف غير المتاحة لا تُفترض؛ تحفظ البنود القديمة كوحدة سجل مستقلة."); Button(onClick = onMigrate, enabled = !busy && s.settings["legacy_imported"] != "true") { Text(if (s.settings["legacy_imported"] == "true") "تم الترحيل" else "نسخ وترحيل البيانات القديمة") }; OutlinedButton(onClick = onArchive, enabled = !busy) { Text("تنزيل الأرشيف القديم") } }
    Panel("مراجعة البيانات القديمة") {
        Text("التزامات الموردين والسجلات الغامضة تحفظ هنا والأرشيف، ولا تدخل المبيعات أو ذمم العملاء. تنزيل الأرشيف يشمل السجل الأصلي ودفعات الموردين.")
        OutlinedButton(enabled = !busy, onClick = onRepair) { Text("مراجعة وتصحيح الترحيل السابق") }
        runCatching { org.json.JSONArray(review).objects() }.getOrDefault(emptyList()).forEach { row ->
            Text("${row.optString("id")} • ${row.optString("classification")} • ${row.optString("status")}")
            if (row.has("supplier_remaining")) Text("التزام أصلي ${row.getString("commitment")} • سداد مورد ${row.getString("supplier_paid")} • المتبقي للمورد ${row.getString("supplier_remaining")} ${s.currency} (قراءة أرشيفية للمراجعة)")
            if (row.has("warning")) Text(row.getString("warning"), color = MaterialTheme.colorScheme.error)
            Text(row.optJSONObject("original")?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
    }
    Panel("الجهاز والحساب") { Text("دفتر محلي لمستخدم واحد، يعمل دون إنترنت. لا توجد مزامنة بين أجهزة أو حسابات متعددة في هذه النسخة. النقل يكون عبر النسخ الاحتياطي. يستخدم القفل الحيوي السابق إذا كان مفعّلًا."); Text("الكاميرا تحتاج إذنًا. قارئ USB/Bluetooth يجب أن يعمل كلوحة مفاتيح مع Enter. الطباعة تتطلب خدمة طباعة Android متوافقة؛ قياس الورق يعتمد على الطابعة.") }
    Panel("سجل التدقيق • أحدث 50 حدثًا") { s.audit.take(50).forEach { Text("${dateText(it.created)} • ${it.action}\n${it.details}", style = MaterialTheme.typography.bodySmall); HorizontalDivider() } }
}
