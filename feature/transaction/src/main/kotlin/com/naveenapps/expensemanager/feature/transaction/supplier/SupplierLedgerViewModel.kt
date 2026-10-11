package com.naveenapps.expensemanager.feature.transaction.supplier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.domain.usecase.account.GetAllAccountsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.supplier.AddSupplierPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.supplier.DeleteSupplierPaymentUseCase
import com.naveenapps.expensemanager.core.domain.usecase.supplier.GetAllSupplierPaymentsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.supplier.GetAllSuppliersUseCase
import com.naveenapps.expensemanager.core.domain.usecase.supplier.SaveSupplierUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.GetAllTransactionsUseCase
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.Supplier
import com.naveenapps.expensemanager.core.model.SupplierPayment
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.model.toTransactionUIModel
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.navigation.ExpenseManagerScreens
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatRepository
import com.naveenapps.expensemanager.core.model.LedgerMoney
import com.naveenapps.expensemanager.core.model.StatementMovement
import com.naveenapps.expensemanager.core.model.buildAccountStatement
import com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter
import com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort
import com.naveenapps.expensemanager.feature.transaction.R
import kotlinx.coroutines.flow.catch
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SupplierLedgerViewModel(
    getCurrencyUseCase: GetCurrencyUseCase,
    private val getFormattedAmountUseCase: GetFormattedAmountUseCase,
    getAllTransactionsUseCase: GetAllTransactionsUseCase,
    getAllSuppliersUseCase: GetAllSuppliersUseCase,
    getAllSupplierPaymentsUseCase: GetAllSupplierPaymentsUseCase,
    private val saveSupplierUseCase: SaveSupplierUseCase,
    private val addSupplierPaymentUseCase: AddSupplierPaymentUseCase,
    private val deleteSupplierPaymentUseCase: DeleteSupplierPaymentUseCase,
    private val getAllAccountsUseCase: GetAllAccountsUseCase,
    private val settingsRepository: SettingsRepository,
    private val numberFormatRepository: NumberFormatRepository,
    appCoroutineDispatchers: AppCoroutineDispatchers,
    private val navigator: AppComposeNavigator,
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierLedgerState())
    val state = _state.asStateFlow()

    init {
        combine(
            getCurrencyUseCase.invoke(),
            getAllTransactionsUseCase.invoke(),
            getAllSuppliersUseCase.invoke(),
            getAllSupplierPaymentsUseCase.invoke(),
        ) { currency, transactions, suppliers, payments ->
            updateLedger(currency, transactions.orEmpty(), suppliers, payments)
        }.catch { _state.update { it.copy(isLoading = false, message = R.string.ledger_load_error) } }.flowOn(appCoroutineDispatchers.computation).launchIn(viewModelScope)
    }

    private fun formatMoney(value: java.math.BigDecimal, currency: Currency): Amount =
        getFormattedAmountUseCase.invoke(value.toDouble(), currency).copy(amountDecimal = value.toPlainString())

    private fun updateLedger(
        currency: Currency,
        transactions: List<Transaction>,
        suppliers: List<Supplier>,
        payments: List<SupplierPayment>,
    ) {
        val purchasesBySupplier = transactions
            .filter { it.supplierId != null && it.customerId == null && it.customerName.isNullOrBlank() &&
                it.type in setOf(TransactionType.EXPENSE, TransactionType.DEBT) }
            .groupBy { it.supplierId!! }
        val paymentsByInvoice = payments.groupBy { it.invoiceTransactionId }
        val supplierProfiles = suppliers.associateBy { it.id }.toMutableMap()
        purchasesBySupplier.forEach { (supplierId, purchases) ->
            if (supplierId !in supplierProfiles) {
                val firstPurchase = purchases.first()
                supplierProfiles[supplierId] = Supplier(
                    id = supplierId,
                    name = firstPurchase.supplierName?.takeIf(String::isNotBlank) ?: "Unknown supplier",
                    createdOn = firstPurchase.createdOn,
                    updatedOn = firstPurchase.updatedOn,
                )
            }
        }

        val ledgerItems = supplierProfiles.values.map { supplier ->
            val purchases = purchasesBySupplier[supplier.id].orEmpty()
            val invoices = purchases.sortedWith(compareByDescending<Transaction> { it.createdOn.time }.thenBy { it.id }).map { transaction ->
                val invoicePayments = if (transaction.type == TransactionType.DEBT) {
                    paymentsByInvoice[transaction.id].orEmpty()
                } else {
                    emptyList()
                }
                val paid = if (transaction.type == TransactionType.EXPENSE) {
                    LedgerMoney.exact(transaction.amount.amount, transaction.amount.amountDecimal)
                } else {
                    LedgerMoney.totalExact(invoicePayments.map { LedgerMoney.exact(it.amount, it.amountDecimal) })
                }
                val remaining = if (transaction.type == TransactionType.DEBT) {
                    LedgerMoney.exact(transaction.amount.amount, transaction.amount.amountDecimal).subtract(paid).max(java.math.BigDecimal.ZERO)
                } else {
                    java.math.BigDecimal.ZERO
                }
                SupplierInvoiceItem(
                    transaction = transaction.toTransactionUIModel(
                        formatMoney(LedgerMoney.exact(transaction.amount.amount, transaction.amount.amountDecimal), currency),
                    ),
                    paid = formatMoney(paid, currency),
                    remaining = formatMoney(remaining, currency),
                    payments = invoicePayments,
                )
            }
            SupplierLedgerItem(
                supplier = supplier,
                totalPurchases = formatMoney(LedgerMoney.totalExact(purchases.map { LedgerMoney.exact(it.amount.amount, it.amount.amountDecimal) }), currency),
                totalPaid = formatMoney(LedgerMoney.totalExact(invoices.map { LedgerMoney.exact(it.paid.amount, it.paid.amountDecimal) }), currency),
                outstanding = formatMoney(LedgerMoney.totalExact(invoices.map { LedgerMoney.exact(it.remaining.amount, it.remaining.amountDecimal) }), currency),
                invoices = invoices,
                lastPurchase = purchases.maxOfOrNull { it.createdOn.time } ?: 0L,
                lastActivity = (purchases.map { it.createdOn.time } + invoices.flatMap { it.payments }.map { it.paidOn.time }).maxOrNull() ?: 0L,
                movements = purchases.flatMap { invoice ->
                    listOf(StatementMovement(invoice.id, invoice.createdOn.time, invoice.invoiceNumber ?: invoice.id, invoice.notes,
                        obligation = LedgerMoney.exact(invoice.amount.amount, invoice.amount.amountDecimal))) +
                        if (invoice.type == TransactionType.EXPENSE) listOf(StatementMovement("cash:${invoice.id}", invoice.createdOn.time,
                            invoice.invoiceNumber ?: invoice.id, invoice.notes, payment = LedgerMoney.exact(invoice.amount.amount, invoice.amount.amountDecimal), invoiceId = invoice.id))
                        else paymentsByInvoice[invoice.id].orEmpty().map { payment ->
                            StatementMovement(payment.id, payment.paidOn.time, invoice.invoiceNumber ?: invoice.id, payment.notes,
                                payment = LedgerMoney.exact(payment.amount, payment.amountDecimal), invoiceId = invoice.id)
                        }
                },
            )
        }.sortedByDescending { it.outstanding.amount }

        _state.update { current ->
            current.copy(
                recentPurchases = transactions.filter { it.supplierId != null && it.type in setOf(TransactionType.EXPENSE, TransactionType.DEBT) }
                    .sortedWith(compareByDescending<Transaction> { it.createdOn.time }.thenBy { it.id }).take(5)
                    .map { it.toTransactionUIModel(formatMoney(LedgerMoney.exact(it.amount.amount, it.amount.amountDecimal), currency)) },
                isLoading = false,
                totalOutstanding = formatMoney(LedgerMoney.totalExact(ledgerItems.map { LedgerMoney.exact(it.outstanding.amount, it.outstanding.amountDecimal) }), currency).amountString.orEmpty(),
                allSuppliers = ledgerItems,
                filteredSuppliers = filterSuppliers(current.searchQuery, ledgerItems),
            )
        }
    }

    private fun filterSuppliers(query: String, suppliers: List<SupplierLedgerItem>,
        filter: DebtFilter = _state.value.debtFilter, sort: LedgerSort = _state.value.sort): List<SupplierLedgerItem> {
        val filtered = suppliers.filter {
            (query.isBlank() || it.supplier.name.contains(query, true) || it.supplier.phone.orEmpty().contains(query, true)) &&
                when (filter) { DebtFilter.ALL -> true; DebtFilter.OUTSTANDING -> it.outstanding.amount > 0; DebtFilter.SETTLED -> it.outstanding.amount == 0.0 }
        }
        return when (sort) {
            LedgerSort.NAME -> filtered.sortedBy { it.supplier.name.lowercase() }
            LedgerSort.DEBT -> filtered.sortedByDescending { it.outstanding.amount }
            LedgerSort.LAST_ACTIVITY -> filtered.sortedByDescending { it.lastActivity }
        }
    }

    fun processAction(action: SupplierLedgerAction) {
        when (action) {
            is SupplierLedgerAction.SetFilter -> _state.update { it.copy(debtFilter = action.filter, filteredSuppliers = filterSuppliers(it.searchQuery, it.allSuppliers, action.filter, it.sort)) }
            is SupplierLedgerAction.SetSort -> _state.update { it.copy(sort = action.sort, filteredSuppliers = filterSuppliers(it.searchQuery, it.allSuppliers, it.debtFilter, action.sort)) }
            is SupplierLedgerAction.ShowStatement -> {
                val item = _state.value.allSuppliers.firstOrNull { it.supplier.id == action.id } ?: return
                _state.update { it.copy(statementMovements = item.movements, statement = buildAccountStatement(item.supplier.name, item.supplier.phone, item.supplier.address, item.movements, 0L, Date().time)) }
            }
            is SupplierLedgerAction.StatementPeriod -> _state.update {
                val statement = it.statement ?: return@update it
                it.copy(statement = buildAccountStatement(statement.name, statement.phone, statement.address, it.statementMovements, action.from, action.to))
            }
            SupplierLedgerAction.DismissStatement -> _state.update { it.copy(statement = null) }
            SupplierLedgerAction.DismissMessage -> _state.update { it.copy(message = null) }
            is SupplierLedgerAction.PaymentDetails -> _state.update { it.copy(paymentDraft = it.paymentDraft?.copy(paidOn = action.paidOn, method = action.method, notes = action.notes)) }
            SupplierLedgerAction.ClosePage -> navigator.popBackStack()
            is SupplierLedgerAction.UpdateSearch -> _state.update {
                it.copy(searchQuery = action.query, filteredSuppliers = filterSuppliers(action.query, it.allSuppliers))
            }
            SupplierLedgerAction.AddSupplier -> _state.update { it.copy(supplierDraft = SupplierDraft()) }
            is SupplierLedgerAction.EditSupplier -> {
                val supplier = _state.value.allSuppliers.firstOrNull { it.supplier.id == action.supplierId }?.supplier ?: return
                _state.update {
                    it.copy(
                        supplierDraft = SupplierDraft(
                            id = supplier.id,
                            name = supplier.name,
                            phone = supplier.phone.orEmpty(),
                            address = supplier.address.orEmpty(),
                            companyName = supplier.companyName.orEmpty(),
                            notes = supplier.notes.orEmpty(),
                            existing = supplier,
                        ),
                    )
                }
            }
            is SupplierLedgerAction.UpdateSupplierField -> _state.update { current ->
                val draft = current.supplierDraft ?: return@update current
                current.copy(supplierDraft = when (action.field) {
                    SupplierProfileField.NAME -> draft.copy(name = action.value, error = false)
                    SupplierProfileField.PHONE -> draft.copy(phone = action.value, error = false)
                    SupplierProfileField.ADDRESS -> draft.copy(address = action.value, error = false)
                    SupplierProfileField.COMPANY_NAME -> draft.copy(companyName = action.value, error = false)
                    SupplierProfileField.NOTES -> draft.copy(notes = action.value, error = false)
                })
            }
            SupplierLedgerAction.DismissSupplierEditor -> _state.update { it.copy(supplierDraft = null) }
            SupplierLedgerAction.SaveSupplier -> saveSupplier()
            is SupplierLedgerAction.ToggleSupplier -> _state.update {
                it.copy(expandedSupplierId = if (it.expandedSupplierId == action.supplierId) null else action.supplierId)
            }
            is SupplierLedgerAction.StartPayment -> startPayment(action)
            is SupplierLedgerAction.UpdatePaymentAmount -> _state.update { current ->
                current.copy(paymentDraft = current.paymentDraft?.copy(amount = action.amount, error = false))
            }
            is SupplierLedgerAction.SelectPaymentAccount -> _state.update { current ->
                current.copy(paymentDraft = current.paymentDraft?.copy(accountId = action.accountId, error = false))
            }
            SupplierLedgerAction.DismissPayment -> _state.update { it.copy(paymentDraft = null) }
            SupplierLedgerAction.SavePayment -> savePayment()
            is SupplierLedgerAction.DeletePayment -> viewModelScope.launch {
                val result = deleteSupplierPaymentUseCase.invoke(action.paymentId)
                _state.update { it.copy(message = if (result is Resource.Success && result.data) R.string.ledger_saved else R.string.ledger_save_error) }
            }
            is SupplierLedgerAction.OpenPurchase -> navigator.navigate(ExpenseManagerScreens.TransactionCreate(action.id))
            is SupplierLedgerAction.StartPurchase -> navigator.navigate(ExpenseManagerScreens.TransactionCreate(null, action.supplierId))
        }
    }

    private fun saveSupplier() {
        val draft = _state.value.supplierDraft ?: return
        if (draft.isSaving) return
        if (draft.name.isBlank()) {
            _state.update { it.copy(supplierDraft = draft.copy(error = true)) }
            return
        }
        _state.update { it.copy(supplierDraft = draft.copy(isSaving = true, error = false)) }
        viewModelScope.launch {
            val now = Date()
            val supplier = Supplier(
                id = draft.id ?: UUID.randomUUID().toString(),
                name = draft.name,
                phone = draft.phone,
                address = draft.address,
                companyName = draft.companyName,
                notes = draft.notes,
                createdOn = draft.existing?.createdOn ?: now,
                updatedOn = now,
            )
            when (val result = saveSupplierUseCase.invoke(supplier)) {
                is Resource.Success -> _state.update {
                    it.copy(supplierDraft = if (result.data) null else draft.copy(isSaving = false, error = true), message = if (result.data) R.string.ledger_saved else null)
                }
                is Resource.Error -> _state.update { it.copy(supplierDraft = draft.copy(isSaving = false, error = true)) }
            }
        }
    }

    private fun startPayment(action: SupplierLedgerAction.StartPayment) {
        viewModelScope.launch {
            val accounts = getAllAccountsUseCase.invoke().first()
            val preferredId = settingsRepository.getDefaultAccount().firstOrNull()
            val selectedAccount = accounts.firstOrNull { it.id == preferredId } ?: accounts.firstOrNull()
            _state.update { current ->
                current.copy(
                    accounts = accounts,
                    paymentDraft = SupplierPaymentDraft(
                        paymentId = UUID.randomUUID().toString(),
                        supplierId = action.supplierId,
                        invoiceTransactionId = action.invoice.transaction.id,
                        remaining = action.invoice.remaining,
                        accountId = selectedAccount?.id,
                    ),
                )
            }
        }
    }

    private fun savePayment() {
        val draft = _state.value.paymentDraft ?: return
        if (draft.isSaving) return
        val amount = LedgerMoney.input(draft.amount)?.toDouble()
        val accountId = draft.accountId
        if (amount == null || !amount.isFinite() || amount <= 0.0 || amount > draft.remaining.amount || accountId == null || draft.method.isBlank()) {
            _state.update { it.copy(paymentDraft = draft.copy(error = true)) }
            return
        }
        _state.update { it.copy(paymentDraft = draft.copy(isSaving = true, error = false)) }
        viewModelScope.launch {
            val result = addSupplierPaymentUseCase.invoke(
                SupplierPayment(
                    id = draft.paymentId,
                    supplierId = draft.supplierId,
                    invoiceTransactionId = draft.invoiceTransactionId,
                    accountId = accountId,
                    amount = amount,
                    paidOn = draft.paidOn,
                    notes = "${draft.method}: ${draft.notes}",
                ),
            )
            when (result) {
                is Resource.Success -> _state.update {
                    it.copy(paymentDraft = if (result.data) null else draft.copy(isSaving = false, error = true), message = if (result.data) R.string.ledger_saved else null)
                }
                is Resource.Error -> _state.update { it.copy(paymentDraft = draft.copy(isSaving = false, error = true)) }
            }
        }
    }
}
