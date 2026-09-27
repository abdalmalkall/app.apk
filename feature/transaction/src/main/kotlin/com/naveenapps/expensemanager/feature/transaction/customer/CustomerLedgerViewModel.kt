package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.GetTransactionWithFilterUseCase
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.repository.ShareRepository
import com.naveenapps.expensemanager.core.model.toTransactionUIModel
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.navigation.ExpenseManagerScreens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update

class CustomerLedgerViewModel(
    getCurrencyUseCase: GetCurrencyUseCase,
    private val getFormattedAmountUseCase: GetFormattedAmountUseCase,
    getTransactionWithFilterUseCase: GetTransactionWithFilterUseCase,
    appCoroutineDispatchers: AppCoroutineDispatchers,
    private val appComposeNavigator: AppComposeNavigator,
    private val shareRepository: ShareRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CustomerLedgerState())
    val state = _state.asStateFlow()

    init {
        combine(
            getCurrencyUseCase.invoke(),
            getTransactionWithFilterUseCase.invoke(),
        ) { currency, transactions ->
            updateCustomerLedger(currency, transactions.orEmpty())
        }.flowOn(appCoroutineDispatchers.computation).launchIn(viewModelScope)
    }

    private fun updateCustomerLedger(currency: Currency, transactions: List<Transaction>) {
        val debtTransactions = transactions.filter {
            it.type == TransactionType.DEBT && !it.customerName.isNullOrBlank()
        }

        val customers = debtTransactions
            .groupBy { it.customerName!!.trim() }
            .entries
            .toList()
            .mapIndexed { index, entry ->
                val customerName = entry.key
                val items = entry.value
                val total = items.sumOf { it.amount.amount }
                val formattedTotal = getFormattedAmountUseCase.invoke(total, currency)
                val normalizedName = customerName.filter { it.isLetterOrDigit() }.ifBlank { "CUSTOMER" }
                val invoiceNumber = "INV-${normalizedName.uppercase().take(6)}-${(index + 1).toString().padStart(3, '0')}"
                CustomerLedgerItem(
                    customerName = customerName,
                    invoiceNumber = invoiceNumber,
                    totalAmount = Amount(
                        amount = total,
                        amountString = formattedTotal.amountString,
                        currency = currency,
                    ),
                    transactionCount = items.size,
                    transactions = items.map { item ->
                        item.toTransactionUIModel(
                            getFormattedAmountUseCase.invoke(item.amount.amount, currency),
                        )
                    },
                )
            }
            .sortedByDescending { it.totalAmount.amount }

        _state.update {
            val filtered = filterCustomers(it.searchQuery, customers)
            it.copy(
                allCustomers = customers,
                filteredCustomers = filtered,
            )
        }
    }

    private fun filterCustomers(searchQuery: String, customers: List<CustomerLedgerItem>): List<CustomerLedgerItem> {
        if (searchQuery.isBlank()) return customers
        return customers.filter { it.customerName.contains(searchQuery, ignoreCase = true) }
    }

    fun processAction(action: CustomerLedgerAction) {
        when (action) {
            CustomerLedgerAction.ClosePage -> appComposeNavigator.popBackStack()
            is CustomerLedgerAction.UpdateSearch -> {
                _state.update { current ->
                    current.copy(
                        searchQuery = action.query,
                        filteredCustomers = filterCustomers(action.query, current.allCustomers),
                    )
                }
            }
            is CustomerLedgerAction.OpenTransactionEdit -> appComposeNavigator.navigate(
                ExpenseManagerScreens.TransactionCreate(action.transactionId),
            )
            is CustomerLedgerAction.ShareInvoice -> {
                shareRepository.shareInvoice(
                    customerName = action.customerName,
                    invoiceNumber = action.invoiceNumber,
                    totalAmount = action.totalAmount,
                    transactions = action.transactions,
                )
            }
        }
    }
}
