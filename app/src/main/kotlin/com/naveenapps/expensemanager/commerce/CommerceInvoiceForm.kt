@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.naveenapps.expensemanager.commerce

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable internal fun NewInvoicePage(
    s: Snapshot, busy: Boolean, pendingBarcode: String, onBarcodeUsed: () -> Unit,
    onAddProduct: (String) -> Unit, onCustomer: () -> Unit,
    onSave: (String, String?, List<DraftLine>, Long, String, Long, String, String, Long?) -> Unit,
    initial: Invoice? = null,
    pendingCustomer: String = "", onCustomerUsed: () -> Unit = {}, onRevisionRefundMethod: (String) -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var token by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var cartJson by rememberSaveable { mutableStateOf(if (initial == null) "[]" else encodeCart(s.items.filter { it.invoiceId == initial.id }.map { DraftLine(it.productId, it.quantity, it.price, it.unit) })) }
    val cart = decodeCart(cartJson)
    fun cartUpdate(lines: List<DraftLine>) { cartJson = encodeCart(lines) }
    var customerId by rememberSaveable { mutableStateOf(initial?.customerId.orEmpty()) }
    var customerSearch by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var productId by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var price by rememberSaveable { mutableStateOf("") }
    var discount by rememberSaveable { mutableStateOf(initial?.discount?.let(Money::format) ?: "0") }
    var tax by rememberSaveable { mutableStateOf(initial?.taxRate ?: s.settings["tax"] ?: "0") }
    var paid by rememberSaveable { mutableStateOf(initial?.paid?.let(Money::format).orEmpty()) }
    var method by rememberSaveable { mutableStateOf(initial?.method ?: "نقدي") }
    var refundMethod by rememberSaveable { mutableStateOf(initial?.method ?: "نقدي") }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var due by rememberSaveable { mutableStateOf(initial?.due?.let { dateText(it).take(10) }.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var unknown by remember { mutableStateOf<String?>(null) }
    var lastCode by remember { mutableStateOf("") }
    var lastTime by remember { mutableLongStateOf(0L) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var editQty by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf("") }
    fun append(p: Product, qty: String = "1", unitPrice: Long = p.price) {
        Money.line(qty, unitPrice)
        val existing = cart.indexOfFirst { it.productId == p.id && it.price == unitPrice && it.unit == p.unit }
        if (existing < 0) cartUpdate(cart + DraftLine(p.id, qty, unitPrice, p.unit))
        else cartUpdate(cart.mapIndexed { idx, line -> if (idx == existing) line.copy(quantity = (Money.decimal(line.quantity) + Money.decimal(qty)).toPlainString()) else line })
        error = ""
    }
    fun scan(value: String) {
        val content = value.trim()
        if (content.isBlank()) return
        val time = android.os.SystemClock.elapsedRealtime()
        if (lastCode == content && time - lastTime < 1200L) return
        lastCode = content; lastTime = time
        val p = s.findProductByCode(content)
        if (p == null) { unknown = content; error = "الباركود غير مسجل: $content" }
        else try { append(p); code = "" } catch (e: Exception) { error = e.message.orEmpty() }
    }
    LaunchedEffect(pendingCustomer) { if (pendingCustomer.isNotBlank()) { customerId = pendingCustomer; onCustomerUsed() } }
    LaunchedEffect(pendingBarcode) { if (pendingBarcode.isNotBlank()) { if (s.products.any { it.barcode == pendingBarcode }) scan(pendingBarcode); onBarcodeUsed() } }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) scan(result.contents)
        else if (result.originalIntent?.getBooleanExtra("MISSING_CAMERA_PERMISSION", false) == true) error = "لم يمنح إذن الكاميرا؛ استخدم الإدخال اليدوي أو القارئ الخارجي"
    }
    val totalResult = runCatching { Money.totals(cart.map { Money.line(it.quantity, it.price) }, Money.parse(discount), tax) }
    val totals = totalResult.getOrNull()
    if (initial != null) Panel("تصحيح ${initial.number}") { Text("يحفظ الأصل ملغى ويصدر بديلًا ضمن معاملة واحدة. فرق المدفوع فقط يؤثر على صافي النقد؛ أي انخفاض يستلزم رد الفرق للعميل. المدفوع السابق ${Money.format(initial.paid)} ${initial.currency}.") }
    Panel("العميل") {
        Text("رقم الفاتورة يصدر تلقائيًا عند الحفظ • التاريخ والوقت تلقائيان", style = MaterialTheme.typography.labelSmall)
        Text(if (customerId.isBlank()) "عميل نقدي" else s.customers.firstOrNull { it.id == customerId }?.name ?: "عميل غير موجود", fontWeight = FontWeight.Bold)
        Field("بحث عن عميل", customerSearch, { customerSearch = it })
        Choice("اختيار العميل", s.customers.firstOrNull { it.id == customerId }?.let { "${it.name} · ${it.phone} · ${it.id.takeLast(6)}" } ?: "عميل نقدي", listOf("عميل نقدي") + s.customers.filter { it.name.contains(customerSearch, true) || it.phone.contains(customerSearch) }.map { "${it.name} · ${it.phone} · ${it.id.takeLast(6)}" }, { value -> customerId = if (value == "عميل نقدي") "" else s.customers.first { "${it.name} · ${it.phone} · ${it.id.takeLast(6)}" == value }.id })
        OutlinedButton(onClick = onCustomer) { Text("إضافة عميل مباشرة") }
    }
    Panel("المنتجات والباركود") {
        Field("باركود أو SKU • القارئ الخارجي ثم Enter", code, { code = it }, onDone = { scan(code) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scan(code) }) { Text("إضافة بالرمز") }
            OutlinedButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES).setPrompt("وجّه الكاميرا إلى الباركود").setBeepEnabled(true).setOrientationLocked(false)) }) { Text("فتح الكاميرا") }
        }
        Text("المكتبة تدعم EAN/UPC وCode 128/39 وITF وQR وData Matrix وPDF417 وAztec. نجاح المسح يعتمد على الكاميرا والإضاءة وحجم الرمز؛ يمكن إدخاله يدويًا.", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.products.filter { it.active }.take(6).forEach { p ->
                OutlinedButton(onClick = { try { append(p) } catch (e: Exception) { error = e.message.orEmpty() } }) { Text("${p.name} +1") }
            }
        }
        Field("بحث عن صنف", search, { search = it })
        val products = s.products.filter { it.active && (it.name.contains(search, true) || it.sku.contains(search, true)) }
        Choice("الصنف", s.products.firstOrNull { it.id == productId }?.let { "${it.name} · ${it.sku}" } ?: "اختر صنفًا", products.map { "${it.name} · ${it.sku}" }, { label -> val p = products.first { "${it.name} · ${it.sku}" == label }; productId = p.id; price = Money.format(p.price) })
        val product = s.products.firstOrNull { it.id == productId }
        if (product != null) {
            Field("الكمية • ${product.unit}", quantity, { quantity = it }, numeric = true)
            Field("سعر الوحدة ${s.currency}", price, { price = it }, numeric = true)
            Text("تعديل السعر متاح لمالك الدفتر الوحيد؛ تحفظ نسخة السعر داخل الفاتورة.", style = MaterialTheme.typography.labelSmall)
            Button(onClick = { try { append(product, quantity, Money.parse(price)); quantity = "1" } catch (e: Exception) { error = e.message.orEmpty() } }) { Text("إضافة إلى الفاتورة") }
        }
    }
    if (cart.isEmpty()) Empty("أضف الرمل أو أي صنف لبدء الفاتورة")
    cart.forEachIndexed { idx, line ->
        Panel(s.products.firstOrNull { it.id == line.productId }?.name ?: "صنف غير موجود") {
            Text("${line.quantity} ${line.unit} × ${Money.format(line.price)} = ${runCatching { Money.format(Money.line(line.quantity, line.price)) }.getOrDefault("—")} ${s.currency}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { editIndex = idx; editQty = line.quantity; editPrice = Money.format(line.price) }) { Text("تعديل") }; TextButton(onClick = { cartUpdate(cart.filterIndexed { n, _ -> n != idx }) }) { Text("إزالة") } }
        }
    }
    Panel("الإجمالي والدفع") {
        Field("خصم ثابت ${s.currency}", discount, { discount = it }, numeric = true)
        Field("الضريبة %", tax, { tax = it }, numeric = true)
        if (totals != null) {
            Text("المجموع ${Money.format(totals.subtotal)} • الضريبة ${Money.format(totals.tax)}")
            Text("الإجمالي: ${Money.format(totals.total)} ${s.currency}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        } else Text(totalResult.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
        Field("المدفوع • فارغ = دفع كامل", paid, { paid = it }, numeric = true)
        TextButton(onClick = { paid = "0" }) { Text("بيع بالدين دون دفعة") }
        val paidValue = runCatching { if (paid.isBlank()) totals?.total ?: 0 else Money.parse(paid) }.getOrNull()
        if (totals != null && paidValue != null) Text("المتبقي: ${Money.format(totals.total - paidValue)} ${s.currency}")
        Choice("طريقة الدفع", method.takeIf { it in paymentMethods } ?: "طريقة مخصصة", paymentMethods + "طريقة مخصصة", { method = if (it == "طريقة مخصصة") "" else it })
        if (method !in paymentMethods) Field("اسم طريقة الدفع", method, { method = it })
        if (initial != null && paidValue != null && paidValue < initial.paid) {
            Choice("طريقة رد فرق التصحيح", refundMethod, paymentMethods + "مخصص", { refundMethod = it })
            if (refundMethod !in paymentMethods) Field("اسم طريقة رد الفرق", refundMethod, { refundMethod = it })
        }
        Field("استحقاق اختياري YYYY-MM-DD", due, { due = it })
        Field("ملاحظات الفاتورة", notes, { notes = it }, lines = 2)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy && cart.isNotEmpty() && totals != null, modifier = Modifier.fillMaxWidth(), onClick = {
            try { val t = totalResult.getOrThrow(); val amount = if (paid.isBlank()) t.total else Money.parse(paid); require(amount <= t.total) { "المدفوع يتجاوز الإجمالي" }; require(customerId.isNotBlank() || amount == t.total) { "اختر عميلًا للبيع بالدين" }; require(refundMethod.isNotBlank()) { "طريقة رد فرق التصحيح مطلوبة" }; focus.clearFocus(); keyboard?.hide(); onRevisionRefundMethod(refundMethod); onSave(token, customerId.ifBlank { null }, cart, Money.parse(discount), tax, amount, method, notes, parseDate(due)) }
            catch (e: Exception) { error = e.message ?: "تحقق من البيانات" }
        }) { Text(if (busy) "جارٍ الحفظ…" else "حفظ وإصدار الفاتورة") }
    }
    if (unknown != null) AlertDialog(onDismissRequest = { unknown = null }, title = { Text("باركود غير مسجل") }, text = { Text(unknown!!) }, confirmButton = { TextButton(onClick = { onAddProduct(unknown!!); unknown = null }) { Text("إضافة المنتج") } }, dismissButton = { TextButton(onClick = { unknown = null }) { Text("إغلاق") } })
    if (editIndex in cart.indices) FormDialog("تعديل البند", busy, { editIndex = -1 }, {
        try { val q = editQty; val p = Money.parse(editPrice); Money.line(q, p); cartUpdate(cart.mapIndexed { n, line -> if (n == editIndex) line.copy(quantity = q, price = p) else line }); editIndex = -1 } catch (e: Exception) { error = e.message.orEmpty() }
    }) { Field("الكمية", editQty, { editQty = it }, numeric = true); Field("السعر", editPrice, { editPrice = it }, numeric = true); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) }
}
private fun encodeCart(lines: List<DraftLine>): String = JSONArray(lines.map { JSONObject().put("product", it.productId).put("qty", it.quantity).put("price", it.price).put("unit", it.unit) }).toString()
private fun decodeCart(json: String): List<DraftLine> = JSONArray(json).let { array -> (0 until array.length()).map { array.getJSONObject(it).let { DraftLine(it.getString("product"), it.getString("qty"), it.getLong("price"), it.getString("unit")) } } }
