package com.naveenapps.expensemanager.commerce

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
class CommerceBackupSecurityTest {
    private lateinit var context: Context
    private lateinit var store: CommerceStore
    private lateinit var name: String

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "backup-security-${UUID.randomUUID()}.db"
        store = CommerceStore(context, name)
        store.addIncome("before", 1200, "دخل سابق", "نقدي", "", 1)
    }

    @After fun cleanup() {
        store.close()
        context.deleteDatabase(name)
        context.deleteDatabase("expense_manager_database.db")
    }

    private fun oldDatabase(): SQLiteDatabase {
        val path = context.getDatabasePath("expense_manager_database.db")
        if (!path.exists()) {
            val room = Room.databaseBuilder(context, ExpenseManagerDatabase::class.java, path.name)
                .allowMainThreadQueries().build()
            room.openHelper.writableDatabase
            room.close()
        }
        return SQLiteDatabase.openOrCreateDatabase(path, null)
    }

    private fun legacyRoot(): JSONObject {
        oldDatabase().use { db ->
            db.execSQL("INSERT OR IGNORE INTO category(id,name,type,icon_background_color,icon_name,updated_on,created_on) VALUES('old','صنف قديم',0,'','',1,1)")
            db.execSQL("INSERT OR IGNORE INTO account(id,name,type,icon_background_color,icon_name,amount,credit_limit,sequence,created_on,updated_on) VALUES('acct','حساب',0,'','',12.5,0,0,1,1)")
        }
        return JSONObject(store.backup())
    }

    private fun legacy(root: JSONObject) = root.getJSONObject("payload").getJSONObject("legacy")
    private fun schema(root: JSONObject, name: String) = legacy(root).getJSONArray("schema").objects().single { it.getString("name") == name }
    private fun entry(path: String, bytes: ByteArray = "attachment".toByteArray()) = JSONObject()
        .put("path", path).put("size", bytes.size).put("sha256", CommerceBackup.sha(bytes))
        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))

    private fun rejectsWithoutChanges(root: JSONObject) {
        val before = store.snapshot()
        val files = context.filesDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(context.filesDir).path to CommerceBackup.sha(it.readBytes()) }
        val old = if (context.getDatabasePath("expense_manager_database.db").exists()) CommerceBackup.captureLegacy(context).toString() else null
        try { store.restore(root.toString()); fail("The backup must be rejected before replacing current data") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, store.snapshot())
        assertEquals(files, context.filesDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(context.filesDir).path to CommerceBackup.sha(it.readBytes()) })
        if (old != null) assertEquals(old, CommerceBackup.captureLegacy(context).toString())
        assertFalse(File(context.filesDir, "commerce-restore-journal.json").exists())
    }

    @Test fun managedDirectoryCannotBeRestoredAsAFile() {
        for (path in listOf("category_images", "account_images", "transaction_attachments", "datastore")) {
            val root = JSONObject(store.backup())
            root.getJSONObject("payload").put("files", JSONArray().put(entry(path)))
            rejectsWithoutChanges(root)
        }
    }

    @Test fun fileDirectoryPrefixCollisionsAreRejectedInBothOrders() {
        for (paths in listOf(listOf("category_images/a", "category_images/a/b"), listOf("category_images/a/b", "category_images/a"))) {
            val root = JSONObject(store.backup())
            root.getJSONObject("payload").put("files", JSONArray(paths.map { entry(it) }))
            rejectsWithoutChanges(root)
        }
    }

    @Test fun alteredColumnDefaultCannotHideBehindTheOriginalRoomIdentityHash() {
        val root = legacyRoot()
        val table = schema(root, "budget")
        assertTrue(table.getString("sql").contains("DEFAULT 0"))
        table.put("sql", table.getString("sql").replace("DEFAULT 0", "DEFAULT 1"))
        rejectsWithoutChanges(root)
    }

    @Test fun partialUniqueIndexCannotHideBehindTheOriginalRoomIdentityHash() {
        val root = legacyRoot()
        val index = schema(root, "index_transaction_invoice_number")
        index.put("sql", index.getString("sql") + " WHERE invoice_number <> 'unchecked'")
        rejectsWithoutChanges(root)
    }

    @Test fun descendingAndCollatedIndexesCannotChangeFutureQueriesAndUniqueness() {
        for (suffix in listOf(" DESC", " COLLATE NOCASE")) {
            val root = legacyRoot()
            val index = schema(root, "index_transaction_invoice_number")
            index.put("sql", index.getString("sql").replace("`invoice_number`)", "`invoice_number`$suffix)"))
            rejectsWithoutChanges(root)
        }
    }

    @Test fun extraUniqueIndexCannotSilentlyForbidPreviouslyValidCategoryInserts() {
        val root = legacyRoot()
        legacy(root).getJSONArray("schema").put(JSONObject().put("type", "index")
            .put("name", "category_name_unique").put("sql", "CREATE UNIQUE INDEX category_name_unique ON category(name)"))
        rejectsWithoutChanges(root)
    }

    @Test fun textAndInfiniteValuesCannotBeImportedIntoRealMoneyColumns() {
        for (value in listOf("unknown", "NaN", "1e999")) {
            val root = legacyRoot()
            legacy(root).getJSONObject("tables").getJSONArray("account").getJSONObject(0).put("amount", value)
            rejectsWithoutChanges(root)
        }
    }

    @Test fun fractionalTimestampsCannotBeTruncatedByRoomIntegerGetters() {
        val root = legacyRoot()
        legacy(root).getJSONObject("tables").getJSONArray("category").getJSONObject(0).put("created_on", "1.5")
        rejectsWithoutChanges(root)
    }

    @Test fun blobCannotBeRestoredIntoTextIdentityColumns() {
        val root = legacyRoot()
        legacy(root).getJSONObject("tables").getJSONArray("category").getJSONObject(0)
            .put("name", JSONObject().put("blob", Base64.encodeToString(byteArrayOf(1, 2), Base64.NO_WRAP)))
        rejectsWithoutChanges(root)
    }

    @Test fun createTableSelectCannotInventRowsNotPresentInTheBackup() {
        val root = legacyRoot()
        legacy(root).getJSONArray("schema").put(JSONObject().put("type", "table").put("name", "invented")
            .put("sql", "CREATE TABLE invented AS SELECT 999 AS balance"))
        legacy(root).getJSONObject("tables").put("invented", JSONArray())
        rejectsWithoutChanges(root)
    }

    @Test fun unsupportedCheckExpressionsAreRejectedBeforeImportingRecords() {
        val root = legacyRoot()
        legacy(root).getJSONArray("schema").put(JSONObject().put("type", "table").put("name", "invented")
            .put("sql", "CREATE TABLE invented (value TEXT CHECK(length(value)<100))"))
        legacy(root).getJSONObject("tables").put("invented", JSONArray().put(JSONObject().put("value", "row")))
        rejectsWithoutChanges(root)
    }

    @Test fun restoredLegacyDatabaseCanActuallyReopenWithRoomAndReadEntities() {
        val backup = legacyRoot().toString()
        oldDatabase().use { it.execSQL("DELETE FROM category"); it.execSQL("UPDATE account SET amount=0") }
        assertTrue(store.restore(backup))
        val room = Room.databaseBuilder(context, ExpenseManagerDatabase::class.java, "expense_manager_database.db")
            .allowMainThreadQueries().build()
        try {
            val category = runBlocking { room.categoryDao().findById("old") }
            assertEquals("صنف قديم", category?.name)
            room.openHelper.writableDatabase.query("SELECT amount FROM account WHERE id='acct'").use {
                assertTrue(it.moveToFirst()); assertEquals(12.5, it.getDouble(0), 0.0)
            }
        } finally { room.close() }
    }

    @Test fun oversizedAttachmentIsRefusedFromItsEncodedBudgetBeforeReading() {
        val file = File(context.filesDir, "transaction_attachments/large.bin").apply { parentFile!!.mkdirs() }
        RandomAccessFile(file, "rw").use { it.setLength(CommerceBackup.MAX_BYTES.toLong()) }
        val before = store.snapshot()
        try { store.backup(); fail("Base64 would exceed the package limit") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("50")) }
        assertEquals(before, store.snapshot())
        assertTrue(store.safetyBackupNames().isEmpty())
    }

    @Test fun symlinkedManagedDirectoryIsNeverReadAsAppData() {
        val outside = File(context.cacheDir, "external-${UUID.randomUUID()}").apply { mkdirs() }
        File(outside, "private.bin").writeText("outside")
        val link = File(context.filesDir, "category_images")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            try { store.backup(); fail("Do not capture another directory via a symlink") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("مسار آخر")) }
            assertEquals("outside", File(outside, "private.bin").readText())
        } finally { Files.delete(link.toPath()); outside.deleteRecursively() }
    }

    @Test fun damagedRecoveryJournalIsPreservedAndDoesNotModifyCurrentData() {
        val journal = File(context.filesDir, "commerce-restore-journal.json")
        val before = store.snapshot()
        journal.writeText(JSONObject().put("safety", "../outside").put("unexpected", true).toString())
        try { store.recoverPendingRestore(); fail("Damaged journal must stop recovery") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, store.snapshot())
        assertTrue(journal.exists())
    }
}
