package com.naveenapps.expensemanager.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import com.naveenapps.expensemanager.core.database.entity.*
import com.naveenapps.expensemanager.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
class LedgerAccountingTest {
    private lateinit var db: ExpenseManagerDatabase
    private val date = Date(1700000000000L)

    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExpenseManagerDatabase::class.java)
            .allowMainThreadQueries().build()
        db.accountDao().insert(AccountEntity("cash", "Cash", AccountType.REGULAR, "#FFFFFF", "ic_cash", 500.0, 0.0, 0, date, date))
        db.categoryDao().insert(CategoryEntity("category", "General", CategoryType.EXPENSE, "#FFFFFF", "ic_category", date, date))
        db.customerDao().insertIfMissing(CustomerEntity("customer", "Ahmed", "ahmed", createdOn = date, updatedOn = date))
        db.supplierDao().insertIfMissing(SupplierEntity("supplier", "Mohammed", "mohammed", createdOn = date, updatedOn = date))
    }
    @After fun close() { db.close() }

    private fun invoice(id: String = "invoice", amount: Double = 100.0, supplier: Boolean = false) = TransactionEntity(
        id = id, notes = "Invoice", categoryId = "category", fromAccountId = "cash", type = TransactionType.DEBT,
        amount = amount, imagePath = "", createdOn = date, updatedOn = date, toAccountId = null,
        customerId = if (supplier) null else "customer", customerName = if (supplier) null else "Ahmed",
        supplierId = if (supplier) "supplier" else null, supplierName = if (supplier) "Mohammed" else null,
    )
    private fun receipt(id: String, amount: Double, invoice: String = "invoice") = CustomerPaymentEntity(
        id, "customer", invoice, "cash", amount, "Cash", Date(date.time + 1000),
    )
    private fun supplierPayment(id: String, amount: Double) = SupplierPaymentEntity(
        id, "supplier", "purchase", "cash", amount, "Cash", Date(date.time + 1000),
    )
    private suspend fun cash() = db.accountDao().findById("cash")!!.amount

    @Test fun ahmedExampleAndFullSettlement() = runBlocking {
        db.transactionDao().insertTransaction(invoice(), 0.0, false)
        assertThat(cash()).isEqualTo(500.0)
        db.customerPaymentDao().addPayment(receipt("first", 30.0))
        assertThat(cash()).isEqualTo(530.0)
        assertThat(db.customerPaymentDao().getTotalPaid("invoice")).isEqualTo(30.0)
        db.customerPaymentDao().addPayment(receipt("second", 20.0))
        assertThat(cash()).isEqualTo(550.0)
        assertThat(db.transactionDao().findById("invoice")!!.amount).isEqualTo(100.0)
        assertThat(db.customerPaymentDao().getTotalPaid("invoice")).isEqualTo(50.0)
        db.customerPaymentDao().addPayment(receipt("last", 50.0))
        assertThat(cash()).isEqualTo(600.0)
        assertThat(db.customerPaymentDao().getTotalPaid("invoice")).isEqualTo(100.0)
        assertThat(db.transactionDao().getAllTransaction().first()).hasSize(1)
    }

    @Test fun mohammedExampleDoesNotCreateAnotherPurchase() = runBlocking {
        db.transactionDao().insertTransaction(invoice("purchase", 200.0, true), 0.0, false)
        db.supplierPaymentDao().addPayment(supplierPayment("first", 80.0))
        assertThat(cash()).isEqualTo(420.0)
        db.supplierPaymentDao().addPayment(supplierPayment("second", 50.0))
        assertThat(cash()).isEqualTo(370.0)
        assertThat(db.supplierPaymentDao().getTotalPaid("purchase")).isEqualTo(130.0)
        assertThat(db.transactionDao().findById("purchase")!!.amount).isEqualTo(200.0)
    }

    @Test fun retryIsIdempotentAndChangedPayloadIsRejected() = runBlocking {
        db.transactionDao().insertTransaction(invoice(), 0.0, false)
        val receipt = receipt("same", 30.0)
        db.customerPaymentDao().addPayment(receipt)
        assertThat(db.customerPaymentDao().addPayment(receipt)).isEqualTo(0L)
        assertThat(db.customerPaymentDao().addPayment(receipt.copy(amount = 20.0))).isEqualTo(-1L)
        assertThat(cash()).isEqualTo(530.0)
        assertThat(db.customerPaymentDao().getPaymentsForInvoice("invoice")).hasSize(1)
    }

    @Test fun supplierRetryIsIdempotentAndReversalIsAppliedOnce() = runBlocking {
        db.transactionDao().insertTransaction(invoice("purchase", 200.0, true), 0.0, false)
        val payment = supplierPayment("same", 80.0)
        db.supplierPaymentDao().addPayment(payment)
        assertThat(db.supplierPaymentDao().addPayment(payment)).isEqualTo(0L)
        assertThat(cash()).isEqualTo(420.0)
        assertThat(db.supplierPaymentDao().deletePaymentAndUpdateAccount("same")).isTrue()
        assertThat(db.supplierPaymentDao().deletePaymentAndUpdateAccount("same")).isFalse()
        assertThat(cash()).isEqualTo(500.0)
    }

    @Test fun invalidAndExcessPaymentsLeaveBalanceUnchanged() = runBlocking {
        db.transactionDao().insertTransaction(invoice(), 0.0, false)
        for ((index, value) in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 100.001, 0.0001).withIndex()) {
            assertThat(db.customerPaymentDao().addPayment(receipt("invalid-$index", value))).isEqualTo(-1L)
        }
        assertThat(db.customerPaymentDao().addPayment(receipt("wrong-account", 1.0).copy(accountId = "missing"))).isEqualTo(-1L)
        assertThat(db.customerPaymentDao().addPayment(receipt("wrong-party", 1.0).copy(customerId = "missing"))).isEqualTo(-1L)
        assertThat(db.customerPaymentDao().addPayment(receipt("before-invoice", 1.0).copy(paidOn = Date(0)))).isEqualTo(-1L)
        assertThat(cash()).isEqualTo(500.0)
    }

    @Test fun threeDecimalsRemainExactAcrossPaymentsAndReversals() = runBlocking {
        db.transactionDao().insertTransaction(invoice(amount = 0.300), 0.0, false)
        db.customerPaymentDao().addPayment(receipt("a", 0.100))
        db.customerPaymentDao().addPayment(receipt("b", 0.200))
        assertThat(cash()).isEqualTo(500.300)
        assertThat(LedgerMoney.total(db.customerPaymentDao().getPaymentsForInvoice("invoice").map { it.amount }).compareTo(BigDecimal("0.300"))).isEqualTo(0)
        assertThat(db.customerPaymentDao().addPayment(receipt("over", 0.001))).isEqualTo(-1L)
        db.customerPaymentDao().deletePaymentAndUpdateAccount("a")
        db.customerPaymentDao().deletePaymentAndUpdateAccount("b")
        assertThat(cash()).isEqualTo(500.0)
    }

    @Test fun invoiceWithPaymentsCannotBeReassignedReducedOrDeleted() = runBlocking {
        val invoice = invoice()
        db.transactionDao().insertTransaction(invoice, 0.0, false)
        db.customerPaymentDao().addPayment(receipt("payment", 30.0))
        assertThat(runCatching { db.transactionDao().updateTransaction(invoice.copy(amount = 20.0), 0.0, false) }.isFailure).isTrue()
        assertThat(runCatching { db.transactionDao().deleteTransactionAndUpdateAccount(invoice) }.isFailure).isTrue()
        assertThat(cash()).isEqualTo(530.0)
        assertThat(db.transactionDao().findById("invoice")!!.amount).isEqualTo(100.0)
        db.customerPaymentDao().deletePaymentAndUpdateAccount("payment")
        assertThat(db.transactionDao().deleteTransactionAndUpdateAccount(invoice)).isEqualTo(1)
        assertThat(cash()).isEqualTo(500.0)
    }

    @Test fun failedEditRollsBackReversalAndInvoice() = runBlocking {
        val expense = invoice().copy(type = TransactionType.EXPENSE, customerId = null, customerName = null)
        db.transactionDao().insertTransaction(expense, -100.0, false)
        assertThat(cash()).isEqualTo(400.0)
        val failed = runCatching { db.transactionDao().updateTransaction(expense.copy(categoryId = "missing", amount = 200.0), -200.0, false) }
        assertThat(failed.isFailure).isTrue()
        assertThat(cash()).isEqualTo(400.0)
        assertThat(db.transactionDao().findById("invoice")!!.amount).isEqualTo(100.0)
        db.transactionDao().updateTransaction(expense.copy(amount = 200.0), -200.0, false)
        assertThat(cash()).isEqualTo(300.0)
        db.transactionDao().deleteTransactionAndUpdateAccount(expense)
        assertThat(cash()).isEqualTo(500.0)
    }

    @Test fun duplicateInvoiceNumberCannotReplaceExistingInvoice() = runBlocking {
        val original = invoice().copy(invoiceNumber = "INV-0001")
        db.transactionDao().insertTransaction(original, 0.0, false)
        assertThat(runCatching { db.transactionDao().insertTransaction(original.copy(id = "other"), 0.0, false) }.isFailure).isTrue()
        assertThat(db.transactionDao().findById("invoice")).isNotNull()
        assertThat(db.transactionDao().findById("other")).isNull()
    }

    @Test fun statementHasOpeningRunningClosingBalancesAndStableTies() {
        val movements = listOf(
            StatementMovement("invoice", 1000, "INV-1", "Sale", obligation = BigDecimal("100")),
            StatementMovement("z", 1000, "INV-1", "Payment", payment = BigDecimal("30"), invoiceId = "invoice"),
            StatementMovement("a", 2000, "INV-1", "Payment", payment = BigDecimal("20"), invoiceId = "invoice"),
        )
        val whole = buildAccountStatement("Ahmed", "123", null, movements.reversed(), 0, 3000)
        assertThat(whole.rows.map { it.movement.id }).containsExactly("invoice", "z", "a").inOrder()
        assertThat(whole.closingBalance).isEqualTo(BigDecimal("50"))
        val period = buildAccountStatement("Ahmed", null, null, movements, 1500, 3000)
        assertThat(period.openingBalance).isEqualTo(BigDecimal("70"))
        assertThat(period.rows.single().balance).isEqualTo(BigDecimal("50"))
        assertThat(period.closingBalance).isEqualTo(BigDecimal("50"))
    }

    @Test fun canonicalCashBalanceRetainsMilliunitsBeyondDoublePrecision() = runBlocking {
        val account = db.accountDao().findById("cash")!!
        db.accountDao().update(account.copy(amount = 10000000000000000.0, amountDecimal = "10000000000000000.000"))
        db.transactionDao().insertTransaction(invoice(amount = 1.0), 0.0, false)
        db.customerPaymentDao().addPayment(receipt("tiny", 0.001))
        assertThat(db.accountDao().findById("cash")!!.amountDecimal).isEqualTo("10000000000000000.001")
        db.customerPaymentDao().deletePaymentAndUpdateAccount("tiny")
        assertThat(BigDecimal(db.accountDao().findById("cash")!!.amountDecimal).compareTo(BigDecimal("10000000000000000.000"))).isEqualTo(0)
    }

    @Test fun supplierProfileCanBeCreatedEditedAndValidatedWithoutChangingCreationDate() = runBlocking {
        val repository = SupplierRepositoryImpl(db.supplierDao(),
            com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers(
                kotlinx.coroutines.Dispatchers.Unconfined, kotlinx.coroutines.Dispatchers.Unconfined, kotlinx.coroutines.Dispatchers.Unconfined))
        val profile = Supplier("new-supplier", "  Zaid  ", " 123 ", createdOn = date, updatedOn = date, address = " Main Street ", companyName = " Company ", notes = " Note ")
        assertThat(repository.saveSupplier(profile)).isEqualTo(Resource.Success(true))
        assertThat(repository.saveSupplier(profile.copy(name = "Zaid Ali", createdOn = Date(0)))).isEqualTo(Resource.Success(true))
        val stored = db.supplierDao().findById("new-supplier")!!
        assertThat(stored.name).isEqualTo("Zaid Ali")
        assertThat(stored.phone).isEqualTo("123")
        assertThat(stored.address).isEqualTo("Main Street")
        assertThat(stored.companyName).isEqualTo("Company")
        assertThat(stored.createdOn).isEqualTo(date)
        assertThat(repository.saveSupplier(profile.copy(id = "duplicate", name = " zaid ALI "))).isInstanceOf(Resource.Error::class.java)
        assertThat(repository.saveSupplier(profile.copy(id = "blank", name = " "))).isInstanceOf(Resource.Error::class.java)
    }

    @Test fun moneyInputRejectsPartialParsingAndSupportsArabicMilliunits() {
        assertThat(LedgerMoney.input("١٢٫٣٤٥")).isEqualTo(BigDecimal("12.345"))
        for (text in listOf("1x", "NaN", "Infinity", "1.2345", "-1", "", "1,2", "1e3")) assertThat(LedgerMoney.input(text)).isNull()
    }

    @Test fun referencedAccountsAndCategoriesCannotEraseTransactionHistory() = runBlocking {
        val expense = invoice().copy(type = TransactionType.EXPENSE, customerId = null, customerName = null)
        db.transactionDao().insertTransaction(expense, -100.0, false)
        val account = db.accountDao().findById("cash")!!
        val category = db.categoryDao().findById("category")!!
        assertThat(runCatching { db.accountDao().delete(account) }.isFailure).isTrue()
        assertThat(runCatching { db.categoryDao().delete(category) }.isFailure).isTrue()
        assertThat(db.transactionDao().findById("invoice")).isNotNull()
        assertThat(cash()).isEqualTo(400.0)
    }

    @Test fun duplicateProfileInsertionCannotCascadeDeleteInvoices() = runBlocking {
        db.transactionDao().insertTransaction(invoice(), 0.0, false)
        val account = db.accountDao().findById("cash")!!
        val category = db.categoryDao().findById("category")!!
        assertThat(runCatching { db.accountDao().insert(account.copy(name = "Replacement")) }.isFailure).isTrue()
        assertThat(runCatching { db.categoryDao().insert(category.copy(name = "Replacement")) }.isFailure).isTrue()
        assertThat(db.transactionDao().findById("invoice")).isNotNull()
        assertThat(db.accountDao().findById("cash")!!.name).isEqualTo("Cash")
    }

    @Test fun missingOrIdenticalTransferDestinationCannotDebitCash() = runBlocking {
        val transfer = invoice().copy(type = TransactionType.TRANSFER, customerId = null, customerName = null)
        for (destination in listOf(null, "", "cash", "missing")) {
            assertThat(runCatching {
                db.transactionDao().insertTransaction(transfer.copy(toAccountId = destination), -100.0, true)
            }.isFailure).isTrue()
        }
        assertThat(db.transactionDao().findById("invoice")).isNull()
        assertThat(cash()).isEqualTo(500.0)
    }

    @Test fun hiddenFractionalPaymentValuesAreRejectedForBothDebtDirections() = runBlocking {
        db.transactionDao().insertTransaction(invoice(), 0.0, false)
        db.transactionDao().insertTransaction(invoice("purchase", supplier = true), 0.0, false)
        val hidden = "0.10000000000000001"
        assertThat(BigDecimal(hidden).toDouble()).isEqualTo(0.1)
        assertThat(db.customerPaymentDao().addPayment(receipt("customer-hidden", 0.1).copy(amountDecimal = hidden))).isEqualTo(-1L)
        assertThat(db.supplierPaymentDao().addPayment(supplierPayment("supplier-hidden", 0.1).copy(amountDecimal = hidden))).isEqualTo(-1L)
        assertThat(cash()).isEqualTo(500.0)
        assertThat(db.customerPaymentDao().getPaymentsForInvoice("invoice")).isEmpty()
        assertThat(db.supplierPaymentDao().getPaymentsForInvoice("purchase")).isEmpty()
    }

    @Test fun hiddenFractionalNewDebtIsRejectedBeforeRecordingTheInvoice() = runBlocking {
        val inconsistent = invoice(amount = 0.1).copy(amountDecimal = "0.10000000000000001")
        assertThat(runCatching { db.transactionDao().insertTransaction(inconsistent, 0.0, false) }.isFailure).isTrue()
        assertThat(db.transactionDao().findById("invoice")).isNull()
        assertThat(cash()).isEqualTo(500.0)
    }

    @Test fun paidHistoricalInvoiceCannotChangeExactAmountWithinTheSameDouble() = runBlocking {
        // Historical precision is preserved in amount_decimal even when Double cannot show it.
        val original = invoice(amount = 1.0).copy(amountDecimal = "1.0000000000000001")
        db.transactionDao().insert(original)
        db.customerPaymentDao().addPayment(receipt("payment", 0.1))
        assertThat(runCatching {
            db.transactionDao().updateTransaction(original.copy(amountDecimal = "1.0"), 0.0, false)
        }.isFailure).isTrue()
        assertThat(db.transactionDao().findById("invoice")!!.amountDecimal).isEqualTo("1.0000000000000001")
        assertThat(cash()).isEqualTo(500.1)
    }

    @Test fun paymentRetryCannotChangeTheExactValueWhileKeepingTheSameDouble() = runBlocking {
        db.transactionDao().insertTransaction(invoice(), 0.0, false)
        db.customerPaymentDao().addPayment(receipt("same", 0.1))
        assertThat(db.customerPaymentDao().addPayment(receipt("same", 0.1).copy(amountDecimal = "0.10000000000000001"))).isEqualTo(-1L)
        assertThat(cash()).isEqualTo(500.1)
        assertThat(db.customerPaymentDao().getPaymentsForInvoice("invoice")).hasSize(1)
    }
}
