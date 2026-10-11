package com.naveenapps.expensemanager.commerce

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable internal fun FormDialog(title: String, busy: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { if (!busy) onDismiss() }
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
            .clickable(interactionSource = interaction, indication = null) { if (!busy) onDismiss() }
            .padding(WindowInsets.safeDrawing.asPaddingValues()).imePadding().padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.widthIn(max = 540.dp).fillMaxWidth().height(maxHeight * .88f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { paneTitle = title }, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy, onClick = onSave) { Text("حفظ") }
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("إغلاق") }
                }
            }
        }
    }
}
@Composable internal fun ConfirmDialog(title: String, text: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = { Text(text) }, confirmButton = { Button(enabled = !busy, onClick = onConfirm) { Text("تأكيد") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إلغاء") } })
}
@Composable internal fun ProductDialog(product: Product?, busy: Boolean, onDismiss: () -> Unit, onSave: (Product) -> Unit, units: List<String> = listOf("طن", "متر مكعب", "نقلة", "كيس", "قطعة")) {
    var name by rememberSaveable { mutableStateOf(product?.name.orEmpty()) }
    var sku by rememberSaveable { mutableStateOf(product?.sku.orEmpty()) }
    var barcode by rememberSaveable { mutableStateOf(product?.barcode.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(product?.category ?: "رمل") }
    var type by rememberSaveable { mutableStateOf(product?.type ?: "سلعة") }
    var description by rememberSaveable { mutableStateOf(product?.description.orEmpty()) }
    var price by rememberSaveable { mutableStateOf(Money.format(product?.price ?: 0)) }
    var cost by rememberSaveable { mutableStateOf(product?.cost?.let(Money::format).orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(product?.unit ?: "طن") }
    var stock by rememberSaveable { mutableStateOf(product?.stock ?: "0") }
    var minimum by rememberSaveable { mutableStateOf(product?.minimum ?: "0") }
    var notes by rememberSaveable { mutableStateOf(product?.notes.orEmpty()) }
    var track by rememberSaveable { mutableStateOf(product?.trackStock ?: false) }
    var active by rememberSaveable { mutableStateOf(product?.active ?: true) }
    var error by remember { mutableStateOf("") }
    FormDialog(if (product?.id.isNullOrBlank()) "إضافة منتج" else "تعديل المنتج", busy, onDismiss, {
        try { onSave(Product(product?.id.orEmpty(), name, sku, barcode, category, type, description, Money.parse(price), if (cost.isBlank()) null else Money.parse(cost), unit, track, stock, minimum, notes, active)) } catch (e: Exception) { error = e.message.orEmpty() }
    }) {
        Field("اسم المنتج", name, { name = it }); Field("SKU • رمز فريد", sku, { sku = it }); Field("الباركود • اختياري", barcode, { barcode = it })
        Field("التصنيف", category, { category = it }); Field("نوع المنتج", type, { type = it }); Field("الوصف", description, { description = it })
        Field("سعر البيع", price, { price = it }, numeric = true); Field("التكلفة • اختيارية", cost, { cost = it }, numeric = true)
        Choice("وحدة القياس", unit, units + "وحدة مخصصة", { unit = if (it == "وحدة مخصصة") "" else it })
        Field("اسم الوحدة", unit, { unit = it })
        Row { Checkbox(track, { track = it }); Text("إدارة المخزون") }
        if (track) { Field("المخزون المتوفر", stock, { stock = it }, numeric = true); Field("الحد الأدنى", minimum, { minimum = it }, numeric = true) }
        Row { Checkbox(active, { active = it }); Text("منتج نشط") }
        Field("ملاحظات", notes, { notes = it }, lines = 2)
        Text("تعديل السعر لا يغير الفواتير السابقة. تغير الوحدة يستلزم مراجعة المخزون والكميات التاريخية.", style = MaterialTheme.typography.labelSmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
@Composable internal fun CustomerDialog(customer: Customer?, busy: Boolean, onDismiss: () -> Unit, onSave: (Customer) -> Unit) {
    var name by rememberSaveable { mutableStateOf(customer?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(customer?.phone.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(customer?.address.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(customer?.type ?: "فرد") }
    var notes by rememberSaveable { mutableStateOf(customer?.notes.orEmpty()) }
    var error by remember { mutableStateOf("") }
    FormDialog("بيانات العميل", busy, onDismiss, {
        if (name.isBlank()) error = "اسم العميل مطلوب" else onSave(Customer(customer?.id.orEmpty(), name, phone, address, type, notes))
    }) { Field("الاسم الكامل", name, { name = it }); Field("الهاتف", phone, { phone = it }); Field("العنوان", address, { address = it }); Field("نوع العميل", type, { type = it }); Field("ملاحظات", notes, { notes = it }, lines = 2); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) }
}
@Composable internal fun CollectionDialog(balance: Long, busy: Boolean, onDismiss: () -> Unit, onSave: (String, Long, String, String) -> Unit) {
    val token = rememberSaveable { UUID.randomUUID().toString() }
    var amount by rememberSaveable { mutableStateOf(Money.format(balance)) }
    var method by rememberSaveable { mutableStateOf("نقدي") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    FormDialog("تحصيل الذمة • ${Money.format(balance)}", busy, onDismiss, {
        try { val value = Money.parse(amount); require(value > 0 && value <= balance) { "المبلغ يجب أن يكون موجبًا ولا يتجاوز الدين" }; onSave(token, value, method, notes) } catch (e: Exception) { error = e.message.orEmpty() }
    }) { Field("المبلغ", amount, { amount = it }, numeric = true); Choice("طريقة الدفع", method, paymentMethods + "مخصص", { method = it }); if (method !in paymentMethods) Field("اسم طريقة الدفع", method, { method = it }); Field("ملاحظات", notes, { notes = it }); Text("تقل الذمم وتزيد المقبوضات دون زيادة المبيعات"); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) }
}
@Composable internal fun IncomeDialog(busy: Boolean, onDismiss: () -> Unit, onSave: (String, Long, String, String, String, Long) -> Unit) {
    val token = rememberSaveable { UUID.randomUUID().toString() }
    var amount by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("نقدي") }
    var notes by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Amman")).toString()) }
    var error by remember { mutableStateOf("") }
    FormDialog("إضافة دخل غير ناتج عن المبيعات", busy, onDismiss, {
        try { val value = Money.parse(amount); require(value > 0 && source.isNotBlank()) { "أدخل المصدر ومبلغًا موجبًا" }; onSave(token, value, source, method, notes, parseDate(date) ?: error("التاريخ مطلوب")) } catch (e: Exception) { error = e.message.orEmpty() }
    }) { Field("المصدر / التصنيف", source, { source = it }); Field("المبلغ", amount, { amount = it }, numeric = true); Choice("الدفع", method, paymentMethods + "مخصص", { method = it }); if (method !in paymentMethods) Field("طريقة الدفع", method, { method = it }); Field("التاريخ YYYY-MM-DD", date, { date = it }); Field("ملاحظات", notes, { notes = it }); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) }
}
@Composable internal fun ReturnDialog(items: List<InvoiceItem>, cancel: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (String, Map<Long, String>, String, Boolean, String) -> Unit) {
    val token = rememberSaveable { UUID.randomUUID().toString() }
    val quantities = remember { mutableStateMapOf<Long, String>() }
    var reason by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var refundMethod by rememberSaveable { mutableStateOf("نقدي") }
    FormDialog(if (cancel) "تأكيد إلغاء الفاتورة" else "إرجاع المنتجات", busy, onDismiss, {
        if (reason.isBlank()) error = "السبب مطلوب" else if (refundMethod.isBlank()) error = "طريقة رد المبلغ مطلوبة" else onSave(token, quantities.toMap(), reason, cancel, refundMethod)
    }) {
        Text("ستقل المبيعات والذمم ويعاد المخزون. أي مدفوع زائد عن صافي الفاتورة يسجل ردًا نقديًا؛ يجب رد المبلغ فعليًا للعميل.")
        if (!cancel) items.forEach { l -> val available = Money.decimal(l.quantity) - Money.decimal(l.returned); Field("${l.name} • المتاح $available ${l.unit}", quantities[l.id] ?: "0", { quantities[l.id] = it }, numeric = true) }
        Choice("طريقة رد المبلغ الفعلية", refundMethod, paymentMethods + "مخصص", { refundMethod = it })
        if (refundMethod !in paymentMethods) Field("اسم طريقة الرد", refundMethod, { refundMethod = it })
        Field("سبب الإلغاء / الإرجاع", reason, { reason = it }, lines = 2)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
