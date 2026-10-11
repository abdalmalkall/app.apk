package com.naveenapps.expensemanager.feature.transaction.supplier

import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.domain.usecase.account.GetAllAccountsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.supplier.*
import com.naveenapps.expensemanager.core.domain.usecase.transaction.GetAllTransactionsUseCase
import com.naveenapps.expensemanager.core.model.*
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatRepository
import com.naveenapps.expensemanager.core.testing.BaseCoroutineTest
import com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class SupplierLedgerViewModelTest : BaseCoroutineTest() {
    private val currency: GetCurrencyUseCase = mock()
    private val format: GetFormattedAmountUseCase = mock()
    private val transactions: GetAllTransactionsUseCase = mock()
    private val suppliers: GetAllSuppliersUseCase = mock()
    private val payments: GetAllSupplierPaymentsUseCase = mock()
    private val save: SaveSupplierUseCase = mock()
    private val addPayment: AddSupplierPaymentUseCase = mock()
    private val deletePayment: DeleteSupplierPaymentUseCase = mock()
    private val accounts: GetAllAccountsUseCase = mock()
    private val settings: SettingsRepository = mock()
    private val numbers: NumberFormatRepository = mock()
    private val navigator: AppComposeNavigator = mock()
    private val date = Date(1700000000000L)
    private val supplier = Supplier("supplier", "Mohammed", phone = "555-0100", createdOn = date, updatedOn = date)

    private fun model(invoices: List<Transaction> = emptyList(), receipts: List<SupplierPayment> = emptyList()): SupplierLedgerViewModel {
        whenever(currency.invoke()).thenReturn(flowOf(Currency("JOD", "JOD", code = "JOD")))
        whenever(format.invoke(any(), any())).thenAnswer { Amount(it.getArgument(0), it.getArgument<Double>(0).toString(), it.getArgument(1)) }
        whenever(transactions.invoke()).thenReturn(flowOf(invoices))
        whenever(suppliers.invoke()).thenReturn(flowOf(listOf(supplier)))
        whenever(payments.invoke()).thenReturn(flowOf(receipts))
        val cash: Account = mock()
        whenever(cash.id).thenReturn("selected-cash")
        whenever(accounts.invoke()).thenReturn(flowOf(listOf(cash)))
        whenever(settings.getDefaultAccount()).thenReturn(flowOf("selected-cash"))
        return SupplierLedgerViewModel(currency, format, transactions, suppliers, payments, save, addPayment, deletePayment,
            accounts, settings, numbers,
            AppCoroutineDispatchers(testCoroutineDispatcher.dispatcher, testCoroutineDispatcher.dispatcher, testCoroutineDispatcher.dispatcher), navigator)
    }
    private fun invoice() = Transaction("purchase", "Goods", "category", "cash", null, Amount(200.0), "", TransactionType.DEBT,
        createdOn = date, updatedOn = date, supplierId = supplier.id, supplierName = supplier.name, invoiceNumber = "PUR-1")

    @Test fun `registered supplier can be searched by phone without purchases`() = runTest {
        val model = model()
        advanceUntilIdle()
        assertThat(model.state.value.filteredSuppliers.single().outstanding.amount).isEqualTo(0.0)
        model.processAction(SupplierLedgerAction.UpdateSearch("0100"))
        assertThat(model.state.value.filteredSuppliers).hasSize(1)
        model.processAction(SupplierLedgerAction.SetFilter(DebtFilter.OUTSTANDING))
        assertThat(model.state.value.filteredSuppliers).isEmpty()
        model.processAction(SupplierLedgerAction.SetFilter(DebtFilter.SETTLED))
        assertThat(model.state.value.filteredSuppliers).hasSize(1)
    }

    @Test fun `purchase and partial payments produce genuine statement balances`() = runTest {
        val model = model(listOf(invoice()), listOf(
            SupplierPayment("first", supplier.id, "purchase", "cash", 80.0, paidOn = Date(date.time + 1000)),
            SupplierPayment("second", supplier.id, "purchase", "cash", 50.0, paidOn = Date(date.time + 2000)),
        ))
        advanceUntilIdle()
        val item = model.state.value.allSuppliers.single()
        assertThat(item.totalPurchases.amount).isEqualTo(200.0)
        assertThat(item.totalPaid.amount).isEqualTo(130.0)
        assertThat(item.outstanding.amount).isEqualTo(70.0)
        model.processAction(SupplierLedgerAction.ShowStatement(supplier.id))
        assertThat(model.state.value.statement!!.rows).hasSize(3)
        assertThat(model.state.value.statement!!.closingBalance.toPlainString()).isEqualTo("70.0")
    }

    @Test fun `saving supplier payment twice submits once to selected account`() = runTest {
        whenever(addPayment.invoke(any())).thenReturn(Resource.Success(true))
        val model = model(listOf(invoice()))
        advanceUntilIdle()
        model.processAction(SupplierLedgerAction.StartPayment(supplier.id, model.state.value.allSuppliers.single().invoices.single()))
        advanceUntilIdle()
        model.processAction(SupplierLedgerAction.UpdatePaymentAmount("80.075"))
        model.processAction(SupplierLedgerAction.SavePayment)
        model.processAction(SupplierLedgerAction.SavePayment)
        advanceUntilIdle()
        val captor = argumentCaptor<SupplierPayment>()
        verify(addPayment, times(1)).invoke(captor.capture())
        assertThat(captor.firstValue.accountId).isEqualTo("selected-cash")
        assertThat(captor.firstValue.amount).isEqualTo(80.075)
        assertThat(model.state.value.paymentDraft).isNull()
        assertThat(model.state.value.message).isNotNull()
    }

    @Test fun `ambiguous debt is not guessed as a supplier obligation`() = runTest {
        val model = model(listOf(invoice(), invoice().copy(id = "ambiguous", customerId = "customer", customerName = "Ahmed")))
        advanceUntilIdle()
        val supplier = model.state.value.allSuppliers.single()
        assertThat(supplier.invoices.map { it.transaction.id }).containsExactly("purchase")
        assertThat(supplier.outstanding.amount).isEqualTo(200.0)
    }
}
