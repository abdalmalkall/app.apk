package com.naveenapps.expensemanager.commerce

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Separate, versioned financial store. The existing Room database is preserved; full restores require an application restart. */
class CommerceStore(context: Context, name: String = "commerce_v1.db") : SQLiteOpenHelper(context.applicationContext, name, null, 2) {
    private val appContext = context.applicationContext
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        schema.forEach(db::execSQL)
        put(db, "settings", "key" to "currency", "value" to "JOD")
        put(db, "settings", "key" to "business", "value" to "مؤسستي")
        put(db, "settings", "key" to "tax", "value" to "0")
        put(db, "settings", "key" to "paper", "value" to "A4")
        listOf("رمل 1", "رمل 2").forEachIndexed { index, name ->
            putProduct(db, Product(id = "sand-${index + 1}", name = name, sku = "SAND-${index + 1}"))
        }
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE invoices ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0 CHECK(excluded IN (0,1))")
            db.execSQL("ALTER TABLE credit_notes ADD COLUMN quantities TEXT NOT NULL DEFAULT '{}'")
        }
    }
    private fun id() = UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()
    @Synchronized private fun <T> atomic(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        val result: T
        try { result = block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        revision.value += 1
        return result
    }
    private fun audit(db: SQLiteDatabase, action: String, target: String, details: String) {
        put(db, "audit_logs", "created" to now(), "action" to action, "target" to target, "details" to details)
    }
    private fun putProduct(db: SQLiteDatabase, p: Product) {
        put(db, "products", "id" to p.id, "name" to p.name, "sku" to p.sku, "barcode" to p.barcode.ifBlank { null }, "category" to p.category, "type" to p.type,
            "description" to p.description, "price" to p.price, "cost" to p.cost, "unit" to p.unit, "track_stock" to p.trackStock, "stock" to p.stock,
            "minimum" to p.minimum, "notes" to p.notes, "active" to p.active)
    }
    fun saveProduct(product: Product): String = atomic { db ->
        require(product.name.isNotBlank() && product.sku.isNotBlank() && product.unit.isNotBlank()) { "الاسم والرمز والوحدة مطلوبة" }
        require(product.price in 0..Money.MAX && (product.cost == null || product.cost in 0..Money.MAX)) { "سعر غير صالح" }
        val p = product.copy(id = product.id.ifBlank { id() }, stock = Money.quantity(product.stock).toPlainString(), minimum = Money.quantity(product.minimum).toPlainString())
        // UPDATE preserves foreign keys and snapshots; uniqueness is checked by SQLite.
        val existing = query(db, "SELECT id FROM products WHERE id=?", p.id) { it.string("id") }.isNotEmpty()
        if (existing) {
            val conflicting = query(db, "SELECT id FROM products WHERE id<>? AND (sku=? OR (barcode=? AND barcode IS NOT NULL))", p.id, p.sku, p.barcode) { it.string("id") }
            require(conflicting.isEmpty()) { "رمز المنتج أو الباركود مسجل مسبقًا" }
            val oldProduct = products(db).first { it.id == p.id }
            require(oldProduct.unit == p.unit || items(db).none { it.productId == p.id }) { "لا تغير وحدة منتج مباع دون تحويل الكميات؛ أنشئ منتجًا مستقلًا للوحدة الجديدة" }
            val v = productValues(p)
            db.update("products", v, "id=?", arrayOf(p.id))
        } else putProduct(db, p)
        audit(db, "PRODUCT_SAVE", p.id, p.name)
        p.id
    }
    private fun productValues(p: Product) = values("name" to p.name, "sku" to p.sku, "barcode" to p.barcode.ifBlank { null }, "category" to p.category, "type" to p.type,
        "description" to p.description, "price" to p.price, "cost" to p.cost, "unit" to p.unit, "track_stock" to p.trackStock, "stock" to p.stock,
        "minimum" to p.minimum, "notes" to p.notes, "active" to p.active)
    fun saveCustomer(customer: Customer): String = atomic { db ->
        require(customer.name.isNotBlank()) { "اسم العميل مطلوب" }
        val c = customer.copy(id = customer.id.ifBlank { id() })
        val v = values("name" to c.name.trim(), "phone" to c.phone, "address" to c.address, "type" to c.type, "notes" to c.notes)
        if (db.update("customers", v, "id=?", arrayOf(c.id)) == 0) { v.put("id", c.id); db.insertOrThrow("customers", null, v) }
        audit(db, "CUSTOMER_SAVE", c.id, c.name)
        c.id
    }
    fun issueInvoice(token: String, customerId: String?, lines: List<DraftLine>, discount: Long, taxRate: String, paid: Long, method: String, notes: String, due: Long? = null, created: Long = now()): String = atomic { db ->
        ensureLegacyReviewed(db)
        query(db, "SELECT id FROM invoices WHERE token=?", token) { it.string("id") }.firstOrNull()?.let { return@atomic it }
        require(token.isNotBlank() && lines.isNotEmpty() && method.isNotBlank()) { "أضف منتجًا وطريقة دفع" }
        require(created in 0..now() && (due == null || due >= 0)) { "تاريخ الفاتورة غير صالح أو في المستقبل" }
        val products = products(db).associateBy { it.id }
        lines.forEach { require(products[it.productId]?.active == true) { "المنتج متوقف أو غير موجود" }; require(it.unit == products.getValue(it.productId).unit) { "وحدة الفاتورة يجب أن تطابق وحدة المنتج" } }
        val totals = Money.totals(lines.map { Money.line(it.quantity, it.price) }, discount, taxRate)
        require(paid in 0..totals.total) { "المدفوع يتجاوز الإجمالي أو سالب" }
        require(paid == totals.total || customerId != null) { "البيع بالدين يتطلب عميلًا مسجلًا" }
        val customer = customerId?.let { key -> customers(db).firstOrNull { it.id == key } ?: error("العميل غير موجود") }
        lines.forEach { line -> products.getValue(line.productId).cost?.let { Money.line(line.quantity, it) } }
        val key = id()
        val number = nextNumber(db, "invoice", "INV")
        put(db, "invoices", "id" to key, "token" to token, "number" to number, "customer_id" to customerId, "customer_name" to (customer?.name ?: "عميل نقدي"),
            "created" to created, "due" to due, "subtotal" to totals.subtotal, "discount" to discount, "tax_rate" to taxRate, "tax" to totals.tax, "total" to totals.total,
            "method" to method, "notes" to notes, "state" to "ISSUED", "currency" to setting(db, "currency", "JOD"))
        lines.forEach { line ->
            val p = products.getValue(line.productId)
            put(db, "invoice_items", "invoice_id" to key, "product_id" to p.id, "name" to p.name, "sku" to p.sku, "quantity" to line.quantity,
                "unit" to line.unit, "price" to line.price, "cost" to p.cost, "total" to Money.line(line.quantity, line.price), "returned" to "0", "tracked" to p.trackStock)
        }
        lines.groupBy { it.productId }.forEach { (productId, productLines) ->
            val p = products.getValue(productId)
            if (p.trackStock) {
                val stock = Money.decimal(p.stock) - productLines.fold(BigDecimal.ZERO) { acc, l -> acc + Money.decimal(l.quantity) }
                require(stock >= BigDecimal.ZERO) { "المخزون غير كافٍ: ${p.name}" }
                db.update("products", values("stock" to stock.toPlainString()), "id=?", arrayOf(productId))
            }
        }
        movement(db, key, "SALE", totals.total, created, "مبيعات", method, number)
        if (paid > 0) payment(db, key, paid, created, method, "دفعة إصدار الفاتورة", "INVOICE_PAYMENT", "$token:initial")
        audit(db, "INVOICE_ISSUE", key, number)
        key
    }
    /** Revisions retain the old invoice and all original receipts. The whole replacement is atomic. */
    fun reviseInvoice(token: String, originalId: String, customerId: String?, lines: List<DraftLine>, discount: Long, taxRate: String, paid: Long, method: String, notes: String, due: Long?, refundMethod: String = method): String = atomic { db ->
        ensureLegacyReviewed(db)
        require(token.isNotBlank()) { "معرّف عملية التصحيح مطلوب" }
        query(db, "SELECT result FROM operations WHERE token=?", "revision:$token") { it.string("result") }.firstOrNull()?.let { return@atomic it }
        val original = invoices(db).first { it.id == originalId }
        require(original.state == "ISSUED" && original.credit == 0L && items(db).filter { it.invoiceId == originalId }.all { Money.decimal(it.returned) == BigDecimal.ZERO }) { "عدّل الفواتير التي لم تُلغَ أو تُرجع فقط" }
        returnItems("revision-cancel:$token", originalId, emptyMap(), "تصحيح الفاتورة ${original.number}", true, refundMethod)
        val replacement = issueInvoice("revision-issue:$token", customerId, lines, discount, taxRate, paid, method, "بديلة للفاتورة ${original.number}. $notes", due)
        put(db, "operations", "token" to "revision:$token", "result" to replacement)
        audit(db, "INVOICE_REVISION", originalId, "بديل: $replacement / المدفوع القديم ${Money.format(original.paid)} / الجديد ${Money.format(paid)}")
        replacement
    }
    private fun nextNumber(db: SQLiteDatabase, sequence: String, prefix: String): String {
        db.execSQL("INSERT OR IGNORE INTO sequences(name,value) VALUES(?,0)", arrayOf(sequence))
        val current = query(db, "SELECT value FROM sequences WHERE name=?", sequence) { it.long("value") }.single()
        require(current < Long.MAX_VALUE - 1) { "تسلسل الأرقام ممتلئ؛ أوقف العملية للمراجعة" }
        db.execSQL("UPDATE sequences SET value=value+1 WHERE name=?", arrayOf(sequence))
        val n = query(db, "SELECT value FROM sequences WHERE name=?", sequence) { it.long("value") }.single()
        return "$prefix-${n.toString().padStart(6, '0')}"
    }
    private fun movement(db: SQLiteDatabase, invoiceId: String?, kind: String, amount: Long, created: Long, source: String, method: String, notes: String) {
        put(db, "financial_transactions", "id" to id(), "invoice_id" to invoiceId, "kind" to kind, "amount" to amount, "created" to created, "source" to source, "method" to method, "notes" to notes)
    }
    private fun payment(db: SQLiteDatabase, invoiceId: String, amount: Long, created: Long, method: String, notes: String, origin: String, token: String): String {
        val key = id()
        put(db, "payments", "id" to key, "token" to token, "receipt" to nextNumber(db, "receipt", "REC"), "invoice_id" to invoiceId, "amount" to amount, "created" to created, "method" to method, "notes" to notes, "origin" to origin)
        movement(db, invoiceId, origin, amount, created, if (origin == "DEBT_COLLECTION") "تحصيل ذمم" else "دفعات فواتير", method, notes)
        return key
    }
    fun collectDebt(token: String, customerId: String, amount: Long, method: String, notes: String, invoiceId: String? = null): List<String> = atomic { db ->
        ensureLegacyReviewed(db)
        require(token.isNotBlank()) { "معرّف عملية التحصيل مطلوب" }
        val prior = query(db, "SELECT result FROM operations WHERE token=?", token) { it.string("result") }.firstOrNull()
        if (prior != null) {
            val ids = prior.split(',').filter { it.isNotBlank() }
            require(ids.isNotEmpty() && ids.all { key -> query(db, "SELECT id FROM payments WHERE id=? AND origin='DEBT_COLLECTION'", key) { it.string("id") }.isNotEmpty() }) { "معرّف العملية مستخدم لإجراء آخر" }
            return@atomic ids
        }
        require(amount in 1..Money.MAX && method.isNotBlank()) { "أدخل مبلغًا موجبًا ضمن النطاق وطريقة الدفع" }
        val debts = invoices(db).filter { it.customerId == customerId && it.remaining > 0 && (invoiceId == null || it.id == invoiceId) }.sortedBy { it.created }
        require(amount <= debts.sumOf { it.remaining }) { "المبلغ يتجاوز الذمة المستحقة" }
        var left = amount
        val receipts = mutableListOf<String>()
        debts.forEach { invoice ->
            val allocation = minOf(left, invoice.remaining)
            if (allocation > 0) {
                receipts += payment(db, invoice.id, allocation, now(), method, notes, "DEBT_COLLECTION", "$token:${invoice.id}")
                left -= allocation
            }
        }
        put(db, "operations", "token" to token, "result" to receipts.joinToString(","))
        audit(db, "DEBT_COLLECTION", customerId, "${Money.format(amount)} / ${receipts.joinToString()}")
        receipts
    }
    fun addIncome(token: String, amount: Long, source: String, method: String, notes: String, created: Long): Unit = atomic { db ->
        ensureLegacyReviewed(db)
        require(token.isNotBlank()) { "معرّف عملية الدخل مطلوب" }
        query(db, "SELECT result FROM operations WHERE token=?", token) { it.string("result") }.firstOrNull()?.let { require(it == "income") { "معرّف العملية مستخدم لإجراء آخر" }; return@atomic }
        require(amount in 1..Money.MAX && source.isNotBlank() && method.isNotBlank()) { "أدخل المصدر والمبلغ وطريقة الدفع" }
        require(created in 0..now()) { "تاريخ الدخل غير صالح أو في المستقبل" }
        movement(db, null, "OTHER_INCOME", amount, created, source, method, notes)
        put(db, "operations", "token" to token, "result" to "income")
        audit(db, "OTHER_INCOME", token, "$source / ${Money.format(amount)}")
    }
    /** Credit notes reduce sales and debt; any cash already paid beyond the new net total is refunded. */
    fun returnItems(token: String, invoiceId: String, quantities: Map<Long, String>, reason: String, cancel: Boolean = false, refundMethod: String = "نقدي"): Unit = atomic { db ->
        ensureLegacyReviewed(db)
        require(token.isNotBlank()) { "معرّف عملية المرتجع مطلوب" }
        query(db, "SELECT result FROM operations WHERE token=?", token) { it.string("result") }.firstOrNull()?.let { require(it == "return") { "معرّف العملية مستخدم لإجراء آخر" }; return@atomic }
        require(reason.isNotBlank() && refundMethod.isNotBlank()) { "سبب الإلغاء وطريقة رد المبلغ مطلوبان" }
        val invoice = invoices(db).first { it.id == invoiceId }
        require(invoice.state == "ISSUED") { "الفاتورة ملغاة أو مرتجعة بالكامل" }
        val items = items(db).filter { it.invoiceId == invoiceId }
        require(quantities.keys.all { key -> items.any { it.id == key } }) { "بند مرتجع غير صالح" }
        var anyQuantity = false
        val returnedQuantities = mutableMapOf<String, String>()
        val before = items.associate { it.id.toString() to it.returned }
        val products = products(db).associateBy { it.id }.toMutableMap()
        val returnedDetails = mutableListOf<String>()
        items.forEach { item ->
            val available = Money.decimal(item.quantity) - Money.decimal(item.returned)
            val qty = if (cancel) available else Money.quantity(quantities[item.id] ?: "0")
            require(qty <= available) { "كمية المرتجع تتجاوز المباع" }
            returnedQuantities[item.id.toString()] = qty.toPlainString()
            if (qty > BigDecimal.ZERO) {
                anyQuantity = true
                returnedDetails += "${item.id}: ${item.name} / ${qty.toPlainString()} ${item.unit}"
                db.update("invoice_items", values("returned" to (Money.decimal(item.returned) + qty).toPlainString()), "id=?", arrayOf(item.id.toString()))
                val p = products.getValue(item.productId)
                if (item.tracked) {
                    val restocked = p.copy(stock = Money.quantity((Money.decimal(p.stock) + qty).toPlainString()).toPlainString())
                    db.update("products", values("stock" to restocked.stock), "id=?", arrayOf(p.id))
                    products[p.id] = restocked
                }
            }
        }
        require(anyQuantity) { "اختر كمية للإرجاع" }
        val afterItems = items(db).filter { it.invoiceId == invoiceId }
        val allReturned = afterItems.all { Money.decimal(it.quantity) == Money.decimal(it.returned) }
        val targetCredit = Money.returnCredit(afterItems, invoice.total, invoice.subtotal)
        // Existing historical credits stay untouched; new credits reconcile the cumulative quantity.
        val credit = (targetCredit - invoice.credit).coerceIn(0, invoice.net)
        val refund = (invoice.paid - (invoice.net - credit)).coerceAtLeast(0)
        val created = now()
        val detail = JSONObject().put("rounding", "CUMULATIVE_V1").put("items", JSONObject(returnedQuantities)).put("before", JSONObject(before))
        put(db, "credit_notes", "id" to id(), "token" to token, "invoice_id" to invoiceId, "amount" to credit, "refund" to refund, "created" to created, "reason" to reason, "quantities" to detail.toString())
        movement(db, invoiceId, "RETURN", -credit, created, "مرتجعات", invoice.method, reason)
        if (refund > 0) movement(db, invoiceId, "REFUND", -refund, created, "رد مبالغ للعميل", refundMethod, reason)
        if (cancel || allReturned) db.update("invoices", values("state" to if (cancel) "CANCELLED" else "RETURNED"), "id=?", arrayOf(invoiceId))
        put(db, "operations", "token" to token, "result" to "return")
        audit(db, if (cancel) "INVOICE_CANCEL" else "RETURN", invoiceId, "${returnedDetails.joinToString("؛ ")} / $reason / ${Money.format(credit)} / رد ${Money.format(refund)}")
    }
    fun saveSettings(settings: Map<String, String>) = atomic { db ->
        CommerceValidation.validateSettings(settings)
        val oldCurrency = setting(db, "currency", "JOD")
        require(oldCurrency == settings["currency"] || invoices(db, true).isEmpty() && query(db, "SELECT id FROM financial_transactions LIMIT 1") { it.string("id") }.isEmpty()) { "لا يمكن تغيير عملة دفتر يحتوي عمليات؛ أنشئ دفترًا مستقلًا لعملة أخرى" }
        settings.forEach { (key, value) -> put(db, "settings", "key" to key, "value" to value, replace = true) }
        audit(db, "SETTINGS_SAVE", "settings", settings.keys.joinToString())
    }
    private fun setting(db: SQLiteDatabase, key: String, fallback: String = "") = query(db, "SELECT value FROM settings WHERE key=?", key) { it.string("value") }.firstOrNull() ?: fallback
    @Synchronized fun snapshot(): Snapshot {
        val db = readableDatabase
        return Snapshot(products(db), customers(db), invoices(db), items(db), payments(db), movements(db),
            query(db, "SELECT * FROM settings") { it.string("key") to it.string("value") }.toMap(),
            query(db, "SELECT * FROM audit_logs ORDER BY id DESC LIMIT 500") { Audit(it.long("id"), it.long("created"), it.string("action"), it.string("target"), it.string("details")) }, legacyReviewPending = pendingLegacy(db).size)
    }
    private fun products(db: SQLiteDatabase) = query(db, "SELECT * FROM products ORDER BY name") { c -> Product(c.string("id"), c.string("name"), c.string("sku"), c.string("barcode"), c.string("category"), c.string("type"), c.string("description"), c.long("price"), c.nullLong("cost"), c.string("unit"), c.long("track_stock") == 1L, c.string("stock"), c.string("minimum"), c.string("notes"), c.long("active") == 1L) }
    private fun customers(db: SQLiteDatabase) = query(db, "SELECT * FROM customers WHERE id NOT IN (SELECT customer_id FROM invoices WHERE excluded=1 AND customer_id LIKE 'legacy:transaction:%') OR id IN (SELECT customer_id FROM invoices WHERE excluded=0) ORDER BY name") { Customer(it.string("id"), it.string("name"), it.string("phone"), it.string("address"), it.string("type"), it.string("notes")) }
    private fun invoices(db: SQLiteDatabase, includeExcluded: Boolean = false) = query(db, """SELECT i.*, COALESCE((SELECT SUM(amount) FROM credit_notes WHERE invoice_id=i.id),0) credit,
        COALESCE((SELECT SUM(amount) FROM payments WHERE invoice_id=i.id),0)-COALESCE((SELECT SUM(refund) FROM credit_notes WHERE invoice_id=i.id),0) paid
        FROM invoices i ${if (includeExcluded) "" else "WHERE i.excluded=0"} ORDER BY created DESC, number DESC""") { c -> Invoice(c.string("id"), c.string("number"), c.nullString("customer_id"), c.string("customer_name"), c.long("created"), c.nullLong("due"), c.long("subtotal"), c.long("discount"), c.string("tax_rate"), c.long("tax"), c.long("total"), c.string("method"), c.string("notes"), c.string("state"), c.string("currency"), c.long("credit"), c.long("paid")) }
    private fun items(db: SQLiteDatabase, includeExcluded: Boolean = false) = query(db, "SELECT * FROM invoice_items ${if (includeExcluded) "" else "WHERE invoice_id IN (SELECT id FROM invoices WHERE excluded=0)"} ORDER BY id") { c -> InvoiceItem(c.long("id"), c.string("invoice_id"), c.string("product_id"), c.string("name"), c.string("sku"), c.string("quantity"), c.string("unit"), c.long("price"), c.nullLong("cost"), c.long("total"), c.string("returned"), c.long("tracked") == 1L) }
    private fun payments(db: SQLiteDatabase) = query(db, "SELECT * FROM payments WHERE invoice_id IN (SELECT id FROM invoices WHERE excluded=0) ORDER BY created DESC") { c -> Payment(c.string("id"), c.string("receipt"), c.string("invoice_id"), c.long("amount"), c.long("created"), c.string("method"), c.string("notes"), c.string("origin")) }
    private fun movements(db: SQLiteDatabase) = query(db, "SELECT * FROM financial_transactions WHERE invoice_id IS NULL OR invoice_id IN (SELECT id FROM invoices WHERE excluded=0) ORDER BY created DESC") { c -> Movement(c.string("id"), c.nullString("invoice_id"), c.string("kind"), c.long("amount"), c.long("created"), c.string("source"), c.string("method"), c.string("notes")) }

    @Synchronized fun backup(): String = CommerceBackup(appContext, this).create(exportDatabase(readableDatabase)).toString()
    internal fun ledgerBackup(): JSONObject = exportDatabase(readableDatabase)
    internal fun validateLedger(root: JSONObject): JSONObject {
        require(root.optString("format") == "expensemanager-commerce") { "صيغة النسخة غير متوافقة" }
        val version = root.get("version")
        require(version is Int && version in 1..2) { "إصدار النسخة غير متوافق" }
        val data = JSONObject(root.getJSONObject("tables").toString())
        require(data.keys().asSequence().toSet() == tables.toSet()) { "جداول النسخة غير متوافقة" }
        if (version == 1) {
            data.getJSONArray("invoices").objects().forEach { require(!it.has("excluded")); it.put("excluded", 0) }
            data.getJSONArray("credit_notes").objects().forEach { require(!it.has("quantities")); it.put("quantities", "{}") }
        }
        SQLiteDatabase.create(null).use { candidate ->
            candidate.setForeignKeyConstraintsEnabled(true)
            schema.forEach(candidate::execSQL)
            try { importRows(candidate, data); validate(candidate) }
            catch (e: Exception) { throw IllegalArgumentException("النسخة غير صحيحة: ${e.message.orEmpty()}", e) }
        }
        return data
    }
    private fun exportDatabase(db: SQLiteDatabase): JSONObject {
        val data = JSONObject()
        tables.forEach { table -> data.put(table, JSONArray(query(db, "SELECT * FROM $table") { c ->
            JSONObject().apply { c.columnNames.forEachIndexed { index, name -> put(name, when (c.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                else -> c.getString(index)
            }) } }
        })) }
        return JSONObject().put("format", "expensemanager-commerce").put("version", 2).put("created", now()).put("tables", data)
    }
    @Synchronized fun restore(text: String): Boolean = CommerceBackup(appContext, this).restore(text)
    internal fun replaceLedger(data: JSONObject, external: () -> Unit = {}) = atomic { db ->
        tables.asReversed().forEach { db.delete(it, null, null) }
        importRows(db, data)
        external()
        audit(db, "BACKUP_RESTORE", "database", "استعادة متحققة؛ نسخة أمان كاملة محفوظة")
    }
    internal fun writeSafety(prefix: String): java.io.File {
        val folder = java.io.File(appContext.filesDir, "commerce-backups").apply { check(mkdirs() || isDirectory) }
        return java.io.File(folder, "$prefix-${now()}-${id()}.json").also { CommerceBackup.writeDurable(it, backup().toByteArray()) }
    }
    internal fun recoverPendingRestore() = CommerceBackup(appContext, this).recover()
    private fun importRows(db: SQLiteDatabase, data: JSONObject) {
        tables.forEach { table ->
            val rows = data.getJSONArray(table)
            val types = query(db, "PRAGMA table_info($table)") { it.string("name") to (it.string("type") to (it.long("notnull") == 1L)) }.toMap()
            val columns = types.keys
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == columns) { "مخطط النسخة غير صحيح: $table" }
                val v = ContentValues()
                row.keys().forEach { key ->
                    val value = row.get(key)
                    val (type, required) = types.getValue(key)
                    when {
                        value == JSONObject.NULL -> { require(!required) { "حقل مطلوب فارغ: $table.$key" }; v.putNull(key) }
                        type == "INTEGER" -> { require(value is Int || value is Long) { "حقل $table.$key يجب أن يكون عددًا صحيحًا" }; v.put(key, (value as Number).toLong()) }
                        else -> { require(value is String) { "حقل $table.$key يجب أن يكون نصًا" }; v.put(key, value as String) }
                    }
                }
                db.insertOrThrow(table, null, v)
            }
        }
    }
    private fun validate(db: SQLiteDatabase) = CommerceValidation.validate(db, invoices(db, true), items(db, true), products(db))
    /** Import only identifiable sales/debts. All other legacy records remain available in the archive. */
    @Synchronized fun migrateLegacy(): String {
        if (setting(readableDatabase, "legacy_imported") == "true") return "تم ترحيل البيانات سابقًا؛ لم تتكرر العمليات"
        val path = appContext.getDatabasePath("expense_manager_database.db")
        if (!path.exists()) return "لا توجد قاعدة بيانات قديمة"
        val archive = CommerceBackup.captureLegacy(appContext).getJSONObject("tables")
        writeSafety("before-migration")
        return atomic { db ->
            if (setting(db, "legacy_imported") == "true") return@atomic "تم ترحيل البيانات سابقًا؛ لم تتكرر العمليات"
            archive.keys().forEach { table -> put(db, "legacy_archive", "name" to table, "json" to archive.getJSONArray(table).toString(), replace = true) }
            val customers = archive.optJSONArray("customer") ?: JSONArray()
            for (n in 0 until customers.length()) {
                val c = customers.getJSONObject(n)
                put(db, "customers", "id" to "legacy:${c.getString("id")}", "name" to c.optString("name", "عميل قديم"), "phone" to c.optString("phone").takeUnless { it == "null" }.orEmpty(),
                    "address" to c.optString("address").takeUnless { it == "null" }.orEmpty(), "type" to "مرحّل", "notes" to c.optString("notes").takeUnless { it == "null" }.orEmpty())
            }
            val tx = archive.optJSONArray("transaction") ?: JSONArray()
            val oldPayments = archive.optJSONArray("customer_payment") ?: JSONArray()
            var count = 0
            var archived = 0
            val review = JSONArray()
            for (n in 0 until tx.length()) {
                val t = tx.getJSONObject(n)
                val type = t.optString("type")
                val isDebt = type == "3" || type == "DEBT"
                val isIncome = type == "0" || type == "INCOME"
                val number = t.optString("invoice_number").takeUnless { it == "null" || it.isBlank() }
                val classification = legacyClassification(t)
                if (classification != "CUSTOMER" && isDebt || classification == "SUPPLIER" || classification == "AMBIGUOUS") {
                    review.put(legacyReviewEntry(t, archive, "للمراجعة؛ خارج المبيعات وذمم العملاء"))
                    archived++; continue
                }
                val amountText = t.optString("amount_decimal").takeUnless { it == "null" || it.isBlank() } ?: t.optString("amount", "0")
                val amount = Money.decimal(amountText).setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact()
                val created = t.optLong("created_on", now())
                if (amount <= 0 || amount > Money.MAX || (!isDebt && !isIncome)) { archived++; continue }
                if (isIncome && number == null) {
                    movement(db, null, "OTHER_INCOME", amount, created, "دخل مرحّل", "طريقة غير محددة (مرحّل)", t.optString("notes"))
                    archived++
                    continue
                }
                val customerRaw = t.optString("customer_id").takeUnless { it == "null" || it.isBlank() }
                var customerId = customerRaw?.let { "legacy:$it" }
                val customerName = t.optString("customer_name").takeUnless { it == "null" || it.isBlank() } ?: "عميل مرحّل"
                if (customerId != null && query(db, "SELECT id FROM customers WHERE id=?", customerId) { it.string("id") }.isEmpty()) customerId = null
                if (isDebt && customerId == null) {
                    customerId = "legacy:transaction:${t.getString("id")}" 
                    put(db, "customers", "id" to customerId, "name" to customerName, "phone" to "", "address" to "", "type" to "مرحّل", "notes" to "يرجى مراجعة بيانات العميل المرحّل")
                }
                val category = t.optString("category_id", "unknown")
                val originalUnit = t.optString("unit").trim().takeUnless { it.isBlank() || it == "null" }
                val knownLine = runCatching {
                    require(originalUnit != null)
                    val qty = Money.decimal(t.optString("quantity")).stripTrailingZeros().toPlainString()
                    val price = Money.parse(t.optString("unit_price"))
                    require(Money.line(qty, price) == amount)
                    Triple(qty, originalUnit, price)
                }.getOrNull()
                val unit = knownLine?.second ?: "سجل"
                val quantity = knownLine?.first ?: "1"
                val unitPrice = knownLine?.third ?: amount
                val productId = "legacy:category:$category:unit:$unit"
                if (query(db, "SELECT id FROM products WHERE id=?", productId) { it.string("id") }.isEmpty()) {
                    val categories = archive.optJSONArray("category") ?: JSONArray()
                    val categoryName = (0 until categories.length()).map { categories.getJSONObject(it) }.firstOrNull { it.optString("id") == category }?.optString("name") ?: "صنف قديم"
                    putProduct(db, Product(id = productId, name = "$categoryName (مرحّل • $unit)", sku = "LEGACY-${UUID.nameUUIDFromBytes("$category|$unit".toByteArray(Charsets.UTF_8))}", unit = unit, active = false, notes = "سجلات أصلية محفوظة؛ الكميات والأسعار المتطابقة مع قيمة الأصل محفوظة"))
                }
                val invoiceId = "legacy:${t.getString("id")}" 
                val newNumber = nextNumber(db, "invoice", "INV")
                put(db, "invoices", "id" to invoiceId, "token" to invoiceId, "number" to newNumber, "customer_id" to customerId, "customer_name" to customerName,
                    "created" to created, "due" to null, "subtotal" to amount, "discount" to 0L, "tax_rate" to "0", "tax" to 0L, "total" to amount, "method" to "طريقة غير محددة (مرحّل)",
                    "notes" to "مرحل من ${number ?: t.getString("id")}. كمية أصلية: ${t.optString("quantity")}. ${t.optString("notes")}", "state" to "ISSUED", "currency" to setting(db, "currency", "JOD"))
                val p = products(db).first { it.id == productId }
                put(db, "invoice_items", "invoice_id" to invoiceId, "product_id" to productId, "name" to p.name, "sku" to p.sku, "quantity" to quantity, "unit" to unit, "price" to unitPrice, "cost" to null, "total" to amount, "returned" to "0", "tracked" to false)
                movement(db, invoiceId, "SALE", amount, created, "مبيعات مرحلة", "طريقة غير محددة (مرحّل)", newNumber)
                if (!isDebt) payment(db, invoiceId, amount, created, "طريقة غير محددة (مرحّل)", "قبض فاتورة مرحّلة", "INVOICE_PAYMENT", "$invoiceId:initial")
                else {
                    var paid = 0L
                    for (j in 0 until oldPayments.length()) {
                        val op = oldPayments.getJSONObject(j)
                        if (op.optString("invoice_transaction_id") != t.getString("id")) continue
                        val raw = op.optString("amount_decimal").takeUnless { it == "null" || it.isBlank() } ?: op.optString("amount", "0")
                        val value = Money.decimal(raw).setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact()
                        require(value > 0 && paid + value <= amount) { "دفعات قديمة تتجاوز الفاتورة؛ أوقف الترحيل للمراجعة" }
                        payment(db, invoiceId, value, op.optLong("paid_on", created), "طريقة غير محددة (مرحّل)", "تحصيل مرحّل: ${op.optString("notes")}", "DEBT_COLLECTION", "legacy:payment:${op.getString("id")}")
                        paid += value
                    }
                }
                count++
            }
            put(db, "legacy_archive", "name" to "commerce-migration-review", "json" to review.toString(), replace = true)
            put(db, "settings", "key" to "legacy_imported", "value" to "true", replace = true)
            validate(db)
            audit(db, "LEGACY_IMPORT", "database", "$count فواتير / $archived سجلات أخرى مؤرشفة")
            "تم ترحيل $count فاتورة. حُفظت جميع السجلات الأصلية؛ الموردون والسجلات الغامضة والمصروفات والتحويلات في مسار المراجعة والأرشيف، خارج المبيعات وذمم العملاء."
        }
    }
    private fun legacyReviewEntry(t: JSONObject, archive: JSONObject, status: String): JSONObject {
        val classification = classifyLegacy(t)
        val payments = (archive.optJSONArray("supplier_payment") ?: JSONArray()).objects().filter { it.optString("invoice_transaction_id") == t.getString("id") }
        val entry = JSONObject().put("id", t.getString("id")).put("classification", classification).put("original", t).put("supplier_payments", JSONArray(payments)).put("status", status)
        if (classification == "SUPPLIER" && t.optString("type") in listOf("3", "DEBT")) {
            runCatching {
                fun amount(row: JSONObject): Long = Money.parse((row.optString("amount_decimal").takeUnless { it.isBlank() || it == "null" } ?: row.getString("amount")))
                val original = amount(t)
                val paid = payments.fold(0L) { a,p -> Math.addExact(a, amount(p)) }
                require(paid <= original)
                entry.put("commitment", Money.format(original)).put("supplier_paid", Money.format(paid)).put("supplier_remaining", Money.format(original-paid))
            }.onFailure { entry.put("warning", "قيم الالتزام أو الدفعات تحتاج مراجعة؛ الأصل محفوظ دون افتراض رصيد") }
        }
        return entry
    }
    internal fun legacyClassification(t: JSONObject): String = classifyLegacy(t)
    private fun pendingLegacy(db: SQLiteDatabase): List<String> {
        val archive = query(db, "SELECT json FROM legacy_archive WHERE name='transaction'") { it.string("json") }.firstOrNull() ?: return emptyList()
        val ids = JSONArray(archive).objects().filter { classifyLegacy(it) in listOf("SUPPLIER", "AMBIGUOUS") }.map { "legacy:${it.getString("id")}" }.toSet()
        return query(db, "SELECT id FROM invoices WHERE excluded=0") { it.string("id") }.filter { it in ids }
    }
    private fun ensureLegacyReviewed(db: SQLiteDatabase) {
        require(pendingLegacy(db).isEmpty()) { "يوجد ترحيل قديم خاطئ؛ استخدم مراجعة وتصحيح الترحيل قبل إضافة حركات مالية" }
    }
    /** Explicit owner action only. Preserve the original invoice/receipts and quarantine their effects. */
    @Synchronized fun repairLegacySupplierImports(): String {
        val archive = JSONObject(legacyArchive())
        val oldRows = archive.optJSONArray("transaction") ?: return "لا يوجد أرشيف ترحيل سابق للمراجعة"
        val candidates = oldRows.objects().filter { legacyClassification(it) in listOf("SUPPLIER", "AMBIGUOUS") }
        val db = readableDatabase
        val targets = candidates.filter { t -> query(db, "SELECT id FROM invoices WHERE id=? AND excluded=0", "legacy:${t.getString("id")}") { it.string("id") }.isNotEmpty() }
        if (targets.isEmpty()) return "لا توجد فواتير مرحّلة تحتاج تصحيحًا؛ لم تتكرر المعالجة"
        targets.forEach { t ->
            val key = "legacy:${t.getString("id")}"
            val i = invoices(db, true).first { it.id == key }
            val payments = query(db, "SELECT token FROM payments WHERE invoice_id=?", key) { it.string("token") }
            require(i.state == "ISSUED" && i.credit == 0L && items(db, true).filter { it.invoiceId == key }.all { !it.tracked } && payments.all { it.startsWith("legacy:") }) { "الفاتورة ${i.number} عُدلت أو حُصلت بعد الترحيل؛ يلزم تدقيق يدوي قبل عزلها" }
        }
        writeSafety("before-correction")
        return atomic { transaction ->
            val review = archive.optJSONArray("commerce-migration-review") ?: JSONArray()
            targets.forEach { t ->
                val key = "legacy:${t.getString("id")}"
                transaction.update("invoices", values("excluded" to 1), "id=?", arrayOf(key))
                put(transaction, "operations", "token" to "legacy-repair:$key", "result" to key)
                review.put(legacyReviewEntry(t, archive, "عُزل الأثر الخاطئ مع حفظ أصل الفاتورة والدفعات").put("invoice", key))
                audit(transaction, "LEGACY_SUPPLIER_QUARANTINE", key, "عزل من المبيعات وذمم العملاء؛ الأصل محفوظ؛ ${t}")
            }
            put(transaction, "legacy_archive", "name" to "commerce-migration-review", "json" to review.toString(), replace = true)
            validate(transaction)
            "عُزلت ${targets.size} فاتورة خاطئة؛ الأصل محفوظ في الأرشيف ومسار المراجعة"
        }
    }
    fun migrationReview(): String = query(readableDatabase, "SELECT json FROM legacy_archive WHERE name='commerce-migration-review'") { it.string("json") }.firstOrNull() ?: "[]"
    fun legacyArchive(): String = JSONObject().apply {
        query(readableDatabase, "SELECT * FROM legacy_archive") { it.string("name") to it.string("json") }.forEach { (name, json) -> put(name, JSONArray(json)) }
    }.toString(2)

    fun safetyBackupNames(): List<String> = java.io.File(appContext.filesDir, "commerce-backups").listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") && (it.name.startsWith("before-restore-") || it.name.startsWith("before-migration-") || it.name.startsWith("before-correction-")) }
        .sortedByDescending { it.lastModified() }.map { it.name }
    fun safetyBackupFile(name: String): java.io.File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name in safetyBackupNames()) { "نسخة الأمان غير موجودة" }
        return java.io.File(appContext.filesDir, "commerce-backups/$name")
    }

    companion object {
        val tables = listOf("settings", "sequences", "products", "customers", "invoices", "invoice_items", "payments", "credit_notes", "financial_transactions", "operations", "audit_logs", "legacy_archive")
        private val schema = listOf(
            "CREATE TABLE settings(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)",
            "CREATE TABLE sequences(name TEXT PRIMARY KEY NOT NULL,value INTEGER NOT NULL CHECK(value>=0))",
            "CREATE TABLE products(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,sku TEXT NOT NULL UNIQUE,barcode TEXT UNIQUE,category TEXT NOT NULL,type TEXT NOT NULL,description TEXT NOT NULL,price INTEGER NOT NULL CHECK(price>=0),cost INTEGER CHECK(cost>=0),unit TEXT NOT NULL,track_stock INTEGER NOT NULL,stock TEXT NOT NULL,minimum TEXT NOT NULL,notes TEXT NOT NULL,active INTEGER NOT NULL)",
            "CREATE TABLE customers(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,phone TEXT NOT NULL,address TEXT NOT NULL,type TEXT NOT NULL,notes TEXT NOT NULL)",
            "CREATE TABLE invoices(id TEXT PRIMARY KEY NOT NULL,token TEXT NOT NULL UNIQUE,number TEXT NOT NULL UNIQUE,customer_id TEXT REFERENCES customers(id),customer_name TEXT NOT NULL,created INTEGER NOT NULL,due INTEGER,subtotal INTEGER NOT NULL CHECK(subtotal>=0),discount INTEGER NOT NULL CHECK(discount>=0),tax_rate TEXT NOT NULL,tax INTEGER NOT NULL CHECK(tax>=0),total INTEGER NOT NULL CHECK(total>=0),method TEXT NOT NULL,notes TEXT NOT NULL,excluded INTEGER NOT NULL DEFAULT 0 CHECK(excluded IN (0,1)),state TEXT NOT NULL CHECK(state IN ('ISSUED','CANCELLED','RETURNED')),currency TEXT NOT NULL)",
            "CREATE TABLE invoice_items(id INTEGER PRIMARY KEY AUTOINCREMENT,invoice_id TEXT NOT NULL REFERENCES invoices(id),product_id TEXT NOT NULL REFERENCES products(id),name TEXT NOT NULL,sku TEXT NOT NULL,quantity TEXT NOT NULL,unit TEXT NOT NULL,price INTEGER NOT NULL CHECK(price>=0),cost INTEGER,total INTEGER NOT NULL CHECK(total>=0),returned TEXT NOT NULL,tracked INTEGER NOT NULL)",
            "CREATE INDEX items_invoice ON invoice_items(invoice_id)",
            "CREATE INDEX invoices_customer ON invoices(customer_id)",
            "CREATE INDEX invoices_created ON invoices(created)",
            "CREATE TABLE payments(id TEXT PRIMARY KEY NOT NULL,token TEXT NOT NULL UNIQUE,receipt TEXT NOT NULL UNIQUE,invoice_id TEXT NOT NULL REFERENCES invoices(id),amount INTEGER NOT NULL CHECK(amount>0),created INTEGER NOT NULL,method TEXT NOT NULL,notes TEXT NOT NULL,origin TEXT NOT NULL CHECK(origin IN ('INVOICE_PAYMENT','DEBT_COLLECTION')))",
            "CREATE INDEX payments_invoice ON payments(invoice_id)",
            "CREATE TABLE credit_notes(id TEXT PRIMARY KEY NOT NULL,token TEXT NOT NULL UNIQUE,invoice_id TEXT NOT NULL REFERENCES invoices(id),amount INTEGER NOT NULL CHECK(amount>=0),refund INTEGER NOT NULL CHECK(refund>=0),created INTEGER NOT NULL,reason TEXT NOT NULL,quantities TEXT NOT NULL DEFAULT '{}')",
            "CREATE INDEX credits_invoice ON credit_notes(invoice_id)",
            "CREATE TABLE financial_transactions(id TEXT PRIMARY KEY NOT NULL,invoice_id TEXT REFERENCES invoices(id),kind TEXT NOT NULL CHECK(kind IN ('SALE','RETURN','INVOICE_PAYMENT','DEBT_COLLECTION','OTHER_INCOME','REFUND')),amount INTEGER NOT NULL,created INTEGER NOT NULL,source TEXT NOT NULL,method TEXT NOT NULL,notes TEXT NOT NULL)",
            "CREATE INDEX movements_created ON financial_transactions(created)",
            "CREATE INDEX movements_invoice ON financial_transactions(invoice_id)",
            "CREATE TABLE operations(token TEXT PRIMARY KEY NOT NULL,result TEXT NOT NULL)",
            "CREATE TABLE audit_logs(id INTEGER PRIMARY KEY AUTOINCREMENT,created INTEGER NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,details TEXT NOT NULL)",
            "CREATE TABLE legacy_archive(name TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL)"
        )
        internal fun values(vararg pairs: Pair<String, Any?>): ContentValues = ContentValues().apply { pairs.forEach { (key, value) -> when (value) {
            null -> putNull(key)
            is Long -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, if (value) 1 else 0)
            else -> put(key, value.toString())
        } } }
        internal fun put(db: SQLiteDatabase, table: String, vararg pairs: Pair<String, Any?>, replace: Boolean = false): Long = db.insertWithOnConflict(table, null, values(*pairs), if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_ABORT).also { require(it != -1L) { "تعذر حفظ البيانات" } }
        internal fun <T> query(db: SQLiteDatabase, sql: String, vararg args: String?, map: (Cursor) -> T): List<T> = db.rawQuery(sql, if (args.isEmpty()) null else args).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
        internal fun Cursor.string(name: String): String = getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }
        internal fun Cursor.nullString(name: String): String? = string(name).takeIf { it.isNotBlank() }
        internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
        internal fun Cursor.nullLong(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
    }
}
