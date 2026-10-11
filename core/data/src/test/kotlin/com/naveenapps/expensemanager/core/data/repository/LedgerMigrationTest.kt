package com.naveenapps.expensemanager.core.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import com.naveenapps.expensemanager.core.database.MIGRATION_11_12
import com.naveenapps.expensemanager.core.database.MIGRATION_12_13
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LedgerMigrationTest {
    @Test fun upgradeFrom11PreservesAllFinancialRowsAndAddsProfiles() = verifyUpgrade(11)
    @Test fun upgradeFrom12PreservesAllFinancialRowsAndAddsExactDecimals() = verifyUpgrade(12)

    private fun verifyUpgrade(version: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ledger-upgrade-$version.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = javaClass.classLoader!!.getResourceAsStream(
            "com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase/$version.json",
        )!!.bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject.getAsJsonObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            schema.getAsJsonArray("entities").forEach { item ->
                val entity = item.asJsonObject
                val table = entity.get("tableName").asString
                old.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
                entity.getAsJsonArray("indices")?.forEach { index ->
                    old.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getAsJsonArray("setupQueries").forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO category (id,name,type,icon_background_color,icon_name,updated_on,created_on) VALUES ('category','General',1,'#fff','ic_category',1000,1000)")
            old.execSQL("INSERT INTO account (id,name,type,icon_background_color,icon_name,amount,credit_limit,sequence,created_on,updated_on) VALUES ('cash','Cash',0,'#fff','ic_cash',500.123456,0,0,1000,1000)")
            old.execSQL("INSERT INTO customer (id,name,normalized_name,phone,created_on,updated_on) VALUES ('customer','Ahmed','ahmed','123',1000,1000)")
            old.execSQL("INSERT INTO supplier (id,name,normalized_name,phone,created_on,updated_on) VALUES ('supplier','Mohammed','mohammed','456',1000,1000)")
            old.execSQL("INSERT INTO `transaction` (id,notes,category_id,from_account_id,type,amount,image_path,created_on,updated_on,customer_id,customer_name,invoice_number) VALUES ('sale','Sale','category','cash',3,100.125,'',1000,1000,'customer','Ahmed','INV-1')")
            old.execSQL("INSERT INTO `transaction` (id,notes,category_id,from_account_id,type,amount,image_path,created_on,updated_on,supplier_id,supplier_name,invoice_number) VALUES ('purchase','Purchase','category','cash',3,200.250,'',1000,1000,'supplier','Mohammed','PUR-1')")
            old.execSQL("INSERT INTO customer_payment (id,customer_id,invoice_transaction_id,account_id,amount,notes,paid_on) VALUES ('receipt','customer','sale','cash',30.125,'old note',2000)")
            old.execSQL("INSERT INTO supplier_payment (id,supplier_id,invoice_transaction_id,account_id,amount,notes,paid_on) VALUES ('payment','supplier','purchase','cash',80.075,'old note',2000)")
            old.version = version
        }
        val db = Room.databaseBuilder(context, ExpenseManagerDatabase::class.java, name)
            .addMigrations(MIGRATION_11_12, MIGRATION_12_13).allowMainThreadQueries().build()
        try {
            // Opening Room validates the entire migrated schema against its generated v13 schema.
            assertThat(db.accountDao().findById("cash")!!.amount).isEqualTo(500.123456)
            assertThat(db.accountDao().findById("cash")!!.amountDecimal).isEqualTo("500.123456")
            db.openHelper.readableDatabase.query("SELECT created_on FROM customer WHERE id = 'customer'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(1000L)
            }
            assertThat(db.customerDao().findById("customer")!!.creditLimitMilliunits).isEqualTo(0)
            assertThat(db.customerDao().findById("customer")!!.address).isNull()
            assertThat(db.supplierDao().findById("supplier")!!.companyName).isNull()
            assertThat(db.transactionDao().findById("sale")!!.amountDecimal).isEqualTo("100.125")
            assertThat(db.transactionDao().findById("purchase")!!.amountDecimal).isEqualTo("200.25")
            assertThat(db.customerPaymentDao().findPayment("receipt")!!.amountDecimal).isEqualTo("30.125")
            assertThat(db.supplierPaymentDao().findPayment("payment")!!.amountDecimal).isEqualTo("80.075")
            assertThat(db.customerPaymentDao().findPayment("receipt")!!.notes).isEqualTo("old note")
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
