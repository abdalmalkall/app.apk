package com.naveenapps.expensemanager.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

private const val MIGRATION_TEST_DB = "migration_8_to_9_test"

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ExpenseManagerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun existingDebtRowsArePreservedAndLinkedToOneCustomer() {
        val databaseV8 = helper.createDatabase(MIGRATION_TEST_DB, 8)
        databaseV8.execSQL(
            """
            INSERT INTO category
                (id, name, type, icon_background_color, icon_name, updated_on, created_on)
            VALUES ('cat-1', 'Sand', 0, '#FFFFFF', 'ic_calendar', 1000, 1000)
            """.trimIndent(),
        )
        databaseV8.execSQL(
            """
            INSERT INTO account
                (id, name, type, icon_background_color, icon_name, amount, credit_limit,
                 sequence, created_on, updated_on)
            VALUES ('acc-1', 'Cash', 0, '#FFFFFF', 'ic_cash', 0.0, 0.0, 1, 1000, 1000)
            """.trimIndent(),
        )
        databaseV8.execSQL(
            """
            INSERT INTO `transaction`
                (id, notes, category_id, from_account_id, type, customer_name, amount,
                 image_path, created_on, updated_on, to_account_id)
            VALUES
                ('txn-1', '', 'cat-1', 'acc-1', 3, ' Ahmad ', 50.0, '', 1000, 1000, NULL),
                ('txn-2', '', 'cat-1', 'acc-1', 3, 'Ahmad', 25.0, '', 2000, 2000, NULL)
            """.trimIndent(),
        )
        databaseV8.close()

        val databaseV9 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 9, true, MIGRATION_8_9)
        val customerCursor = databaseV9.query("SELECT id, name, phone FROM customer")
        assertThat(customerCursor.count).isEqualTo(1)
        customerCursor.moveToFirst()
        assertThat(customerCursor.getString(customerCursor.getColumnIndexOrThrow("name"))).isEqualTo("Ahmad")
        assertThat(customerCursor.isNull(customerCursor.getColumnIndexOrThrow("phone"))).isTrue()
        customerCursor.close()

        val transactionCursor = databaseV9.query(
            "SELECT id, amount, customer_id FROM `transaction` ORDER BY id",
        )
        assertThat(transactionCursor.count).isEqualTo(2)
        transactionCursor.moveToFirst()
        val migratedCustomerId = transactionCursor.getString(transactionCursor.getColumnIndexOrThrow("customer_id"))
        assertThat(transactionCursor.getDouble(transactionCursor.getColumnIndexOrThrow("amount"))).isEqualTo(50.0)
        transactionCursor.moveToNext()
        assertThat(transactionCursor.getString(transactionCursor.getColumnIndexOrThrow("customer_id")))
            .isEqualTo(migratedCustomerId)
        assertThat(transactionCursor.getDouble(transactionCursor.getColumnIndexOrThrow("amount"))).isEqualTo(25.0)
        transactionCursor.close()
        databaseV9.close()
    }
}