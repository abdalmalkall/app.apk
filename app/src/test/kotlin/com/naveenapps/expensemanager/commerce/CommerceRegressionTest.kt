package com.naveenapps.expensemanager.commerce

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors

/** Read-only audit diagnostics against disposable Robolectric databases. Regression assertions require correct behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
class CommerceRegressionTest {
    private lateinit var context: Context
    private lateinit var store: CommerceStore
    private lateinit var name: String
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); name = "audit-${UUID.randomUUID()}.db"; store = CommerceStore(context, name); store.saveProduct(Product(id="sand-1", name="رمل 1", sku="SAND-1", price=12000, cost=6000, trackStock=true, stock="100")) }
    @After fun cleanup() { store.close(); context.deleteDatabase(name); context.deleteDatabase("expense_manager_database.db") }
    private fun sale(paid:Long=25000):Pair<String,String> { val c=store.saveCustomer(Customer(name="عميل تدقيق")); val i=store.issueInvoice(UUID.randomUUID().toString(),c,listOf(DraftLine("sand-1","5",12000,"طن")),0,"0",paid,"نقدي",""); return i to c }
    private fun rejectWithoutChange(text:String) { val before=store.snapshot(); try { store.restore(text); fail("Invalid backup must be rejected") } catch(e:Exception) { }; assertEquals(before,store.snapshot()) }
    @Test fun restoreRejectsSaleWithoutInvoice() {
        val backup=JSONObject(store.backup()); backup.getJSONObject("tables").getJSONArray("financial_transactions").put(JSONObject().put("id","orphan-sale").put("invoice_id",JSONObject.NULL).put("kind","SALE").put("amount",60000).put("created",1).put("source","مبيعات").put("method","نقدي").put("notes",""))
        rejectWithoutChange(backup.toString()); assertTrue(store.snapshot().invoices.isEmpty()); assertEquals(0L,store.snapshot().sales())
    }
    @Test fun restoreRejectsFractionalMoneyEncodedAsString() {
        store.addIncome("income",4500,"خدمات","نقدي","",1)
        val backup=JSONObject(store.backup()); backup.getJSONObject("tables").getJSONArray("financial_transactions").getJSONObject(0).put("amount","4500.9")
        rejectWithoutChange(backup.toString()); assertEquals(4500L,store.snapshot().cash())
        assertEquals(4500L,JSONObject(store.backup()).getJSONObject("tables").getJSONArray("financial_transactions").getJSONObject(0).getLong("amount"))
    }
    @Test fun refundUsesExplicitActualMethodAfterBankCollection() {
        val (invoice,c)=sale(0); store.collectDebt("bank",c,60000,"تحويل بنكي","",invoice); store.returnItems("cancel",invoice,emptyMap(),"تدقيق",true,"تحويل بنكي")
        val s=store.snapshot(); assertEquals(0L,s.cash()); assertEquals(0L,s.sales()); assertEquals(0L,s.debt)
        assertEquals("تحويل بنكي",s.movements.single { it.kind=="REFUND" }.method)
        assertEquals(60000L,s.movements.filter { it.method=="تحويل بنكي" && it.kind=="DEBT_COLLECTION" }.sumOf { it.amount })
    }
    @Test fun safetyCopyCanActuallyRestorePreviousBookAndReopen() {
        val old=store.backup(); sale(); val original=store.snapshot(); store.restore(old)
        val safety=store.safetyBackupFile(store.safetyBackupNames().first()).readText(); store.restore(safety)
        assertEquals(original.invoices,store.snapshot().invoices); assertEquals(35000L,store.snapshot().debt)
        store.close(); store=CommerceStore(context,name); assertEquals(60000L,store.snapshot().sales()); assertEquals(25000L,store.snapshot().cash()); assertEquals(35000L,store.snapshot().debt)
    }
    @Test fun incompatibleAndInvalidJsonDoNotReplaceLiveBook() {
        sale(); val s=store.snapshot()
        for (bad in listOf("{broken",JSONObject(store.backup()).put("version",3).toString())) {
            try { store.restore(bad); fail("must reject") } catch(e:Exception) { }
            assertEquals(s,store.snapshot())
        }
    }
    @Test fun repeatedAndConcurrentSavesKeepUniqueNumbersAndReceipts() {
        val c=store.saveCustomer(Customer(name="عميل")); val pool=Executors.newFixedThreadPool(4)
        try { val futures=(1..25).map { n -> pool.submit<String> { store.issueInvoice("stress-$n",c,listOf(DraftLine("sand-1","1",12000,"طن")),0,"0",12000,"نقدي","") } }; futures.forEach { it.get() } } finally { pool.shutdownNow() }
        val s=store.snapshot(); assertEquals(25,s.invoices.size); assertEquals(25,s.invoices.map { it.number }.distinct().size); assertEquals(25,s.payments.map { it.receipt }.distinct().size); assertEquals("75",s.products.first { it.id=="sand-1" }.stock); assertEquals(300000L,s.sales()); assertEquals(300000L,s.cash())
    }
    @Test fun historicalCostAndStatementBalanceStayStableAfterEditsAndReturn() {
        val (invoice,c)=sale(); store.collectDebt("ten",c,10000,"نقدي","",invoice)
        store.saveProduct(store.snapshot().products.first { it.id=="sand-1" }.copy(name="اسم جديد",price=99000,cost=90000))
        assertEquals(6000L,store.snapshot().items.single().cost)
        store.returnItems("one",invoice,mapOf(store.snapshot().items.single().id to "1"),"مرتجع")
        val s=store.snapshot(); val deltas=s.movements.filter { it.invoiceId==invoice }.sumOf { if(it.kind in listOf("SALE","RETURN")) it.amount else -it.amount }; assertEquals(s.balance(c),deltas); assertEquals(13000L,deltas)
    }
    @Test fun preMigrationBackupIncludesOldDatabaseRecords() {
        val path=context.getDatabasePath("expense_manager_database.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { it.execSQL("CREATE TABLE legacy_only(secret TEXT)"); it.execSQL("INSERT INTO legacy_only VALUES('ARCHIVE_SENTINEL')") }
        val backup=JSONObject(store.backup()); assertEquals(0,backup.getJSONObject("tables").getJSONArray("legacy_archive").length()); assertTrue(backup.getJSONObject("payload").getJSONObject("legacy").toString().contains("ARCHIVE_SENTINEL"))
    }
    @Test fun supplierDebtMigrationDoesNotBecomeCustomerSale() {
        val path=context.getDatabasePath("expense_manager_database.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            db.execSQL("CREATE TABLE supplier(id TEXT,name TEXT)"); db.execSQL("INSERT INTO supplier VALUES('supplier-1','مورد رمل')")
            db.execSQL("CREATE TABLE \"transaction\"(id TEXT,type INTEGER,invoice_number TEXT,amount_decimal TEXT,created_on INTEGER,supplier_id TEXT,supplier_name TEXT)")
            db.execSQL("INSERT INTO \"transaction\" VALUES('purchase',3,'PURCHASE-1','60.000',1,'supplier-1','مورد رمل')")
            db.execSQL("CREATE TABLE supplier_payment(id TEXT,invoice_transaction_id TEXT,amount_decimal TEXT,paid_on INTEGER)")
            db.execSQL("INSERT INTO supplier_payment VALUES('sp','purchase','25.000',2)")
        }
        store.migrateLegacy(); val s=store.snapshot()
        assertEquals(0L,s.sales()); assertEquals(0L,s.debt); assertEquals(0L,s.cash()); assertEquals(0,s.customers.size)
        assertTrue(s.invoices.isEmpty()); assertTrue(store.migrationReview().contains("SUPPLIER")); assertEquals("35.000",org.json.JSONArray(store.migrationReview()).getJSONObject(0).getString("supplier_remaining")); assertTrue(store.legacyArchive().contains("supplier_payment"))
    }

}
