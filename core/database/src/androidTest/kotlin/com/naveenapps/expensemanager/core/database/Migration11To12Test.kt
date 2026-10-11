package com.naveenapps.expensemanager.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val MIGRATION_11_TO_12_TEST_DB = "migration_11_to_12_test"

@RunWith(AndroidJUnit4::class)
class Migration11To12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ExpenseManagerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun existingFinancialRowsRemainUnchangedAndNewProfileFieldsHaveSafeDefaults() {
        val databaseV11 = helper.createDatabase(MIGRATION_11_TO_12_TEST_DB, 11)
        databaseV11.execSQL(
            """
            INSERT INTO category
                (id, name, type, icon_background_color, icon_name, updated_on, created_on)
            VALUES ('category-1', 'General', 1, '#FFFFFF', 'ic_category', 1000, 1000)
            """.trimIndent(),
        )
        databaseV11.execSQL(
            """
            INSERT INTO account
                (id, name, type, icon_background_color, icon_name, amount, credit_limit,
                 sequence, created_on, updated_on)
            VALUES ('account-1', 'Cash', 0, '#FFFFFF', 'ic_cash', 500.0, 0.0, 1, 1000, 1000)
            """.trimIndent(),
        )
        databaseV11.execSQL(
            """
            INSERT INTO customer (id, name, normalized_name, phone, created_on, updated_on)
            VALUES ('customer-1', 'Existing Customer', 'existing customer', '555-0100', 1000, 1000)
            """.trimIndent(),
        )
        databaseV11.execSQL(
            """
            INSERT INTO supplier (id, name, normalized_name, phone, created_on, updated_on)
            VALUES ('supplier-1', 'Existing Supplier', 'existing supplier', '555-0200', 1000, 1000)
            """.trimIndent(),
        )
        databaseV11.execSQL(
            """
            INSERT INTO `transaction`
                (id, notes, category_id, from_account_id, type, customer_name, amount,
                 image_path, created_on, updated_on, to_account_id, customer_id, invoice_number,
                 supplier_id, supplier_name)
            VALUES
                ('customer-invoice', 'existing customer debt', 'category-1', 'account-1', 3,
                 'Existing Customer', 100.125, '', 2000, 2000, NULL, 'customer-1', 'INV-0001', NULL, NULL),
                ('supplier-invoice', 'existing supplier debt', 'category-1', 'account-1', 3,
                 NULL, 200.250, '', 3000, 3000, NULL, NULL, NULL, 'supplier-1', 'Existing Supplier')
            """.trimIndent(),
        )
        databaseV11.execSQL(
            """
            INSERT INTO customer_payment
                (id, customer_id, invoice_transaction_id, account_id, amount, notes, paid_on)
            VALUES ('customer-payment-1', 'customer-1', 'customer-invoice', 'account-1', 30.125, 'partial', 4000)
            """.trimIndent(),
        )
        databaseV11.execSQL(
            """
            INSERT INTO supplier_payment
                (id, supplier_id, invoice_transaction_id, account_id, amount, notes, paid_on)
            VALUES ('supplier-payment-1', 'supplier-1', 'supplier-invoice', 'account-1', 80.075, 'partial', 5000)
            """.trimIndent(),
        )
        databaseV11.close()

        val databaseV12 = helper.runMigrationsAndValidate(
            MIGRATION_11_TO_12_TEST_DB,
            12,
            true,
            MIGRATION_11_12,
        )

        databaseV12.query(
            "SELECT address, notes, credit_limit_milliunits FROM customer WHERE id = 'customer-1'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.getLong(2)).isEqualTo(0L)
        }
        databaseV12.query(
            "SELECT address, company_name, notes FROM supplier WHERE id = 'supplier-1'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
        }
        databaseV12.query("SELECT amount FROM account WHERE id = 'account-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getDouble(0)).isEqualTo(500.0)
        }
        databaseV12.query("SELECT amount FROM `transaction` WHERE id = 'customer-invoice'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getDouble(0)).isEqualTo(100.125)
        }
        databaseV12.query("SELECT amount FROM customer_payment WHERE id = 'customer-payment-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getDouble(0)).isEqualTo(30.125)
        }
        databaseV12.query("SELECT amount FROM supplier_payment WHERE id = 'supplier-payment-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getDouble(0)).isEqualTo(80.075)
        }
        databaseV12.close()
    }
}
