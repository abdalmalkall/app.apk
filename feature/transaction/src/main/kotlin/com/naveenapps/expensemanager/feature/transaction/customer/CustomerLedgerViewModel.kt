package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.domain.usecase.account.GetAllAccountsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.AddCustomerPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.DeleteCustomerPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.GetAllCustomerPaymentsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.GetAllTransactionsUseCase
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.model.toTransactionUIModel
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.navigation.ExpenseManagerScreens
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import com.naveenapps.expensemanager.core.repository.ShareRepository
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

class CustomerLedgerViewModel(
    getCurrencyUseCase: GetCurrencyUseCase,
    private val getFormattedAmountUseCase: GetFormattedAmountUseCase,
    getAllTransactionsUseCase: GetAllTransactionsUseCase,
    getAllCustomerPaymentsUseCase: GetAllCustomerPaymentsUseCase,
    private val addCustomerPaymentUseCase: AddCustomerPaymentUseCase,
    private val deleteCustomerPaymentUseCase: DeleteCustomerPaymentUseCase,
    private val getAllAccountsUseCase: GetAllAccountsUseCase,
    private val settingsRepository: SettingsRepository,
    private val numberFormatRepository: NumberFormatRepository,
    appCoroutineDispatchers: AppCoroutineDispatchers,
    private val appComposeNavigator: AppComposeNavigator,
    private val shareRepository: ShareRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CustomerLedgerState())
    val state = _state.asStateFlow()

    init {
        combine(
            getCurrencyUseCase.invoke(),
            getAllTransactionsUseCase.invoke(),
            getAllCustomerPaymentsUseCase.invoke(),
        ) { currency, transactions, payments ->
            updateCustomerLedger(currency, transactions.orEmpty(), payments)
        }.flowOn(appCoroutineDispatchers.computation).launchIn(viewModelScope)
    }

    private fun updateCustomerLedger(
        currency: Currency,
        transactions: List<Transaction>,
        payments: List<CustomerPayment>,
    ) {
        val debtTransactions = transactions.filter {
            it.type == TransactionType.DEBT && !it.customerName.isNullOrBlank()
        }

        val paymentsByInvoice = payments.groupBy { it.invoiceTransactionId }
        val customers = debtTransactions
            .groupBy { it.customerId ?: "legacy:${it.customerName!!.trim().lowercase()}" }
            .map { (customerId, items) ->
                val customerName = items.first().customerName!!.trim()
                val invoices = items.map { item ->
                    val invoicePayments = paymentsByInvoice[item.id].orEmpty()
                    val paid = invoicePayments.sumOf { it.amount }
                    val remaining = (item.amount.amount - paid).coerceAtLeast(0.0)
                    CustomerInvoiceItem(
                        transaction = item.toTransactionUIModel(
                            getFormattedAmountUseCase.invoke(item.amount.amount, currency),
                        ),
                        paidAmount = getFormattedAmountUseCase.invoke(paid, currency),
                        remainingAmount = getFormattedAmountUseCase.invoke(remaining, currency),
                        paymentStatus = when {
                            remaining <= 0.000001 -> CustomerPaymentStatus.PAID
                            paid > 0.0 -> CustomerPaymentStatus.PARTIALLY_PAID
                            else -> CustomerPaymentStatus.UNPAID
                        },
                        payments = invoicePayments,
                    )
                }
                val totalSales = items.sumOf { it.amount.amount }
                val totalPaid = invoices.sumOf { it.paidAmount.amount }
                val outstanding = invoices.sumOf { it.remainingAmount.amount }
                CustomerLedgerItem(
                    id = customerId,
                    customerName = customerName,
                    phone = items.firstNotNullOfOrNull { it.customerPhone?.takeIf(String::isNotBlank) },
                    totalSales = getFormattedAmountUseCase.invoke(totalSales, currency),
                    totalPaid = getFormattedAmountUseCase.invoke(totalPaid, currency),
                    totalAmount = getFormattedAmountUseCase.invoke(outstanding, currency),
                    transactionCount = items.size,
                    transactions = invoices,
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
        return customers.filter {
            it.customerName.contains(searchQuery, ignoreCase = true) ||
                it.phone.orEmpty().contains(searchQuery, ignoreCase = true)
        }
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
            is CustomerLedgerAction.ToggleCustomerDetails -> {
                _state.update { current ->
                    current.copy(
                        expandedCustomerId = current.expandedCustomerId.takeUnless { it == action.customerId }
                            ?: action.customerId,
                    )
                }
            }
            is CustomerLedgerAction.OpenTransactionEdit -> appComposeNavigator.navigate(
                ExpenseManagerScreens.TransactionCreate(action.transactionId),
            )
            is CustomerLedgerAction.StartPayment -> {
                val transaction = action.invoice.transaction
                _state.update {
                    it.copy(
                        paymentDraft = CustomerPaymentDraft(
                            customerId = action.customerId,
                            invoiceTransactionId = transaction.id,
                            invoiceNumber = transaction.invoiceNumber.orEmpty(),
                            remainingAmount = action.invoice.remainingAmount,
                        ),
                    )
                }
            }
            is CustomerLedgerAction.UpdatePaymentAmount -> {
                _state.update { current ->
                    current.copy(paymentDraft = current.paymentDraft?.copy(amount = action.amount, error = false))
                }
            }
            CustomerLedgerAction.DismissPayment -> _state.update { it.copy(paymentDraft = null) }
            CustomerLedgerAction.SavePayment -> savePayment()
            is CustomerLedgerAction.DeletePayment -> viewModelScope.launch {
                deleteCustomerPaymentUseCase.invoke(action.paymentId)
            }
            is CustomerLedgerAction.ShareInvoice -> {
                shareRepository.shareInvoice(
                    customerName = action.customerName,
                    phone = action.phone,
                    invoiceNumber = action.invoiceNumber,
                    totalAmount = action.totalAmount,
                    transactions = action.transactions,
                )
            }
            is CustomerLedgerAction.ShareCustomerStatement -> {
                shareRepository.shareCustomerStatement(
                    customerName = action.customerName,
                    phone = action.phone,
                    totalAmount = action.totalAmount,
                    transactions = action.transactions,
                )
            }
        }
    }

    private fun savePayment() {
        val draft = _state.value.paymentDraft ?: return
        val amount = numberFormatRepository.parseToDouble(draft.amount)
        if (amount == null || amount <= 0.0 || amount > draft.remainingAmount.amount) {
            _state.update { it.copy(paymentDraft = draft.copy(error = true)) }
            return
        }
        viewModelScope.launch {
            val accounts = getAllAccountsUseCase.invoke().first()
            val preferredId = settingsRepository.getDefaultAccount().firstOrNull()
            val account = accounts.firstOrNull { it.id == preferredId } ?: accounts.firstOrNull()
            if (account == null) {
                _state.update { it.copy(paymentDraft = draft.copy(error = true)) }
                return@launch
            }
            val payment = CustomerPayment(
                id = UUID.randomUUID().toString(),
                customerId = draft.customerId,
                invoiceTransactionId = draft.invoiceTransactionId,
                accountId = account.id,
                amount = amount,
                paidOn = Date(),
            )
            when (addCustomerPaymentUseCase.invoke(payment)) {
                is Resource.Success -> _state.update { it.copy(paymentDraft = null) }
                is Resource.Error -> _state.update { it.copy(paymentDraft = draft.copy(error = true)) }
            }
        }
    }
}
