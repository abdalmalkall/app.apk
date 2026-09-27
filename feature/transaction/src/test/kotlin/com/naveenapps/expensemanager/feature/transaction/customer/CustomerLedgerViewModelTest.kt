package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.lifecycle.ViewModel
import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.GetTransactionWithFilterUseCase
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
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
    private val getTransactionWithFilterUseCase: GetTransactionWithFilterUseCase = mock()
    private val appComposeNavigator: AppComposeNavigator = mock()

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

        whenever(getTransactionWithFilterUseCase.invoke()).thenReturn(flowOf(listOf(customerA, customerB, customerC)))

        val viewModel = CustomerLedgerViewModel(
            getCurrencyUseCase = getCurrencyUseCase,
            getFormattedAmountUseCase = getFormattedAmountUseCase,
            getTransactionWithFilterUseCase = getTransactionWithFilterUseCase,
            appCoroutineDispatchers = dispatchers,
            appComposeNavigator = appComposeNavigator,
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
}
