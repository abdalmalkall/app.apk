package com.naveenapps.expensemanager.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import com.naveenapps.expensemanager.core.database.entity.AccountEntity
import com.naveenapps.expensemanager.core.database.entity.CategoryEntity
import com.naveenapps.expensemanager.core.database.entity.SupplierEntity
import com.naveenapps.expensemanager.core.database.entity.SupplierPaymentEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionEntity
import com.naveenapps.expensemanager.core.model.AccountType
import com.naveenapps.expensemanager.core.model.CategoryType
import com.naveenapps.expensemanager.core.model.StoredIcon
import com.naveenapps.expensemanager.core.model.TransactionType
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupplierPaymentDaoTest {

    private lateinit var database: ExpenseManagerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ExpenseManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun partialPaymentsUpdateAccountOnceAndRejectOverpayment() = runBlocking {
        val timestamp = Date(1_700_000_000_000L)
        val supplier = SupplierEntity(
            id = "supplier-1",
            name = "Stone Supply",
            normalizedName = "stone supply",
            createdOn = timestamp,
            updatedOn = timestamp,
        )
        val account = AccountEntity(
            id = "account-1",
            name = "Cash",
            type = AccountType.REGULAR,
            iconBackgroundColor = "#FFFFFF",
            iconName = "ic_cash",
            amount = 500.0,
            creditLimit = 0.0,
            sequence = 1,
            createdOn = timestamp,
            updatedOn = timestamp,
        )
        val category = CategoryEntity(
            id = "category-1",
            name = "Purchases",
            type = CategoryType.EXPENSE,
            iconBackgroundColor = "#FFFFFF",
            iconName = "ic_category",
            updatedOn = timestamp,
            createdOn = timestamp,
        )
        database.supplierDao().insertIfMissing(supplier)
        database.accountDao().insert(account)
        database.categoryDao().insert(category)
        database.transactionDao().insert(
            TransactionEntity(
                id = "purchase-1",
                notes = "Credit purchase",
                categoryId = category.id,
                fromAccountId = account.id,
                type = TransactionType.DEBT,
                amount = 100.0,
                imagePath = "",
                createdOn = timestamp,
                updatedOn = timestamp,
                toAccountId = null,
                invoiceNumber = "INV-0001",
                supplierId = supplier.id,
                supplierName = supplier.name,
            ),
        )

        val paymentDao = database.supplierPaymentDao()
        assertThat(paymentDao.addPayment(payment("payment-1", supplier.id, "purchase-1", account.id, 35.0, timestamp)))
            .isNotEqualTo(-1L)
        assertThat(paymentDao.addPayment(payment("payment-2", supplier.id, "purchase-1", account.id, 65.0, timestamp)))
            .isNotEqualTo(-1L)
        assertThat(paymentDao.getTotalPaid("purchase-1")).isWithin(0.000001).of(100.0)
        assertThat(database.accountDao().findById(account.id)?.amount).isWithin(0.000001).of(400.0)

        assertThat(paymentDao.addPayment(payment("payment-over", supplier.id, "purchase-1", account.id, 0.01, timestamp)))
            .isEqualTo(-1L)
        assertThat(database.accountDao().findById(account.id)?.amount).isWithin(0.000001).of(400.0)

        assertThat(paymentDao.deletePaymentAndUpdateAccount("payment-2")).isTrue()
        assertThat(paymentDao.getTotalPaid("purchase-1")).isWithin(0.000001).of(35.0)
        assertThat(database.accountDao().findById(account.id)?.amount).isWithin(0.000001).of(465.0)
    }

    private fun payment(
        paymentId: String,
        supplierId: String,
        invoiceId: String,
        accountId: String,
        amount: Double,
        timestamp: Date,
    ) = SupplierPaymentEntity(
        id = paymentId,
        supplierId = supplierId,
        invoiceTransactionId = invoiceId,
        accountId = accountId,
        amount = amount,
        paidOn = timestamp,
    )
}