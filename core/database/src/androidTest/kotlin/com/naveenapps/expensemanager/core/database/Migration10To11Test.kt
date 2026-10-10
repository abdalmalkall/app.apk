package com.naveenapps.expensemanager.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

private const val MIGRATION_10_TO_11_TEST_DB = "migration_10_to_11_test"

@RunWith(AndroidJUnit4::class)
class Migration10To11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ExpenseManagerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun existingCustomerSalesPaymentsAndAccountBalancesRemainUnchanged() {
        val databaseV10 = helper.createDatabase(MIGRATION_10_TO_11_TEST_DB, 10)
        databaseV10.execSQL(
            """
            INSERT INTO category
                (id, name, type, icon_background_color, icon_name, updated_on, created_on)
            VALUES ('category-1', 'General', 1, '#FFFFFF', 'ic_category', 1000, 1000)
            """.trimIndent(),
        )
        databaseV10.execSQL(
            """
            INSERT INTO account
                (id, name, type, icon_background_color, icon_name, amount, credit_limit,
                 sequence, created_on, updated_on)
            VALUES ('account-1', 'Cash', 0, '#FFFFFF', 'ic_cash', 275.0, 0.0, 1, 1000, 1000)
            """.trimIndent(),
        )
        databaseV10.execSQL(
            """
            INSERT INTO customer (id, name, normalized_name, phone, created_on, updated_on)
            VALUES ('customer-1', 'Existing Customer', 'existing customer', '555-0100', 1000, 1000)
            """.trimIndent(),
        )
        databaseV10.execSQL(
            """
            INSERT INTO `transaction`
                (id, notes, category_id, from_account_id, type, customer_name, amount,
                 image_path, created_on, updated_on, to_account_id, customer_id, invoice_number)
            VALUES
                ('invoice-1', 'existing credit sale', 'category-1', 'account-1', 3,
                 'Existing Customer', 100.0, '', 2000, 2000, NULL, 'customer-1', 'INV-0001')
            """.trimIndent(),
        )
        databaseV10.execSQL(
            """
            INSERT INTO customer_payment
                (id, customer_id, invoice_transaction_id, account_id, amount, notes, paid_on)
            VALUES ('customer-payment-1', 'customer-1', 'invoice-1', 'account-1', 25.0, 'partial', 3000)
            """.trimIndent(),
        )
        databaseV10.close()

        val databaseV11 = helper.runMigrationsAndValidate(
            MIGRATION_10_TO_11_TEST_DB,
            11,
            true,
            MIGRATION_10_11,
        )

        databaseV11.query("SELECT amount FROM account WHERE id = 'account-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getDouble(0)).isEqualTo(275.0)
        }
        databaseV11.query("SELECT amount, supplier_id FROM `transaction` WHERE id = 'invoice-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getDouble(0)).isEqualTo(100.0)
            assertThat(cursor.isNull(1)).isTrue()
        }
        databaseV11.query("SELECT COUNT(*) FROM customer_payment WHERE id = 'customer-payment-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        databaseV11.query("SELECT COUNT(*) FROM supplier").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        databaseV11.query("SELECT COUNT(*) FROM supplier_payment").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        databaseV11.close()
    }
}