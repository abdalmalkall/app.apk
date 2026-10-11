@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.naveenapps.expensemanager.commerce

import androidx.glance.appwidget.updateAll
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal object CommerceRegistry {
    private var instance: CommerceStore? = null
    @Synchronized fun get(context: Context): CommerceStore = instance ?: CommerceStore(context.applicationContext).also { instance = it }
}
private val sections = listOf("لوحة التحكم", "فاتورة جديدة", "الفواتير", "المنتجات", "العملاء", "الذمم", "الدخل والمقبوضات", "التقارير", "الإعدادات")
private val sectionIcons = listOf(Icons.Default.Dashboard, Icons.Default.AddShoppingCart, Icons.Default.ReceiptLong, Icons.Default.Inventory2, Icons.Default.People, Icons.Default.AccountBalanceWallet, Icons.Default.Payments, Icons.Default.BarChart, Icons.Default.Settings)

@Composable fun CommerceApp() {
    val context = LocalContext.current
    val store = remember { CommerceRegistry.get(context) }
    var snapshot by remember { mutableStateOf(Snapshot()) }
    var safetyFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf("لوحة التحكم") }
    var revisionRefundMethod by rememberSaveable { mutableStateOf("نقدي") }
    var editInvoice by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var productEdit by remember { mutableStateOf<Product?>(null) }
    var customerEdit by remember { mutableStateOf<Customer?>(null) }
    var collected by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingPath by rememberSaveable { mutableStateOf("") }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var pendingCustomerId by rememberSaveable { mutableStateOf("") }
    var pendingBarcode by rememberSaveable { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun work(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; snapshot = withContext(Dispatchers.IO) { store.snapshot() }; runCatching { com.naveenapps.expensemanager.widget.ExpenseWidget().updateAll(context) } }
            catch (e: Exception) { if(e is CommerceRestoreRestartException) dialog = "restore-restart-required"; message(friendlyError(e)) }
            finally { safetyFiles = runCatching { withContext(Dispatchers.IO) { store.safetyBackupNames() } }.getOrDefault(safetyFiles); busy = false }
        }
    }
    LaunchedEffect(store) {
        try { store.changes.collect { snapshot = withContext(Dispatchers.IO) { store.snapshot() }; safetyFiles = withContext(Dispatchers.IO) { store.safetyBackupNames() }; loaded = true } }
        catch (e: Exception) { loadError = friendlyError(e) }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingPath.takeIf { it.isNotBlank() }?.let(::File)
        if (uri != null && source != null) work { context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("تعذر فتح الملف"); message("تم تنزيل ${source.name}") }
    }
    val restoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val text = context.contentResolver.openInputStream(uri)?.use { input -> val bytes = input.readLimited(50_000_000); require(bytes.size <= 50_000_000) { "النسخة أكبر من 50 ميغابايت" }; bytes.toString(Charsets.UTF_8) } ?: error("تعذر قراءة النسخة")
            pendingBackup = text
            dialog = "restore-confirm"
        }
    }
    fun document(action: String, file: () -> File) { work {
        val result = file()
        scope.launch(Dispatchers.Main) {
            try {
            when (action) { "طباعة" -> CommerceDocuments.print(context, result, snapshot.settings["paper"] ?: "A4"); "مشاركة" -> CommerceDocuments.share(context, result); else -> { pendingPath = result.path; saveFile.launch(result.name) } }
            } catch (e: Exception) { message(friendlyError(e)) }
        }
    } }
    val dark = snapshot.settings["theme"] == "dark"
    val colors = if (dark) darkColorScheme(primary = Color(0xFF73D3BC), background = Color(0xFF101B25), surface = Color(0xFF172734)) else lightColorScheme(primary = Color(0xFF126C5A), secondary = Color(0xFF345A86), background = Color(0xFFF3F6FA), surface = Color.White, primaryContainer = Color(0xFFD9F3EA))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors) {
            if (!loaded) {
                Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
                    if (loadError != null) Text(loadError!!) else CircularProgressIndicator()
                }
                return@MaterialTheme
            }
            BackHandler(page !in sections || drawer.isOpen) { if (drawer.isOpen) scope.launch { drawer.close() } else page = if (page == "تفاصيل الصنف") "المنتجات" else if (page == "كشف العميل") "العملاء" else "الفواتير" }
            ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                ModalDrawerSheet {
                    Text(snapshot.settings["business"] ?: "مؤسستي", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("الفواتير والإدارة المالية", Modifier.padding(horizontal = 24.dp), color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    sections.forEachIndexed { index, label -> NavigationDrawerItem(label = { Text(label) }, icon = { Icon(sectionIcons[index], null) }, selected = page == label, onClick = { page = label; if (label == "فاتورة جديدة") editInvoice = ""; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp)) }
                }
            }) {
                Scaffold(topBar = { TopAppBar(title = { Text(page, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "القائمة") } }, actions = {
                    if (page != "فاتورة جديدة") IconButton(onClick = { editInvoice = ""; page = "فاتورة جديدة" }) { Icon(Icons.Default.Add, "فاتورة جديدة") }
                }) }, snackbarHost = { SnackbarHost(snackbar) }, containerColor = colors.background) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (snapshot.legacyReviewPending > 0) {
                            Text("يوجد ${snapshot.legacyReviewPending} سجل ترحيل يحتاج تصحيحًا؛ الحركات المالية متوقفة لحين المراجعة.", color = colors.error, modifier = Modifier.padding(12.dp))
                            Button(onClick = { dialog = "repair-legacy" }) { Text("مراجعة وتصحيح الترحيل السابق") }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (page) {
                                "لوحة التحكم" -> DashboardPage(snapshot, onInvoice = { selected = it; page = "تفاصيل الفاتورة" }, onNew = { editInvoice = ""; page = "فاتورة جديدة" })
                                "المنتجات" -> ProductsPage(snapshot, onAdd = { productEdit = null; dialog = "product" }, onOpen = { selected = it; page = "تفاصيل الصنف" })
                                "تفاصيل الصنف" -> snapshot.products.firstOrNull { it.id == selected }?.let { p -> ProductDetail(snapshot, p, onEdit = { productEdit = p; dialog = "product" }, onInvoice = { selected = it; page = "تفاصيل الفاتورة" }, onBarcode = {
                                    document(it) {
                                        val actual = if (p.barcode.isBlank()) p.copy(barcode = p.sku).also { product -> store.saveProduct(product) } else p
                                        CommerceDocuments.barcode(context, store.snapshot(), actual)
                                    }
                                }) }
                                "العملاء", "الذمم" -> CustomersPage(snapshot, page == "الذمم", onAdd = { customerEdit = null; dialog = "customer" }, onOpen = { selected = it; page = "كشف العميل" })
                                "كشف العميل" -> snapshot.customers.firstOrNull { it.id == selected }?.let { c -> CustomerDetail(snapshot, c, onEdit = { customerEdit = c; dialog = "customer" }, onCollect = { collected = emptyList(); dialog = "collect-customer" }, onInvoice = { selected = it; page = "تفاصيل الفاتورة" }, onDocument = { document(it) { CommerceDocuments.statement(context, snapshot, c) } }) }
                                "فاتورة جديدة" -> key(editInvoice) { NewInvoicePage(snapshot, busy, pendingBarcode, onBarcodeUsed = { pendingBarcode = "" }, onAddProduct = { barcode -> productEdit = Product(name = "", sku = "", barcode = barcode); dialog = "product" }, onCustomer = { customerEdit = null; dialog = "customer" }, onSave = { token, customer, lines, discount, tax, paid, method, notes, due ->
                                    work { val key = if (editInvoice.isBlank()) store.issueInvoice(token, customer, lines, discount, tax, paid, method, notes, due) else store.reviseInvoice(token, editInvoice, customer, lines, discount, tax, paid, method, notes, due, revisionRefundMethod); selected = key; editInvoice = ""; page = "تفاصيل الفاتورة"; message("تم حفظ الفاتورة") }
                                }, initial = snapshot.invoices.firstOrNull { it.id == editInvoice }, pendingCustomer = pendingCustomerId, onCustomerUsed = { pendingCustomerId = "" }, onRevisionRefundMethod = { revisionRefundMethod = it }) }
                                "الفواتير" -> InvoicesPage(snapshot) { selected = it; page = "تفاصيل الفاتورة" }
                                "تفاصيل الفاتورة" -> snapshot.invoices.firstOrNull { it.id == selected }?.let { i -> InvoiceDetail(snapshot, i, onDocument = { document(it) { CommerceDocuments.invoice(context, snapshot, i) } }, onCollect = { collected = emptyList(); dialog = "collect-invoice" }, onReturn = { dialog = "return" }, onCancel = { dialog = "cancel" }, onReceipt = { payment -> document("مشاركة") { CommerceDocuments.receipt(context, snapshot, listOf(payment)) } }, onEdit = { editInvoice = i.id; page = "فاتورة جديدة" }) }
                                "الدخل والمقبوضات" -> IncomePage(snapshot, onAdd = { dialog = "income" })
                                "التقارير" -> ReportsPage(snapshot) { from, to, kind, format, action -> document(action) { CommerceDocuments.report(context, snapshot, from, to, kind, format) } }
                                "الإعدادات" -> SettingsPage(snapshot, busy, onSave = { settings -> work { store.saveSettings(settings); message("تم حفظ الإعدادات") } }, onBackup = {
                                    document("تنزيل") { File(context.cacheDir, "commerce-documents").apply { mkdirs() }.resolve("commerce-backup-${System.currentTimeMillis()}.json").apply { writeText(store.backup()) } }
                                }, onRestore = { restoreFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, onMigrate = { dialog = "migrate" }, onArchive = {
                                    document("تنزيل") { File(context.cacheDir, "commerce-documents").apply { mkdirs() }.resolve("legacy-archive.json").apply { writeText(store.legacyArchive()) } }
                                }, review = store.migrationReview(), onRepair = { dialog = "repair-legacy" }, safetyFiles = safetyFiles, onSafetyRestore = { name -> work { pendingBackup = store.safetyBackupFile(name).inputStream().use { it.readLimited(50_000_000).toString(Charsets.UTF_8) }; dialog = "restore-confirm" } }, onSafetyDownload = { name -> document("تنزيل") { store.safetyBackupFile(name) } })
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                }
            }
            when (dialog) {
                "restart-required", "restore-restart-required" -> AlertDialog(onDismissRequest = {}, title = { Text(if(dialog=="restart-required") "اكتملت الاستعادة" else "يلزم إعادة تشغيل التطبيق") }, text = { Text(if(dialog=="restart-required") "أغلق التطبيق الآن ثم افتحه من جديد لتطبيق قاعدة النظام القديم وإعداداته. نسخة الأمان السابقة محفوظة." else "فشلت الاستعادة. أغلق التطبيق الآن ثم افتحه لإعادة فتح قواعد البيانات وإكمال أي تراجع معلق. نسخة الأمان السابقة محفوظة.") }, confirmButton = { Button(onClick = { (context as? android.app.Activity)?.finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("إغلاق التطبيق") } })
                "repair-legacy" -> ConfirmDialog("تصحيح الترحيل السابق", "يحفظ نسخة أمان ويعزل الفواتير المرحّلة للموردين والسجلات الغامضة من المبيعات وذمم العملاء مع إبقاء أصلها. الفواتير التي عُدلت أو حُصلت لاحقًا تحتاج مراجعة يدوية.", busy, { dialog = null }) { work { message(store.repairLegacySupplierImports()); dialog = null } }
                "product" -> ProductDialog(productEdit, busy, onDismiss = { dialog = null }, onSave = { p -> work { store.saveProduct(p); snapshot = store.snapshot(); pendingBarcode = if (page == "فاتورة جديدة") p.barcode else ""; dialog = null; message("تم حفظ المنتج") } }, units = (snapshot.settings["units"] ?: "طن،متر مكعب،نقلة،كيس،قطعة").split(',', '،').map { it.trim() }.filter { it.isNotEmpty() })
                "customer" -> CustomerDialog(customerEdit, busy, onDismiss = { dialog = null }, onSave = { c -> work { val customerId = store.saveCustomer(c); snapshot = store.snapshot(); pendingCustomerId = if (page == "فاتورة جديدة") customerId else ""; dialog = null; message("تم حفظ العميل") } })
                "income" -> IncomeDialog(busy, onDismiss = { dialog = null }, onSave = { token, amount, source, method, notes, date -> work { store.addIncome(token, amount, source, method, notes, date); dialog = null; message("تم تسجيل الدخل") } })
                "collect-customer", "collect-invoice" -> {
                    val invoice = if (dialog == "collect-invoice") snapshot.invoices.firstOrNull { it.id == selected } else null
                    val customerId = invoice?.customerId ?: selected
                    CollectionDialog(if (invoice != null) invoice.remaining else snapshot.balance(customerId), busy, onDismiss = { dialog = null }, onSave = { token, amount, method, notes -> work { collected = store.collectDebt(token, customerId, amount, method, notes, invoice?.id); dialog = "receipt"; message("تم التحصيل دون تسجيل مبيعات إضافية") } })
                }
                "receipt" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("تم إصدار سند القبض") }, text = { Text("${collected.size} دفعة مرتبطة بالفواتير المستحقة") }, confirmButton = { TextButton(onClick = { document("مشاركة") { CommerceDocuments.receipt(context, snapshot, snapshot.payments.filter { it.id in collected }) } }) { Text("مشاركة السند") } }, dismissButton = { TextButton(onClick = { document("تنزيل") { CommerceDocuments.receipt(context, snapshot, snapshot.payments.filter { it.id in collected }) }; dialog = null }) { Text("تنزيل PDF") } })
                "cancel", "return" -> snapshot.invoices.firstOrNull { it.id == selected }?.let { i -> ReturnDialog(snapshot.items.filter { it.invoiceId == i.id }, dialog == "cancel", busy, onDismiss = { dialog = null }, onSave = { token, quantities, reason, cancel, refundMethod -> work { store.returnItems(token, i.id, quantities, reason, cancel, refundMethod); dialog = null; message("تم تسجيل التسوية ورد المبلغ الزائد إن وجد") } }) }
                "restore-confirm" -> ConfirmDialog("استعادة نسخة احتياطية", "ستستبدل النسخة الدفتر الحالي بعد التحقق منها. تحفظ نسخة أمان كاملة أولًا. استعادة بيانات النظام القديم أو إعداداته تحتاج إغلاق التطبيق وإعادة فتحه." + if (runCatching { org.json.JSONObject(pendingBackup.orEmpty()).optInt("version") == 1 }.getOrDefault(false)) " هذه نسخة قديمة جزئية: لا تستبدل القاعدة القديمة أو ملفاتها وإعداداتها؛ لا تنقلها وحدها إلى جهاز آخر." else "", busy, { dialog = null; pendingBackup = null }) { work { val restart = store.restore(pendingBackup ?: error("لا توجد نسخة")); pendingBackup = null; dialog = if (restart) "restart-required" else null; page = "لوحة التحكم"; message("تمت الاستعادة") } }
                "migrate" -> ConfirmDialog("ترحيل البيانات القديمة", "تُستخدم عملة الدفتر الحالية (${snapshot.currency})؛ راجعها قبل الترحيل. تحفظ نسخة أمان من الدفتر وتُنسخ البيانات القديمة للأرشيف أولًا، ثم تُرحّل فواتير البيع والذمم والدفعات. الدخل غير المرتبط بفاتورة يسجل دخلًا آخر. المصروفات والتحويلات تبقى بالأرشيف. راجع الأرصدة بعد الترحيل؛ لا يمكن تكراره.", busy, { dialog = null }) { work { val result = store.migrateLegacy(); dialog = null; message(result) } }
            }
        }
    }
}
private fun friendlyError(e: Exception): String = when {
    e.message?.contains("UNIQUE constraint") == true -> "الرمز أو الباركود مسجل مسبقًا"
    e is java.time.format.DateTimeParseException -> "صيغة التاريخ المطلوبة: YYYY-MM-DD"
    e is ArithmeticException -> "تحقق من دقة المبلغ وحدوده (3 منازل عشرية)"
    else -> e.message ?: "تعذر تنفيذ العملية"
}
@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit, numeric: Boolean = false, lines: Int = 1, modifier: Modifier = Modifier.fillMaxWidth(), onDone: (() -> Unit)? = null) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines, modifier = modifier.onPreviewKeyEvent { event -> if (onDone != null && event.key == Key.Enter && event.type == KeyEventType.KeyUp) { onDone(); true } else false },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }), shape = RoundedCornerShape(12.dp))
}
@Composable internal fun Choice(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("$label: $value", Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onChange(it); expanded = false }) } }
    }
}
@Composable internal fun Panel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { if (title != null) Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); content() }
    }
}
@Composable internal fun Metric(label: String, value: String, hint: String = "", modifier: Modifier = Modifier) {
    Card(modifier.widthIn(min = 155.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 23.sp, color = MaterialTheme.colorScheme.primary)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun Metrics(s: Snapshot, vararg values: Pair<String, Long>) { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { values.forEach { Metric(it.first, Money.format(it.second), s.currency) } } }
@Composable internal fun Empty(text: String) { Panel { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable internal fun DocumentActions(action: (String) -> Unit) { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("طباعة", "تنزيل PDF", "مشاركة").forEach { OutlinedButton(onClick = { action(it) }) { Text(it) } } } }
@Composable internal fun InvoiceRow(i: Invoice, onOpen: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(i.number, fontWeight = FontWeight.Bold); Text(i.status, color = if (i.remaining > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    Text("${i.customerName} • ${dateText(i.created)}", style = MaterialTheme.typography.bodySmall)
    Text("${Money.format(i.net)} ${i.currency}  •  المتبقي ${Money.format(i.remaining)}")
} } }
@Composable internal fun DashboardPage(s: Snapshot, onInvoice: (String) -> Unit, onNew: () -> Unit) {
    val today = LocalDate.now(ZoneId.of("Asia/Amman"))
    fun start(d: LocalDate) = d.atStartOfDay(ZoneId.of("Asia/Amman")).toInstant().toEpochMilli()
    Panel { Text("نظرة واضحة على أعمالك", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${dateText(System.currentTimeMillis())} • أرقام من سجلاتك الفعلية"); Button(onClick = onNew) { Icon(Icons.Default.Add, null); Text("إصدار فاتورة") } }
    Metrics(s, "مبيعات اليوم" to s.sales(start(today)), "مبيعات الشهر" to s.sales(start(today.withDayOfMonth(1))), "مبيعات السنة" to s.sales(start(today.withDayOfYear(1))), "المقبوضات الفعلية" to s.cash(), "الذمم المستحقة" to s.debt, "تحصيل الذمم" to s.movements.filter { it.kind == "DEBT_COLLECTION" }.sumOf { it.amount }, "دخل آخر" to s.movements.filter { it.kind == "OTHER_INCOME" }.sumOf { it.amount })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Metric("الفواتير الصادرة", s.invoices.size.toString()); listOf("مدفوعة", "جزئية", "غير مدفوعة").forEach { status -> Metric(status, s.invoices.count { it.status == status }.toString()) } }
    SalesChart(s)
    Panel("المبيعات حسب الصنف") {
        val ranking = s.products.map { p -> p to CommerceAnalytics.product(s, p.id).grossSales }.sortedByDescending { it.second }
        if (ranking.all { it.second == 0L }) Text("لا توجد مبيعات بعد")
        ranking.take(10).forEach { (p, amount) -> Text("${p.name}: ${Money.format(amount)} ${s.currency}") }
        Text("صافي المبيعات بعد الخصم والضريبة والمرتجعات", style = MaterialTheme.typography.labelSmall)
    }
    Panel("الإيرادات حسب المصدر") { s.movements.filter { it.kind in listOf("SALE", "RETURN", "OTHER_INCOME") }.groupBy { if (it.kind in listOf("SALE", "RETURN")) "المبيعات الصافية" else it.source }.forEach { (source, entries) -> Text("$source: ${Money.format(entries.sumOf { it.amount })} ${s.currency}") } }
    Text("أحدث الفواتير", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    s.invoices.take(5).forEach { i -> InvoiceRow(i) { onInvoice(i.id) } }
    if (s.invoices.isEmpty()) Empty("ابدأ بإعداد أسعار الرمل وتسجيل أول فاتورة")
    Panel("أحدث الحركات المالية") { s.movements.take(8).forEach { Text("${movementLabel(it.kind)} • ${Money.format(it.amount)} • ${dateText(it.created)}") } }
}
@Composable private fun SalesChart(s: Snapshot) {
    val today = LocalDate.now(ZoneId.of("Asia/Amman"))
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { date -> val start = date.atStartOfDay(ZoneId.of("Asia/Amman")).toInstant().toEpochMilli(); s.sales(start, CommerceDates.end(date)) to s.cash(start, CommerceDates.end(date)) }
    var chosen by remember { mutableIntStateOf(6) }
    val salesColor = MaterialTheme.colorScheme.primary
    val cashColor = MaterialTheme.colorScheme.secondary
    Panel("المبيعات والتحصيلات • آخر 7 أيام") {
        Text("${days[chosen]}: مبيعات ${Money.format(values[chosen].first)} • مقبوضات ${Money.format(values[chosen].second)}")
        Canvas(Modifier.fillMaxWidth().height(150.dp).pointerInput(values) { detectTapGestures { chosen = (it.x / size.width * 7).toInt().coerceIn(0, 6) } }) {
            val max = values.flatMap { listOf(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) }.maxOrNull()?.coerceAtLeast(1) ?: 1
            val step = size.width / 7
            val hasNegative = values.any { it.first < 0 || it.second < 0 }
            val baseline = if (hasNegative) size.height / 2 else size.height
            val amplitude = if (hasNegative) size.height * .45f else size.height * .85f
            drawLine(Color.Gray.copy(alpha = .3f), androidx.compose.ui.geometry.Offset(0f, baseline), androidx.compose.ui.geometry.Offset(size.width, baseline))
            values.forEachIndexed { i, v ->
                listOf(v.first to salesColor, v.second to cashColor).forEachIndexed { j, (amount, color) ->
                    val height = kotlin.math.abs(amount).toFloat() / max * amplitude
                    val top = if (amount >= 0) baseline - height else baseline
                    drawRoundRect(color, androidx.compose.ui.geometry.Offset(step * i + step * (if (j == 0) .12f else .5f), top), androidx.compose.ui.geometry.Size(step * .3f, height.coerceAtLeast(2f)), androidx.compose.ui.geometry.CornerRadius(5f))
                }
            }
        }
        Text("الأخضر: المبيعات • الأزرق: المقبوضات • اضغط الأعمدة للتفاصيل. القيم السالبة تظهر تحت خط الصفر.", style = MaterialTheme.typography.labelSmall)
    }
}
