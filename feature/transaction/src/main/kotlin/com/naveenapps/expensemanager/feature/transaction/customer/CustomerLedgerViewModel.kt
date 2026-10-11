package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.domain.usecase.account.GetAllAccountsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.AddCustomerPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.GetAllCustomersUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.SaveCustomerUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.DeleteCustomerPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.customer.GetAllCustomerPaymentsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.GetAllTransactionsUseCase
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.model.Customer
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
import com.naveenapps.expensemanager.core.model.LedgerMoney
import com.naveenapps.expensemanager.core.model.StatementMovement
import com.naveenapps.expensemanager.core.model.buildAccountStatement
import com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter
import com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort
import com.naveenapps.expensemanager.feature.transaction.R
import kotlinx.coroutines.flow.catch
import java.util.Date
import java.util.UUID

class CustomerLedgerViewModel(
    getCurrencyUseCase: GetCurrencyUseCase,
    private val getFormattedAmountUseCase: GetFormattedAmountUseCase,
    getAllTransactionsUseCase: GetAllTransactionsUseCase,
    getAllCustomerPaymentsUseCase: GetAllCustomerPaymentsUseCase,
    private val getAllCustomersUseCase: GetAllCustomersUseCase,
    private val saveCustomerUseCase: SaveCustomerUseCase,
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
            getAllCustomersUseCase.invoke(),
        ) { currency, transactions, payments, customers ->
            updateCustomerLedger(currency, transactions.orEmpty(), payments, customers)
        }.catch { _state.update { it.copy(isLoading = false, message = R.string.ledger_load_error) } }.flowOn(appCoroutineDispatchers.computation).launchIn(viewModelScope)
    }

    private fun formatMoney(value: java.math.BigDecimal, currency: Currency): Amount =
        getFormattedAmountUseCase.invoke(value.toDouble(), currency).copy(amountDecimal = value.toPlainString())

    private fun updateCustomerLedger(
        currency: Currency,
        transactions: List<Transaction>,
        payments: List<CustomerPayment>,
        registeredCustomers: List<Customer>,
    ) {
        val debtTransactions = transactions.filter {
            it.type == TransactionType.DEBT && !it.customerName.isNullOrBlank() &&
                it.supplierId == null && it.supplierName.isNullOrBlank() &&
                it.invoiceNumber?.startsWith("PUR-") != true
        }

        val paymentsByInvoice = payments.groupBy { it.invoiceTransactionId }
        val groupedInvoices = debtTransactions
            .groupBy { it.customerId ?: "legacy:${it.customerName!!.trim().lowercase(java.util.Locale.ROOT)}" }
            .toMutableMap()
        val ledgerCustomers = registeredCustomers.map { profile ->
            val items = groupedInvoices.remove(profile.id).orEmpty()
            buildCustomerLedgerItem(
                customerId = profile.id,
                customerName = profile.name,
                phone = profile.phone,
                address = profile.address,
                notes = profile.notes,
                creditLimitMilliunits = profile.creditLimitMilliunits,
                profile = profile,
                items = items,
                currency = currency,
                paymentsByInvoice = paymentsByInvoice,
            )
        } + groupedInvoices.map { (customerId, items) ->
            buildCustomerLedgerItem(
                customerId = customerId,
                customerName = items.first().customerName!!.trim(),
                phone = items.firstNotNullOfOrNull { it.customerPhone?.takeIf(String::isNotBlank) },
                items = items,
                currency = currency,
                paymentsByInvoice = paymentsByInvoice,
            )
        }

        _state.update {
            val filtered = filterCustomers(it.searchQuery, ledgerCustomers, it.debtFilter, it.sort)
            it.copy(
                isLoading = false,
                totalOutstanding = formatMoney(LedgerMoney.totalExact(ledgerCustomers.map { LedgerMoney.exact(it.totalAmount.amount, it.totalAmount.amountDecimal) }), currency).amountString.orEmpty(),
                allCustomers = ledgerCustomers,
                filteredCustomers = filtered,
            )
        }
    }

    private fun buildCustomerLedgerItem(
        customerId: String,
        customerName: String,
        phone: String?,
        items: List<Transaction>,
        currency: Currency,
        paymentsByInvoice: Map<String, List<CustomerPayment>>,
        address: String? = null,
        notes: String? = null,
        creditLimitMilliunits: Long = 0L,
        profile: Customer? = null,
    ): CustomerLedgerItem {
                val invoices = items.sortedWith(compareByDescending<Transaction> { it.createdOn.time }.thenBy { it.id }).map { item ->
                    val invoicePayments = paymentsByInvoice[item.id].orEmpty()
                    val paid = LedgerMoney.totalExact(invoicePayments.map { LedgerMoney.exact(it.amount, it.amountDecimal) })
                    val remaining = LedgerMoney.exact(item.amount.amount, item.amount.amountDecimal).subtract(paid).max(java.math.BigDecimal.ZERO)
                    CustomerInvoiceItem(
                        transaction = item.toTransactionUIModel(
                            formatMoney(LedgerMoney.exact(item.amount.amount, item.amount.amountDecimal), currency),
                        ),
                        paidAmount = formatMoney(paid, currency),
                        remainingAmount = formatMoney(remaining, currency),
                        paymentStatus = when {
                            remaining.signum() == 0 -> CustomerPaymentStatus.PAID
                            paid.signum() > 0 -> CustomerPaymentStatus.PARTIALLY_PAID
                            else -> CustomerPaymentStatus.UNPAID
                        },
                        payments = invoicePayments,
                    )
                }
                val totalSales = LedgerMoney.totalExact(items.map { LedgerMoney.exact(it.amount.amount, it.amount.amountDecimal) })
                val totalPaid = LedgerMoney.totalExact(invoices.map { LedgerMoney.exact(it.paidAmount.amount, it.paidAmount.amountDecimal) })
                val outstanding = LedgerMoney.totalExact(invoices.map { LedgerMoney.exact(it.remainingAmount.amount, it.remainingAmount.amountDecimal) })
            return CustomerLedgerItem(
                    id = customerId,
                    customerName = customerName,
                    phone = phone,
                    totalSales = formatMoney(totalSales, currency),
                    totalPaid = formatMoney(totalPaid, currency),
                    totalAmount = formatMoney(outstanding, currency),
                    transactionCount = items.size,
                    transactions = invoices,
                    address = address,
                    notes = notes,
                    creditLimitMilliunits = creditLimitMilliunits,
                    profile = profile,
                    lastActivity = (items.map { it.createdOn.time } + invoices.flatMap { it.payments }.map { it.paidOn.time }).maxOrNull() ?: 0L,
                    movements = items.flatMap { invoice ->
                        listOf(StatementMovement(invoice.id, invoice.createdOn.time, invoice.invoiceNumber ?: invoice.id, invoice.notes,
                            obligation = LedgerMoney.exact(invoice.amount.amount, invoice.amount.amountDecimal))) + paymentsByInvoice[invoice.id].orEmpty().map { payment ->
                            StatementMovement(payment.id, payment.paidOn.time, invoice.invoiceNumber ?: invoice.id, payment.notes,
                                payment = LedgerMoney.exact(payment.amount, payment.amountDecimal), invoiceId = invoice.id)
                        }
                    },
            )
    }

    private fun filterCustomers(searchQuery: String, customers: List<CustomerLedgerItem>,
        filter: DebtFilter = _state.value.debtFilter, sort: LedgerSort = _state.value.sort): List<CustomerLedgerItem> {
        val filtered = customers.filter {
            (searchQuery.isBlank() || it.customerName.contains(searchQuery, true) || it.phone.orEmpty().contains(searchQuery, true)) &&
                when (filter) { DebtFilter.ALL -> true; DebtFilter.OUTSTANDING -> it.totalAmount.amount > 0; DebtFilter.SETTLED -> it.totalAmount.amount == 0.0 }
        }
        return when (sort) {
            LedgerSort.NAME -> filtered.sortedBy { it.customerName.lowercase() }
            LedgerSort.DEBT -> filtered.sortedByDescending { it.totalAmount.amount }
            LedgerSort.LAST_ACTIVITY -> filtered.sortedByDescending { it.lastActivity }
        }
    }

    fun processAction(action: CustomerLedgerAction) {
        when (action) {
            is CustomerLedgerAction.SetFilter -> _state.update { it.copy(debtFilter = action.filter, filteredCustomers = filterCustomers(it.searchQuery, it.allCustomers, action.filter, it.sort)) }
            is CustomerLedgerAction.SetSort -> _state.update { it.copy(sort = action.sort, filteredCustomers = filterCustomers(it.searchQuery, it.allCustomers, it.debtFilter, action.sort)) }
            is CustomerLedgerAction.ShowStatement -> {
                val item = _state.value.allCustomers.firstOrNull { it.id == action.id } ?: return
                _state.update { it.copy(statementMovements = item.movements, statement = buildAccountStatement(item.customerName, item.phone, item.address, item.movements, 0L, Date().time)) }
            }
            is CustomerLedgerAction.StatementPeriod -> _state.update {
                val statement = it.statement ?: return@update it
                it.copy(statement = buildAccountStatement(statement.name, statement.phone, statement.address, it.statementMovements, action.from, action.to))
            }
            CustomerLedgerAction.DismissStatement -> _state.update { it.copy(statement = null) }
            CustomerLedgerAction.DismissMessage -> _state.update { it.copy(message = null) }
            is CustomerLedgerAction.PaymentDetails -> _state.update { it.copy(paymentDraft = it.paymentDraft?.copy(paidOn = action.paidOn, method = action.method, notes = action.notes)) }
            is CustomerLedgerAction.SelectPaymentAccount -> _state.update { it.copy(paymentDraft = it.paymentDraft?.copy(accountId = action.accountId)) }
            is CustomerLedgerAction.StartInvoice -> appComposeNavigator.navigate(ExpenseManagerScreens.TransactionCreate(null, customerId = action.customerId))
            CustomerLedgerAction.ClosePage -> appComposeNavigator.popBackStack()
            CustomerLedgerAction.AddCustomer -> _state.update { it.copy(customerDraft = CustomerDraft()) }
            is CustomerLedgerAction.EditCustomer -> {
                val customer = _state.value.allCustomers.firstOrNull { it.id == action.customerId } ?: return
                _state.update {
                    it.copy(
                        customerDraft = CustomerDraft(
                            id = customer.id,
                            name = customer.customerName,
                            phone = customer.phone.orEmpty(),
                            address = customer.address.orEmpty(),
                            notes = customer.notes.orEmpty(),
                            creditLimit = if (customer.creditLimitMilliunits == 0L) "" else {
                                java.math.BigDecimal.valueOf(customer.creditLimitMilliunits, 3).stripTrailingZeros().toPlainString()
                            },
                            existingCustomer = customer.profile,
                        ),
                    )
                }
            }
            is CustomerLedgerAction.UpdateCustomerDraft -> _state.update { current ->
                val draft = current.customerDraft ?: return@update current
                current.copy(customerDraft = when (action.field) {
                    CustomerProfileField.NAME -> draft.copy(name = action.value, error = false)
                    CustomerProfileField.PHONE -> draft.copy(phone = action.value, error = false)
                    CustomerProfileField.ADDRESS -> draft.copy(address = action.value, error = false)
                    CustomerProfileField.NOTES -> draft.copy(notes = action.value, error = false)
                    CustomerProfileField.CREDIT_LIMIT -> draft.copy(creditLimit = action.value, error = false)
                })
            }
            CustomerLedgerAction.DismissCustomerEditor -> _state.update { it.copy(customerDraft = null) }
            CustomerLedgerAction.SaveCustomer -> saveCustomer()
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
                        expandedCustomerId = if (current.expandedCustomerId == action.customerId) null else action.customerId,
                    )
                }
            }
            is CustomerLedgerAction.OpenTransactionEdit -> appComposeNavigator.navigate(
                ExpenseManagerScreens.TransactionCreate(action.transactionId),
            )
            is CustomerLedgerAction.StartPayment -> viewModelScope.launch {
                val accounts = getAllAccountsUseCase.invoke().first()
                val preferredId = settingsRepository.getDefaultAccount().firstOrNull()
                _state.update { it.copy(accounts = accounts, paymentDraft = CustomerPaymentDraft(
                    paymentId = UUID.randomUUID().toString(), customerId = action.customerId,
                    invoiceTransactionId = action.invoice.transaction.id, invoiceNumber = action.invoice.transaction.invoiceNumber.orEmpty(),
                    remainingAmount = action.invoice.remainingAmount,
                    accountId = accounts.firstOrNull { it.id == preferredId }?.id ?: accounts.firstOrNull()?.id,
                )) }
            }
            is CustomerLedgerAction.UpdatePaymentAmount -> {
                _state.update { current ->
                    current.copy(paymentDraft = current.paymentDraft?.copy(amount = action.amount, error = false))
                }
            }
            CustomerLedgerAction.DismissPayment -> _state.update { it.copy(paymentDraft = null) }
            CustomerLedgerAction.SavePayment -> savePayment()
            is CustomerLedgerAction.DeletePayment -> viewModelScope.launch {
                val result = deleteCustomerPaymentUseCase.invoke(action.paymentId)
                _state.update { it.copy(message = if (result is Resource.Success && result.data) R.string.ledger_saved else R.string.ledger_save_error) }
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

    private fun saveCustomer() {
        val draft = _state.value.customerDraft ?: return
        if (draft.isSaving) return
        val name = draft.name.trim()
        val creditLimit = if (draft.creditLimit.isBlank()) 0L else {
            val parsed = LedgerMoney.input(draft.creditLimit)
            if (parsed == null || parsed.signum() < 0) {
                _state.update { it.copy(customerDraft = draft.copy(error = true)) }
                return
            }
            try { parsed.movePointRight(3).longValueExact() } catch (_: ArithmeticException) {
                _state.update { it.copy(customerDraft = draft.copy(error = true)) }
                return
            }
        }
        if (name.isBlank()) {
            _state.update { it.copy(customerDraft = draft.copy(error = true)) }
            return
        }
        _state.update { it.copy(customerDraft = draft.copy(isSaving = true, error = false)) }
        viewModelScope.launch {
            val now = Date()
            val customer = Customer(
                id = draft.id ?: UUID.randomUUID().toString(),
                name = name,
                phone = draft.phone,
                address = draft.address,
                notes = draft.notes,
                creditLimitMilliunits = creditLimit,
                createdOn = draft.existingCustomer?.createdOn ?: now,
                updatedOn = now,
            )
            when (val result = saveCustomerUseCase.invoke(customer)) {
                is Resource.Success -> _state.update {
                    it.copy(customerDraft = if (result.data) null else draft.copy(isSaving = false, error = true), message = if (result.data) R.string.ledger_saved else null)
                }
                is Resource.Error -> _state.update { it.copy(customerDraft = draft.copy(isSaving = false, error = true)) }
            }
        }
    }

    private fun savePayment() {
        val draft = _state.value.paymentDraft ?: return
        if (draft.isSaving) return
        val amount = LedgerMoney.input(draft.amount)?.toDouble()
        if (amount == null || amount <= 0.0 || amount > draft.remainingAmount.amount || draft.accountId == null || draft.method.isBlank()) {
            _state.update { it.copy(paymentDraft = draft.copy(error = true)) }
            return
        }
        _state.update { it.copy(paymentDraft = draft.copy(isSaving = true, error = false)) }
        viewModelScope.launch {
            val payment = CustomerPayment(
                id = draft.paymentId,
                customerId = draft.customerId,
                invoiceTransactionId = draft.invoiceTransactionId,
                accountId = draft.accountId!!,
                amount = amount,
                paidOn = draft.paidOn,
                notes = "${draft.method}: ${draft.notes}",
            )
            when (val result = addCustomerPaymentUseCase.invoke(payment)) {
                is Resource.Success -> _state.update {
                    it.copy(paymentDraft = if (result.data) null else draft.copy(error = true, isSaving = false), message = if (result.data) R.string.ledger_saved else null)
                }
                is Resource.Error -> _state.update { it.copy(paymentDraft = draft.copy(error = true, isSaving = false)) }
            }
        }
    }
}
