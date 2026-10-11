package com.naveenapps.expensemanager.commerce

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,manifest=Config.NONE,sdk=[34])
class CommerceBackupTest {
 private lateinit var context:Context; private lateinit var store:CommerceStore; private lateinit var name:String
 @Before fun setup() { context=ApplicationProvider.getApplicationContext(); name="backup-${UUID.randomUUID()}.db"; store=CommerceStore(context,name); store.saveProduct(Product(id="sand-1",name="رمل 1",sku="SAND-1",price=12000,trackStock=true,stock="100")) }
 @After fun cleanup() { store.close(); context.deleteDatabase(name); context.deleteDatabase("expense_manager_database.db") }
 private fun sale(paid:Long=25000):String { val c=store.saveCustomer(Customer(name="عميل")); return store.issueInvoice("invoice",c,listOf(DraftLine("sand-1","5",12000,"طن")),0,"0",paid,"نقدي","") }
 private fun rejects(root:JSONObject) { val before=store.snapshot(); val files=allFiles(); try { store.restore(root.toString()); fail("must reject") } catch(e:Exception) { }; assertEquals(before,store.snapshot()); assertEquals(files,allFiles()) }
 private fun allFiles():Map<String,String> = context.filesDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(context.filesDir).path to CommerceBackup.sha(it.readBytes()) }
 private fun oldDb():SQLiteDatabase {
  val file=context.getDatabasePath("expense_manager_database.db"); file.parentFile!!.mkdirs()
  if(!file.exists()) {
   val room=androidx.room.Room.databaseBuilder(context,com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase::class.java,"expense_manager_database.db").allowMainThreadQueries().build()
   room.openHelper.writableDatabase; room.close()
  }
  return SQLiteDatabase.openOrCreateDatabase(file,null)
 }
 @Test fun fullBackupRestoresCommittedWalOldAndNewDataAndPortableAttachmentAndLogo() {
  sale(); val file=File(context.filesDir,"category_images/a.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
  oldDb().use { db -> db.enableWriteAheadLogging(); db.execSQL("INSERT INTO category(id,name,type,icon_background_color,icon_name,updated_on,created_on,custom_image_path) VALUES('old','صنف قديم',0,'','',1,1,?)",arrayOf(file.path)) }
  val logo="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3mQAAAAASUVORK5CYII="
  store.saveSettings(store.snapshot().settings + mapOf("logo" to logo,"theme" to "dark"))
  val backup=store.backup(); val root=JSONObject(backup)
  assertEquals("backup-file:category_images/a.png",root.getJSONObject("payload").getJSONObject("legacy").getJSONObject("tables").getJSONArray("category").getJSONObject(0).getString("custom_image_path"))
  file.writeBytes(byteArrayOf(9)); oldDb().use { it.execSQL("DELETE FROM category") }; store.addIncome("later",1000,"آخر","نقدي","",1)
  assertTrue(store.restore(backup)); assertArrayEquals(byteArrayOf(1,2,3,4),file.readBytes()); assertEquals(60000L,store.snapshot().sales()); assertEquals(25000L,store.snapshot().cash()); assertEquals(logo,store.snapshot().settings["logo"])
  oldDb().use { db -> db.rawQuery("SELECT custom_image_path FROM category WHERE id='old'",null).use { assertTrue(it.moveToFirst()); assertEquals(file.path,it.getString(0)) } }
 }
 @Test fun corruptedMissingAndTraversalFilesAreRejectedBeforeAnyChange() {
  sale(); File(context.filesDir,"category_images/a.png").apply { parentFile!!.mkdirs(); writeText("attachment") }
  for(change in listOf<(JSONObject)->Unit>({it.put("sha256","0")},{it.put("path","transaction_attachments/../../escape")},{it.put("size",0)})) { val root=JSONObject(store.backup()); change(root.getJSONObject("payload").getJSONArray("files").getJSONObject(0)); rejects(root) }
 }
 @Test fun missingReferencedAttachmentIsRejected() {
  val file=File(context.filesDir,"category_images/a.png").apply { parentFile!!.mkdirs(); writeText("attachment") }
  oldDb().use { db -> db.execSQL("INSERT INTO category(id,name,type,icon_background_color,icon_name,updated_on,created_on,custom_image_path) VALUES('old','صنف قديم',0,'','',1,1,?)",arrayOf(file.path)) }
  val root=JSONObject(store.backup()); root.getJSONObject("payload").put("files",JSONArray()); rejects(root)
 }
 @Test fun restoreFailureRollsBackDatabaseFilesAndLegacyRows() {
  sale(); val file=File(context.filesDir,"category_images/a.png").apply { parentFile!!.mkdirs(); writeText("before") }
  oldDb().use { it.execSQL("CREATE TABLE original(value TEXT)"); it.execSQL("INSERT INTO original VALUES('before')") }
  val incoming=JSONObject(store.backup()); val record=incoming.getJSONObject("payload").getJSONArray("files").getJSONObject(0); val bytes="incoming".toByteArray(); record.put("size",bytes.size).put("sha256",CommerceBackup.sha(bytes)).put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
  val before=store.snapshot(); val manager=CommerceBackup(context,store); manager.failureHook={ error("Injected failure after file replacement") }
  try { manager.restore(incoming.toString()); fail("expected failure") } catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("أُعيدت")); assertTrue(e is CommerceRestoreRestartException) }
  assertEquals(before.invoices,store.snapshot().invoices); assertEquals(before.cash(),store.snapshot().cash()); assertEquals("before",file.readText())
  oldDb().use { it.rawQuery("SELECT value FROM original",null).use { c -> assertTrue(c.moveToFirst()); assertEquals("before",c.getString(0)) } }; assertFalse(File(context.filesDir,"commerce-restore-journal.json").exists())
 }
 @Test fun startupJournalRecoveryReturnsPreviousBookAndFiles() {
  sale(); val file=File(context.filesDir,"category_images/a.png").apply { parentFile!!.mkdirs(); writeText("before") }; val safety=store.writeSafety("before-restore")
  file.writeText("interrupted"); store.addIncome("later",1000,"دخل","نقدي","",1)
  CommerceBackup.writeDurable(File(context.filesDir,"commerce-restore-journal.json"),JSONObject().put("safety",safety.name).toString().toByteArray())
  store.recoverPendingRestore(); assertEquals("before",file.readText()); assertEquals(25000L,store.snapshot().cash()); store.recoverPendingRestore(); assertEquals(1,store.snapshot().invoices.size)
 }
 @Test fun versionOneLedgerRestoresWithoutReplacingLegacyOrFiles() {
  sale(); val root=JSONObject(store.backup()).put("version",1); root.remove("payload")
  root.getJSONObject("tables").getJSONArray("invoices").objects().forEach { it.remove("excluded") }
  root.getJSONObject("tables").getJSONArray("credit_notes").objects().forEach { it.remove("quantities") }
  val file=File(context.filesDir,"category_images/a.png").apply { parentFile!!.mkdirs(); writeText("keep") }
  oldDb().use { it.execSQL("CREATE TABLE keep(value TEXT)"); it.execSQL("INSERT INTO keep VALUES('keep')") }
  assertFalse(store.restore(root.toString())); assertEquals("keep",file.readText()); oldDb().use { it.rawQuery("SELECT value FROM keep",null).use { c -> assertTrue(c.moveToFirst()) } }
 }
 @Test fun malformedFinancialVariantsNeverReplaceLiveData() {
  val invoice=sale(60000); store.returnItems("partial",invoice,mapOf(store.snapshot().items.single().id to "1"),"إرجاع",false,"نقدي")
  val mutations=listOf<Pair<String,(JSONObject)->Unit>>(
   "invoices" to {it.put("total",1)}, "invoices" to {it.put("currency","USD")}, "invoices" to {it.put("state","CANCELLED")}, "invoices" to {it.put("excluded",2)}, "invoices" to {it.put("excluded",1)},
   "invoice_items" to {it.put("quantity","0")}, "invoice_items" to {it.put("returned","99")}, "invoice_items" to {it.put("price","12000")},
   "payments" to {it.put("amount",999999)}, "payments" to {it.put("invoice_id","unknown")}, "payments" to {it.put("method","")},
   "credit_notes" to {it.put("amount",0)}, "credit_notes" to {it.put("refund",0)}, "credit_notes" to {it.put("quantities",JSONObject().put("99999","1").toString())},
   "sequences" to {it.put("value",0)}
  )
  for((table,mutate) in mutations) { val root=JSONObject(store.backup()); mutate(root.getJSONObject("tables").getJSONArray(table).getJSONObject(0)); rejects(root) }
 }
 private fun emulatePriorWrongSupplierImport():String {
  store.saveProduct(store.snapshot().products.first { it.id=="sand-1" }.copy(trackStock=false))
  val c=store.saveCustomer(Customer(id="legacy:transaction:purchase",name="عميل مرحّل")); val id=store.issueInvoice("wrong",c,listOf(DraftLine("sand-1","5",12000,"طن")),0,"0",0,"طريقة غير محددة (مرحّل)","")
  val db=store.writableDatabase; db.setForeignKeyConstraintsEnabled(false)
  db.execSQL("UPDATE invoices SET id='legacy:purchase' WHERE id=?",arrayOf(id)); listOf("invoice_items","financial_transactions").forEach { db.execSQL("UPDATE $it SET invoice_id='legacy:purchase' WHERE invoice_id=?",arrayOf(id)) }; db.setForeignKeyConstraintsEnabled(true)
  val old=JSONObject().put("id","purchase").put("type",3).put("supplier_id","s").put("invoice_number","PUR-1").put("amount_decimal","60.000")
  CommerceStore.put(db,"legacy_archive","name" to "transaction","json" to JSONArray().put(old).toString())
  return c
 }
 @Test fun correctionQuarantinesPreservesOriginalAndDoesNotRepeat() {
  emulatePriorWrongSupplierImport(); assertEquals(60000L,store.snapshot().sales())
  store.repairLegacySupplierImports(); assertEquals(0L,store.snapshot().sales()); assertEquals(0L,store.snapshot().debt); assertTrue(store.snapshot().invoices.isEmpty())
  val tables=JSONObject(store.backup()).getJSONObject("tables"); assertEquals(1,tables.getJSONArray("invoices").length()); assertEquals(1,tables.getJSONArray("invoice_items").length()); assertEquals(1,tables.getJSONArray("financial_transactions").length()); assertEquals(1L,tables.getJSONArray("invoices").getJSONObject(0).getLong("excluded"))
  val before=store.snapshot(); store.repairLegacySupplierImports(); assertEquals(before,store.snapshot()); assertTrue(store.migrationReview().contains("عُزل")); assertTrue(store.safetyBackupNames().any { it.startsWith("before-correction-") }); store.restore(store.backup()); assertEquals(0L,store.snapshot().sales())
 }
 @Test fun correctionRefusesInvoicesWithNewCollectionsWithoutPartialMutation() {
  emulatePriorWrongSupplierImport()
  // Previously saved collection from the buggy version: current APIs must block new work.
  val db=store.writableDatabase
  CommerceStore.put(db,"payments","id" to "new-p","token" to "new-receipt","receipt" to "REC-000001","invoice_id" to "legacy:purchase","amount" to 10000L,"created" to System.currentTimeMillis(),"method" to "نقدي","notes" to "","origin" to "DEBT_COLLECTION")
  val before=store.snapshot(); try { store.repairLegacySupplierImports(); fail("manual review required") } catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("يدوي")) }; assertEquals(before,store.snapshot())
 }
 private fun dbInsertAmbiguous(db:SQLiteDatabase) {
  db.execSQL("INSERT INTO category(id,name,type,icon_background_color,icon_name,updated_on,created_on) VALUES('cat','رمل',0,'','',1,1)")
  db.execSQL("INSERT INTO account(id,name,type,icon_background_color,icon_name,amount,credit_limit,sequence,created_on,updated_on) VALUES('acct','حساب',0,'','',0,0,0,1,1)")
  db.execSQL("INSERT INTO \"transaction\"(id,notes,category_id,from_account_id,type,amount,image_path,created_on,updated_on,amount_decimal) VALUES('unknown','','cat','acct',3,50,'',1,1,'50.000')")
 }
 @Test fun ambiguousLegacyDebtIsPreservedForReviewWithoutInventingCustomer() {
  oldDb().use { dbInsertAmbiguous(it) }
  store.migrateLegacy(); assertEquals(0L,store.snapshot().sales()); assertEquals(0L,store.snapshot().debt); assertTrue(store.snapshot().customers.isEmpty()); assertTrue(store.migrationReview().contains("AMBIGUOUS")); store.migrateLegacy(); assertEquals(0L,store.snapshot().sales())
 }
 @Test fun duplicatedIdentifiersNumbersAndReceiptSequencesAreRejected() {
  sale()
  for(table in listOf("invoices","payments","invoice_items")) { val root=JSONObject(store.backup()); val rows=root.getJSONObject("tables").getJSONArray(table); rows.put(JSONObject(rows.getJSONObject(0).toString())); rejects(root) }
 }
 @Test fun multipleRoundedReturnsCanRoundTripWithoutCashOrInventoryDrift() {
  val c=store.saveCustomer(Customer(name="عميل")); val invoice=store.issueInvoice("fractional",c,listOf(DraftLine("sand-1","3",1001,"طن")),1,"16",2000,"نقدي","")
  val item=store.snapshot().items.single(); repeat(3) { store.returnItems("part-$it",invoice,mapOf(item.id to "1"),"إرجاع",false,"تحويل بنكي") }
  val before=store.snapshot(); assertEquals(0L,before.sales()); assertEquals(0L,before.cash()); assertEquals(0L,before.debt); store.restore(store.backup()); assertEquals(before.invoices,store.snapshot().invoices); assertEquals(before.products,store.snapshot().products)
 }
 @Test fun persistentPreferencesRoundTripAndMalformedProtobufIsRejected() {
  sale()
  val bytes=androidx.datastore.preferences.PreferencesProto.PreferenceMap.newBuilder().build().toByteArray()
  val file=File(context.filesDir,"datastore/expense_manager_app_data_store.preferences_pb").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
  val backup=store.backup(); file.writeBytes(byteArrayOf(0))
  assertTrue(store.restore(backup)); assertArrayEquals(bytes,file.readBytes())
  val root=JSONObject(backup); val record=root.getJSONObject("payload").getJSONArray("files").getJSONObject(0); val invalid=byteArrayOf(-1,-1,-1)
  record.put("size",invalid.size).put("sha256",CommerceBackup.sha(invalid)).put("data",android.util.Base64.encodeToString(invalid,android.util.Base64.NO_WRAP)); rejects(root)
 }
 @Test fun schemaOneUpgradeKeepsExistingInvoicesAndReceipts() {
  sale()
  val before=store.snapshot(); val root=JSONObject(store.ledgerBackup().toString()); val tables=root.getJSONObject("tables")
  val definitions=CommerceStore.query(store.readableDatabase,"SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata' ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END") { it.getString(0) }
  val oldName="old-${UUID.randomUUID()}.db"; val file=context.getDatabasePath(oldName)
  SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
   definitions.forEach { sql -> db.execSQL(sql.replace("excluded INTEGER NOT NULL DEFAULT 0 CHECK(excluded IN (0,1)),","").replace(",quantities TEXT NOT NULL DEFAULT '{}'","")) }
   CommerceStore.tables.forEach { table -> tables.getJSONArray(table).objects().forEach { row ->
    if(table=="invoices") row.remove("excluded"); if(table=="credit_notes") row.remove("quantities")
    val values=android.content.ContentValues()
    row.keys().forEach { key -> when(val v=row.get(key)) { JSONObject.NULL -> values.putNull(key); is Int -> values.put(key,v); is Long -> values.put(key,v); else -> values.put(key,v.toString()) } }
    db.insertOrThrow(table,null,values)
   } }
   db.version=1
  }
  val upgraded=CommerceStore(context,oldName)
  try { assertEquals(before.invoices,upgraded.snapshot().invoices); assertEquals(before.payments,upgraded.snapshot().payments); assertEquals(2,upgraded.readableDatabase.version); upgraded.restore(upgraded.backup()); assertEquals(35000L,upgraded.snapshot().debt) } finally { upgraded.close(); context.deleteDatabase(oldName) }
 }

 @Test fun pendingLegacyCorrectionBlocksNewFinancialWorkAndBadBooksCannotBeRestored() {
  val c=emulatePriorWrongSupplierImport(); assertEquals(1,store.snapshot().legacyReviewPending)
  val before=store.snapshot()
  for(action in listOf<()->Unit>({store.collectDebt("new",c,1000,"نقدي","")},{store.addIncome("new",1000,"دخل","نقدي","",1)})) {
   try { action(); fail("financial work must wait for review") } catch(e:IllegalArgumentException) { }; assertEquals(before,store.snapshot())
  }
  rejects(JSONObject(store.backup())); store.repairLegacySupplierImports(); assertEquals(0,store.snapshot().legacyReviewPending)
  store.addIncome("ok",1000,"دخل","نقدي","",1); assertEquals(1000L,store.snapshot().cash())
 }

 @Test fun committedWalRowsAreCapturedWhileTheWriterConnectionStaysOpen() {
  sale()
  oldDb().use { db ->
   db.enableWriteAheadLogging()
   db.execSQL("INSERT INTO category(id,name,type,icon_background_color,icon_name,updated_on,created_on) VALUES('wal-live','ملتزم في WAL',0,'','',1,1)")
   val backup=JSONObject(store.backup())
   assertTrue(backup.getJSONObject("payload").getJSONObject("legacy").getJSONObject("tables").getJSONArray("category").objects().any { it.getString("id")=="wal-live" })
  }
 }

}
