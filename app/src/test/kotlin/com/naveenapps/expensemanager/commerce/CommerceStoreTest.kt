package com.naveenapps.expensemanager.commerce

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
class CommerceStoreTest {
    private lateinit var context: Context
    private lateinit var store: CommerceStore
    private lateinit var databaseName: String
    private lateinit var customer: String
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "test-${UUID.randomUUID()}.db"
        store = CommerceStore(context, databaseName)
        store.saveProduct(Product(id = "sand-1", name = "رمل 1", sku = "SAND-1", barcode = "1234567890128", price = 12000, cost = 6000, unit = "طن", trackStock = true, stock = "100"))
        customer = store.saveCustomer(Customer(name = "أحمد", phone = "0790000000"))
    }
    @After fun tearDown() { store.close(); context.deleteDatabase(databaseName); context.deleteDatabase("expense_manager_database.db") }
    private fun issue(paid: Long, token: String = UUID.randomUUID().toString(), quantity: String = "5", c: String? = customer): String = store.issueInvoice(token, c, listOf(DraftLine("sand-1", quantity, 12000, "طن")), 0, "0", paid, "نقدي", "")
    private fun fails(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (e: Exception) { /* expected */ } }

    @Test fun fullCashInvoiceHasNoDebtAndSurvivesReopening() {
        val id = issue(60000, c = null)
        val before = store.snapshot()
        assertEquals(60000L, before.sales()); assertEquals(60000L, before.cash()); assertEquals(0L, before.debt)
        assertEquals("مدفوعة", before.invoices.single().status)
        store.close(); store = CommerceStore(context, databaseName)
        assertEquals(before, store.snapshot()); assertEquals(id, store.snapshot().invoices.single().id)
    }
    @Test fun partialSaleAndCollectionsNeverDoubleCountRevenue() {
        val invoice = issue(25000)
        assertEquals(60000L, store.snapshot().sales()); assertEquals(25000L, store.snapshot().cash()); assertEquals(35000L, store.snapshot().debt)
        assertEquals("جزئية", store.snapshot().invoices.single().status)
        val payments = store.collectDebt("first-payment", customer, 10000, "تحويل بنكي", "", invoice)
        assertEquals(payments, store.collectDebt("first-payment", customer, 10000, "تحويل بنكي", "", invoice))
        assertEquals(60000L, store.snapshot().sales()); assertEquals(35000L, store.snapshot().cash()); assertEquals(25000L, store.snapshot().debt)
        store.collectDebt("settle", customer, 25000, "نقدي", "")
        val s = store.snapshot(); assertEquals(60000L, s.sales()); assertEquals(60000L, s.cash()); assertEquals(0L, s.debt)
        assertEquals(3, s.payments.size); assertEquals("مدفوعة", s.invoices.single().status)
    }
    @Test fun pureCreditSaleHasNoCashAndPaymentAllocatesOldestFirst() {
        val first = issue(0)
        Thread.sleep(2)
        val second = issue(0, quantity = "2.5")
        assertEquals(90000L, store.snapshot().sales()); assertEquals(0L, store.snapshot().cash())
        assertEquals("غير مدفوعة", store.snapshot().invoices.first().status)
        val receipts = store.collectDebt("allocate", customer, 75000, "نقدي", "")
        assertEquals(2, receipts.size)
        val s = store.snapshot()
        assertEquals(0L, s.invoices.first { it.id == first }.remaining)
        assertEquals(15000L, s.invoices.first { it.id == second }.remaining)
        assertEquals(90000L, s.sales()); assertEquals(75000L, s.cash()); assertEquals(15000L, s.debt)
    }
    @Test fun multiProductDecimalQuantitiesDiscountAndTaxAreExact() {
        store.saveProduct(Product(id = "sand-2", name = "رمل 2", sku = "SAND-2", price = 8000, unit = "متر مكعب"))
        val invoiceId = store.issueInvoice("multi", customer, listOf(DraftLine("sand-1", "2.5", 12000, "طن"), DraftLine("sand-2", "3.75", 8000, "متر مكعب")), 5000, "16", 25000, "نقدي", "")
        val s = store.snapshot(); val i = s.invoices.single()
        assertEquals(invoiceId, i.id); assertEquals(60000L, i.subtotal); assertEquals(8800L, i.tax); assertEquals(63800L, i.total); assertEquals(38800L, i.remaining)
        assertEquals(2, s.items.size); assertEquals("97.5", s.products.first { it.id == "sand-1" }.stock)
        store.saveProduct(s.products.first { it.id == "sand-1" }.copy(name = "رمل مغسول", price = 99000))
        assertEquals("رمل 1", store.snapshot().items.first().name); assertEquals(12000L, store.snapshot().items.first().price)
    }
    @Test fun duplicateInvoiceAndConcurrentOverpaymentAreSafe() {
        val id = issue(0, "repeated")
        assertEquals(id, issue(0, "repeated")); assertEquals(1, store.snapshot().invoices.size)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { n -> pool.submit<Boolean> { try { store.collectDebt("concurrent-$n", customer, 40000, "نقدي", ""); true } catch (e: IllegalArgumentException) { false } } }
            assertEquals(1, jobs.count { it.get() })
        } finally { pool.shutdownNow() }
        assertEquals(20000L, store.snapshot().debt); assertEquals(40000L, store.snapshot().cash())
    }
    @Test fun invalidInvoicesAndOverpaymentsRollbackAllRecordsAndStock() {
        val before = store.backup()
        fails { issue(60001) }; fails { issue(0, c = null) }; fails { issue(0, quantity = "101") }
        fails { store.issueInvoice("bad-product", customer, listOf(DraftLine("missing", "1", 1000, "طن")), 0, "0", 0, "نقدي", "") }
        assertEquals(0, store.snapshot().invoices.size); assertEquals("100", store.snapshot().products.first { it.id == "sand-1" }.stock)
        issue(25000)
        val s = store.snapshot()
        fails { store.collectDebt("over", customer, 35001, "نقدي", "") }
        fails { store.collectDebt("negative", customer, -1, "نقدي", "") }
        assertEquals(s, store.snapshot())
    }
    @Test fun partialReturnAndCancellationRefundExactlyAndKeepAudit() {
        val invoice = issue(60000)
        val line = store.snapshot().items.single()
        store.returnItems("return-one", invoice, mapOf(line.id to "2"), "إرجاع كمية")
        val s = store.snapshot()
        assertEquals(36000L, s.sales()); assertEquals(36000L, s.cash()); assertEquals(0L, s.debt)
        assertEquals(24000L, s.invoices.single().credit); assertEquals("97", s.products.first { it.id == "sand-1" }.stock)
        store.returnItems("return-one", invoice, mapOf(line.id to "2"), "إرجاع كمية")
        assertEquals(s, store.snapshot())
        store.returnItems("cancel-rest", invoice, emptyMap(), "إلغاء المتبقي", true)
        val end = store.snapshot()
        assertEquals(0L, end.sales()); assertEquals(0L, end.cash()); assertEquals(0L, end.debt)
        assertEquals("100", end.products.first { it.id == "sand-1" }.stock)
        assertEquals("ملغاة", end.invoices.single().status); assertEquals(1, end.invoices.size); assertEquals(1, end.payments.size)
        assertTrue(end.audit.any { it.action == "INVOICE_CANCEL" })
    }
    @Test fun returnUnpaidDebtReducesReceivableWithoutInventingCash() {
        val invoice = issue(25000)
        val item = store.snapshot().items.single()
        store.returnItems("return-debt", invoice, mapOf(item.id to "2"), "مرتجع")
        assertEquals(36000L, store.snapshot().sales()); assertEquals(25000L, store.snapshot().cash()); assertEquals(11000L, store.snapshot().debt)
        fails { store.returnItems("too-much", invoice, mapOf(item.id to "4"), "مرتجع") }
        assertEquals(11000L, store.snapshot().debt)
        store.returnItems("all", invoice, emptyMap(), "إلغاء", true)
        assertEquals(0L, store.snapshot().cash()); assertEquals(0L, store.snapshot().debt)
    }
    @Test fun backupRestoresCompleteLedgerAndRejectsCorruptFileWithoutDataLoss() {
        issue(25000); store.collectDebt("receipt", customer, 10000, "نقدي", "")
        store.addIncome("income", 4500, "خدمات", "نقدي", "", System.currentTimeMillis())
        val expected = store.snapshot()
        val backup = store.backup()
        val cloneName = "clone-${UUID.randomUUID()}.db"
        val clone = CommerceStore(context, cloneName)
        try {
            val originalTables = JSONObject(clone.backup()).getJSONObject("tables").toString()
            clone.restore(backup)
            assertTrue(clone.safetyBackupNames().isNotEmpty())
            assertEquals(originalTables, JSONObject(clone.safetyBackupFile(clone.safetyBackupNames().first()).readText()).getJSONObject("tables").toString())
            fails { clone.safetyBackupFile("../commerce_v1.db") }
            assertEquals(expected.invoices, clone.snapshot().invoices); assertEquals(expected.products, clone.snapshot().products)
            assertEquals(expected.cash(), clone.snapshot().cash()); assertEquals(expected.debt, clone.snapshot().debt)
            val bad = JSONObject(backup)
            bad.getJSONObject("tables").getJSONArray("invoices").getJSONObject(0).put("total", 1)
            fails { clone.restore(bad.toString()) }
            assertEquals(expected.invoices, clone.snapshot().invoices)
            clone.issueInvoice("next", customer, listOf(DraftLine("sand-1", "1", 12000, "طن")), 0, "0", 12000, "نقدي", "")
            assertEquals(2, clone.snapshot().invoices.map { it.number }.distinct().size)
        } finally { clone.close(); context.deleteDatabase(cloneName) }
    }
    @Test fun incomeIdempotencyBarcodeUniquenessAndCurrencyProtection() {
        store.addIncome("income", 10000, "دخل آخر", "نقدي", "", 1)
        store.addIncome("income", 10000, "دخل آخر", "نقدي", "", 1)
        assertEquals(0L, store.snapshot().sales()); assertEquals(10000L, store.snapshot().cash())
        assertEquals("sand-1", store.snapshot().products.first { it.barcode == "1234567890128" }.id)
        fails { store.saveProduct(Product(name = "مكرر", sku = "NEW", barcode = "1234567890128")) }
        fails { store.saveSettings(store.snapshot().settings + ("currency" to "USD")) }
    }
    @Test fun migrationArchivesLegacyDataPreservesDebtsAndIsIdempotent() {
        val path = context.getDatabasePath("expense_manager_database.db"); path.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL("CREATE TABLE customer(id TEXT,name TEXT,phone TEXT,address TEXT,notes TEXT)")
        db.execSQL("INSERT INTO customer VALUES('c','عميل قديم','079','','')")
        db.execSQL("CREATE TABLE category(id TEXT,name TEXT)"); db.execSQL("INSERT INTO category VALUES('cat','رمل قديم')")
        db.execSQL("CREATE TABLE \"transaction\"(id TEXT,type INTEGER,invoice_number TEXT,amount_decimal TEXT,amount REAL,customer_id TEXT,customer_name TEXT,category_id TEXT,created_on INTEGER,notes TEXT,quantity REAL,unit TEXT,unit_price REAL)")
        db.execSQL("INSERT INTO \"transaction\" VALUES('debt',3,'OLD-1','60.000',60,'c','عميل قديم','cat',1000,'قديم',5,'طن',12)")
        db.execSQL("INSERT INTO \"transaction\" VALUES('cash',0,'OLD-2','20.000',20,NULL,'نقدي','cat',1000,'',2,NULL,NULL)")
        db.execSQL("INSERT INTO \"transaction\" VALUES('income',0,NULL,'5.000',5,NULL,'','cat',1000,'',NULL,NULL,NULL)")
        db.execSQL("INSERT INTO \"transaction\" VALUES('expense',1,NULL,'9.000',9,NULL,'','cat',1000,'',NULL,NULL,NULL)")
        db.execSQL("CREATE TABLE customer_payment(id TEXT,invoice_transaction_id TEXT,amount_decimal TEXT,amount REAL,paid_on INTEGER,notes TEXT)")
        db.execSQL("INSERT INTO customer_payment VALUES('p','debt','25.000',25,1001,'دفعة')")
        db.close()
        store.migrateLegacy()
        val s = store.snapshot()
        assertEquals(80000L, s.sales()); assertEquals(50000L, s.cash()); assertEquals(35000L, s.debt)
        assertEquals("5", s.items.first { it.invoiceId == "legacy:debt" }.quantity); assertEquals("طن", s.items.first { it.invoiceId == "legacy:debt" }.unit); assertEquals("سجل", s.items.first { it.invoiceId == "legacy:cash" }.unit)
        assertEquals(2, s.invoices.size); assertEquals(4, JSONObject(store.legacyArchive()).getJSONArray("transaction").length())
        store.migrateLegacy(); assertEquals(s.invoices, store.snapshot().invoices)
        assertTrue(path.exists())
        val cloneName = "migration-clone.db"; val clone = CommerceStore(context, cloneName)
        try {
            // This migration fixture is a partial legacy schema, not a restorable Room database.
            val book = JSONObject(store.backup()); book.getJSONObject("payload").put("legacy", JSONObject.NULL)
            clone.restore(book.toString()); assertEquals(s.debt, clone.snapshot().debt)
        } finally { clone.close(); context.deleteDatabase(cloneName) }
    }
    @Test fun invoiceRevisionIsAtomicRetainsOriginalAndOnlyChangesCashByDifference() {
        val original = issue(25000)
        val replacement = store.reviseInvoice("revise", original, customer, listOf(DraftLine("sand-1", "4", 12000, "طن")), 0, "0", 25000, "نقدي", "تصحيح", null)
        val s = store.snapshot()
        assertEquals(48000L, s.sales()); assertEquals(25000L, s.cash()); assertEquals(23000L, s.debt)
        assertEquals("ملغاة", s.invoices.first { it.id == original }.status)
        assertEquals("96", s.products.first { it.id == "sand-1" }.stock)
        assertEquals(replacement, store.reviseInvoice("revise", original, customer, listOf(DraftLine("sand-1", "4", 12000, "طن")), 0, "0", 25000, "نقدي", "تصحيح", null))
        assertEquals(2, store.snapshot().invoices.size)
        val before = store.snapshot()
        fails { store.reviseInvoice("bad-revision", replacement, customer, listOf(DraftLine("sand-1", "101", 12000, "طن")), 0, "0", 25000, "نقدي", "", null) }
        assertEquals(before, store.snapshot())
    }
    @Test fun stockReturnsUseOriginalTrackingAndFreeInvoicesCanBeCancelled() {
        val invoice = issue(0, quantity = "1")
        val p = store.snapshot().products.first { it.id == "sand-1" }
        store.saveProduct(p.copy(trackStock = false))
        store.returnItems("cancel", invoice, emptyMap(), "إلغاء", true)
        assertEquals("100", store.snapshot().products.first { it.id == "sand-1" }.stock)
        store.saveProduct(p.copy(trackStock = true, stock = "100"))
        val free = store.issueInvoice("free", null, listOf(DraftLine("sand-1", "1", 0, "طن")), 0, "0", 0, "نقدي", "")
        store.returnItems("free-return", free, emptyMap(), "إلغاء مجاني", true)
        assertEquals("100", store.snapshot().products.first { it.id == "sand-1" }.stock)
    }
    @Test fun productAllocationsReconcileAfterReturnsAndExcludeTaxFromProfit() {
        store.saveProduct(Product(id = "sand-2", name = "رمل 2", sku = "SAND-2", price = 8000, cost = 3000))
        val invoice = store.issueInvoice("stats", customer, listOf(DraftLine("sand-1", "1", 12000, "طن"), DraftLine("sand-2", "1", 8000, "طن")), 2000, "16", 10000, "نقدي", "")
        val s = store.snapshot()
        val stats = s.products.map { CommerceAnalytics.product(s, it.id) }
        assertEquals(s.sales(), stats.sumOf { it.grossSales }); assertEquals(s.debt, stats.sumOf { it.receivable })
        assertEquals(9000L, stats.sumOf { it.cost ?: 0 }); assertEquals(9000L, stats.sumOf { it.profit ?: 0 })
        val item = s.items.first { it.productId == "sand-1" }
        store.returnItems("stats-return", invoice, mapOf(item.id to "0.5"), "مرتجع")
        val after = store.snapshot(); val totals = after.products.map { CommerceAnalytics.product(after, it.id) }
        assertEquals(after.sales(), totals.sumOf { it.grossSales }); assertEquals(after.debt, totals.sumOf { it.receivable })
        fails { store.saveProduct(after.products.first { it.id == "sand-1" }.copy(unit = "كيس")) }
    }
    @Test fun preciseArabicInputAndRoundingAtMilliunitBoundary() {
        assertEquals(12345L, Money.parse("١٢٫٣٤٥"))
        assertEquals(101L, Money.line("0.335", 300))
        assertEquals(0L, Money.line("0.000001", 1))
        assertEquals(34999L, Money.totals(listOf(30000), 1, "16.667").total)
        fails { Money.parse("0.0001") }; fails { Money.line("-1", 10) }; fails { Money.totals(listOf(100), 101, "0") }
    }
}
