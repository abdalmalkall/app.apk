package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.lifecycle.ViewModel
import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.domain.usecase.account.GetAllAccountsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.AddCustomerPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.DeleteCustomerPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.GetAllCustomerPaymentsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.GetAllTransactionsUseCase
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Account
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.TransactionUiItem
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.repository.ShareRepository
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatRepository
import com.naveenapps.expensemanager.core.testing.BaseCoroutineTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerLedgerViewModelTest : BaseCoroutineTest() {

    private val getCurrencyUseCase: GetCurrencyUseCase = mock()
    private val getFormattedAmountUseCase: GetFormattedAmountUseCase = mock()
    private val getAllTransactionsUseCase: GetAllTransactionsUseCase = mock()
    private val getAllCustomerPaymentsUseCase: GetAllCustomerPaymentsUseCase = mock()
    private val addCustomerPaymentUseCase: AddCustomerPaymentUseCase = mock()
    private val deleteCustomerPaymentUseCase: DeleteCustomerPaymentUseCase = mock()
    private val getAllAccountsUseCase: GetAllAccountsUseCase = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val numberFormatRepository: NumberFormatRepository = mock()
    private val appComposeNavigator: AppComposeNavigator = mock()
    private val shareRepository: ShareRepository = mock()

    @Test
    fun `groups debt transactions by customer and totals them`() = runTest {
        val dispatchers = AppCoroutineDispatchers(
            io = testCoroutineDispatcher.dispatcher,
            computation = testCoroutineDispatcher.dispatcher,
            main = testCoroutineDispatcher.dispatcher,
        )
        whenever(getCurrencyUseCase.invoke()).thenReturn(flowOf(Currency(symbol = "SAR", name = "SAR")))
        whenever(getFormattedAmountUseCase.invoke(100.0, Currency(symbol = "SAR", name = "SAR")))
            .thenReturn(Amount(100.0, "100.00", Currency(symbol = "SAR", name = "SAR")))
        whenever(getFormattedAmountUseCase.invoke(250.0, Currency(symbol = "SAR", name = "SAR")))
            .thenReturn(Amount(250.0, "250.00", Currency(symbol = "SAR", name = "SAR")))
        whenever(getFormattedAmountUseCase.invoke(350.0, Currency(symbol = "SAR", name = "SAR")))
            .thenReturn(Amount(350.0, "350.00", Currency(symbol = "SAR", name = "SAR")))

        val customerA = Transaction(
            id = "1",
            notes = "",
            categoryId = "c1",
            fromAccountId = "a1",
            toAccountId = null,
            amount = Amount(100.0),
            imagePath = "",
            type = TransactionType.DEBT,
            customerName = "Ahmed",
            createdOn = Date(),
            updatedOn = Date(),
        )
        val customerB = Transaction(
            id = "2",
            notes = "",
            categoryId = "c1",
            fromAccountId = "a1",
            toAccountId = null,
            amount = Amount(250.0),
            imagePath = "",
            type = TransactionType.DEBT,
            customerName = "Ahmed",
            createdOn = Date(),
            updatedOn = Date(),
        )
        val customerC = Transaction(
            id = "3",
            notes = "",
            categoryId = "c1",
            fromAccountId = "a1",
            toAccountId = null,
            amount = Amount(350.0),
            imagePath = "",
            type = TransactionType.DEBT,
            customerName = "Zaid",
            createdOn = Date(),
            updatedOn = Date(),
        )

        whenever(getAllTransactionsUseCase.invoke()).thenReturn(flowOf(listOf(customerA, customerB, customerC)))
        whenever(getAllCustomerPaymentsUseCase.invoke()).thenReturn(flowOf(emptyList()))
        listOf(0.0, 100.0, 250.0, 350.0).forEach { amount ->
            whenever(getFormattedAmountUseCase.invoke(amount, Currency(symbol = "SAR", name = "SAR")))
                .thenReturn(Amount(amount, amount.toString(), Currency(symbol = "SAR", name = "SAR")))
        }

        val viewModel = CustomerLedgerViewModel(
            getCurrencyUseCase = getCurrencyUseCase,
            getFormattedAmountUseCase = getFormattedAmountUseCase,
            getAllTransactionsUseCase = getAllTransactionsUseCase,
            getAllCustomerPaymentsUseCase = getAllCustomerPaymentsUseCase,
            addCustomerPaymentUseCase = addCustomerPaymentUseCase,
            deleteCustomerPaymentUseCase = deleteCustomerPaymentUseCase,
            getAllAccountsUseCase = getAllAccountsUseCase,
            settingsRepository = settingsRepository,
            numberFormatRepository = numberFormatRepository,
            appCoroutineDispatchers = dispatchers,
            appComposeNavigator = appComposeNavigator,
            shareRepository = shareRepository,
        )

        advanceUntilIdle()

        val customers = viewModel.state.value.filteredCustomers

        assertThat(customers).hasSize(2)
        assertThat(customers.first().customerName).isEqualTo("Ahmed")
        assertThat(customers.first().totalAmount.amount).isEqualTo(350.0)
        assertThat(customers.first().transactionCount).isEqualTo(2)
        assertThat(customers[1].customerName).isEqualTo("Zaid")
        assertThat(customers[1].totalAmount.amount).isEqualTo(350.0)
    }

    @Test
    fun `rejected payment keeps draft visible with an error`() = runTest {
        val dispatchers = AppCoroutineDispatchers(
            io = testCoroutineDispatcher.dispatcher,
            computation = testCoroutineDispatcher.dispatcher,
            main = testCoroutineDispatcher.dispatcher,
        )
        val currency = Currency(symbol = "SAR", name = "SAR")
        whenever(getCurrencyUseCase.invoke()).thenReturn(flowOf(currency))
        whenever(getAllTransactionsUseCase.invoke()).thenReturn(flowOf(emptyList()))
        whenever(getAllCustomerPaymentsUseCase.invoke()).thenReturn(flowOf(emptyList()))

        val account: Account = mock()
        whenever(account.id).thenReturn("account")
        whenever(getAllAccountsUseCase.invoke()).thenReturn(flowOf(listOf(account)))
        whenever(settingsRepository.getDefaultAccount()).thenReturn(flowOf("account"))
        whenever(numberFormatRepository.parseToDouble("10")).thenReturn(10.0)
        whenever(addCustomerPaymentUseCase.invoke(org.mockito.kotlin.any()))
            .thenReturn(Resource.Success(false))

        val viewModel = CustomerLedgerViewModel(
            getCurrencyUseCase = getCurrencyUseCase,
            getFormattedAmountUseCase = getFormattedAmountUseCase,
            getAllTransactionsUseCase = getAllTransactionsUseCase,
            getAllCustomerPaymentsUseCase = getAllCustomerPaymentsUseCase,
            addCustomerPaymentUseCase = addCustomerPaymentUseCase,
            deleteCustomerPaymentUseCase = deleteCustomerPaymentUseCase,
            getAllAccountsUseCase = getAllAccountsUseCase,
            settingsRepository = settingsRepository,
            numberFormatRepository = numberFormatRepository,
            appCoroutineDispatchers = dispatchers,
            appComposeNavigator = appComposeNavigator,
            shareRepository = shareRepository,
        )
        val invoice = CustomerInvoiceItem(
            transaction = TransactionUiItem(
                id = "invoice",
                notes = null,
                categoryName = "Sand",
                transactionType = TransactionType.DEBT,
                categoryIcon = mock(),
                date = "",
                fromAccountName = "Cash",
                fromAccountIcon = mock(),
            ),
            paidAmount = Amount(0.0),
            remainingAmount = Amount(10.0),
            paymentStatus = CustomerPaymentStatus.UNPAID,
            payments = emptyList(),
        )

        viewModel.processAction(CustomerLedgerAction.StartPayment("customer", invoice))
        viewModel.processAction(CustomerLedgerAction.UpdatePaymentAmount("10"))
        viewModel.processAction(CustomerLedgerAction.SavePayment)
        advanceUntilIdle()

        assertThat(viewModel.state.value.paymentDraft).isNotNull()
        assertThat(viewModel.state.value.paymentDraft?.error).isTrue()
    }
}
