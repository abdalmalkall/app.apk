package com.naveenapps.expensemanager.feature.transaction.create

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveenapps.expensemanager.core.domain.usecase.account.GetAllAccountsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.category.GetAllCategoryUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetDefaultCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.AddTransactionUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.DeleteTransactionUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.FindTransactionByIdUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.UpdateTransactionUseCase
import com.naveenapps.expensemanager.core.repository.FeedbackRepository
import com.naveenapps.expensemanager.core.model.Account
import com.naveenapps.expensemanager.core.model.AccountType
import com.naveenapps.expensemanager.core.model.AccountUiModel
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Category
import com.naveenapps.expensemanager.core.model.CategoryType
import com.naveenapps.expensemanager.core.model.RAML_SWEILEH_CATEGORY_ID
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.StoredIcon
import com.naveenapps.expensemanager.core.model.TextFieldValue
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.model.getAvailableCreditLimit
import com.naveenapps.expensemanager.core.model.isExpense
import com.naveenapps.expensemanager.core.model.isIncome
import com.naveenapps.expensemanager.core.model.isTransfer
import com.naveenapps.expensemanager.core.model.toAccountUiModel
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.navigation.ExpenseManagerArgsNames
import com.naveenapps.expensemanager.core.navigation.ExpenseManagerScreens
import com.naveenapps.expensemanager.core.repository.ImageStorageRepository
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.UUID


class TransactionCreateViewModel(
    savedStateHandle: SavedStateHandle,
    getCurrencyUseCase: GetCurrencyUseCase,
    getAllAccountsUseCase: GetAllAccountsUseCase,
    getAllCategoryUseCase: GetAllCategoryUseCase,
    getDefaultCurrencyUseCase: GetDefaultCurrencyUseCase,
    private val getFormattedAmountUseCase: GetFormattedAmountUseCase,
    private val findTransactionByIdUseCase: FindTransactionByIdUseCase,
    private val addTransactionUseCase: AddTransactionUseCase,
    private val updateTransactionUseCase: UpdateTransactionUseCase,
    private val deleteTransactionUseCase: DeleteTransactionUseCase,
    private val settingsRepository: SettingsRepository,
    private val imageStorageRepository: ImageStorageRepository,
    private val appComposeNavigator: AppComposeNavigator,
    private val numberFormatRepository: NumberFormatRepository,
    private val feedbackRepository: FeedbackRepository,
) : ViewModel() {

    private val sessionCreatedAttachmentPaths = mutableListOf<String>()

    private val _event = Channel<TransactionCreateEvent>()
    val event = _event.receiveAsFlow()

    private val transactionType = MutableStateFlow(TransactionType.EXPENSE)

    // Tracks whether both the accounts and categories flows have emitted their first value,
    // so we only populate an editing transaction once both are ready.
    private val syncState = MutableStateFlow(
        TransactionCreateInitSetupState(
            isCategorySyncCompleted = false,
            isAccountSyncCompleted = false,
        )
    )

    private val _state = MutableStateFlow(
        TransactionCreateState(
            amount = TextFieldValue(
                value = numberFormatRepository.formatForEditing(0.0),
                valueError = false,
                onValueChange = this::setAmountOnChange
            ),
            notes = TextFieldValue(
                value = "",
                valueError = false,
                onValueChange = this::setNotes
            ),
            customerName = TextFieldValue(
                value = "",
                valueError = false,
                onValueChange = this::setCustomerName
            ),
            customerPhone = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.CUSTOMER_PHONE, it) }),
            quantity = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.QUANTITY, it) }),
            unit = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.UNIT, it) }),
            unitPrice = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.UNIT_PRICE, it) }),
            deliveryLocation = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.DELIVERY_LOCATION, it) }),
            deliveryNotes = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.DELIVERY_NOTES, it) }),
            driverName = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.DRIVER_NAME, it) }),
            vehicleNumber = TextFieldValue("", onValueChange = { updateInvoiceField(InvoiceField.VEHICLE_NUMBER, it) }),
            dateTime = Date(),
            transactionType = TransactionType.EXPENSE,
            currency = getDefaultCurrencyUseCase.invoke(),
            selectedCategory = defaultCategory,
            selectedFromAccount = defaultAccount,
            selectedToAccount = defaultAccount,
            accounts = emptyList(),
            categories = emptyList(),
            showDeleteButton = false,
            showDeleteDialog = false,
            showCategorySelection = false,
            showAccountSelection = false,
            showNumberPad = false,
            showTimeSelection = false,
            showDateSelection = false,
            accountSelection = AccountSelection.FROM_ACCOUNT
        )
    )
    var state = _state.asStateFlow()

    // Non-null only when editing an existing transaction.
    private var editingTransaction: Transaction? = null

    init {
        observeAccountsAndCurrency(getCurrencyUseCase, getAllAccountsUseCase)
        observeCategories(getAllCategoryUseCase)
        loadTransactionWhenReady(savedStateHandle)
    }

    // region Observers

    private fun observeAccountsAndCurrency(
        getCurrencyUseCase: GetCurrencyUseCase,
        getAllAccountsUseCase: GetAllAccountsUseCase,
    ) {
        combine(
            getCurrencyUseCase.invoke(),
            getAllAccountsUseCase.invoke(),
        ) { currency, accounts ->
            val mappedAccounts = mapAccountsToUiModels(accounts, currency)
            val defaultAccountId = settingsRepository.getDefaultAccount().firstOrNull()
            val selectedAccount = mappedAccounts.find { it.id == defaultAccountId }
                ?: mappedAccounts.firstOrNull()
                ?: defaultAccount
            _state.update {
                it.copy(
                    currency = currency,
                    accounts = mappedAccounts,
                    selectedFromAccount = selectedAccount,
                    selectedToAccount = selectedAccount,
                )
            }
            syncState.update { it.copy(isAccountSyncCompleted = true) }
        }.launchIn(viewModelScope)
    }

    private fun observeCategories(getAllCategoryUseCase: GetAllCategoryUseCase) {
        combine(
            transactionType,
            getAllCategoryUseCase.invoke(),
            settingsRepository.getDefaultIncomeCategory(),
            settingsRepository.getDefaultExpenseCategory()
        ) { type, categories, defaultIncomeCategory, defaultExpenseCategory ->
            val filteredCategories = categories.filter { category ->
                category.id == RAML_SWEILEH_CATEGORY_ID &&
                    if (type.isIncome()) category.type.isIncome() else category.type.isExpense()
            }
            val preferredCategoryId = when (type) {
                TransactionType.INCOME -> defaultIncomeCategory
                TransactionType.EXPENSE -> defaultExpenseCategory
                TransactionType.TRANSFER -> null
                TransactionType.DEBT -> defaultExpenseCategory
            }
            val matchedCategory = filteredCategories.find { it.id == preferredCategoryId }

            _state.update {
                it.copy(
                    transactionType = type,
                    categories = filteredCategories,
                    selectedCategory = matchedCategory ?: filteredCategories.firstOrNull()
                    ?: defaultCategory,
                )
            }
            syncState.update { it.copy(isCategorySyncCompleted = true) }
        }.launchIn(viewModelScope)
    }

    private fun loadTransactionWhenReady(savedStateHandle: SavedStateHandle) {
        val transactionId = savedStateHandle.get<String>(ExpenseManagerArgsNames.ID) ?: return
        viewModelScope.launch {
            syncState.first { it.isAccountSyncCompleted && it.isCategorySyncCompleted }
            loadEditingTransaction(transactionId)
        }
    }

    // endregion

    fun createImageCaptureUri(): Uri = imageStorageRepository.createImageCaptureUri()

    // region Transaction load (edit mode)

    private suspend fun loadEditingTransaction(transactionId: String) {
        when (val response = findTransactionByIdUseCase.invoke(transactionId)) {
            is Resource.Error -> Unit
            is Resource.Success -> {
                val transaction = response.data
                editingTransaction = transaction
                transactionType.value = transaction.type
                _state.update { current ->
                    current.copy(
                        amount = current.amount.copy(
                            value = numberFormatRepository.formatForEditing(transaction.amount.amount)
                        ),
                        transactionType = transaction.type,
                        customerName = current.customerName.copy(value = transaction.customerName.orEmpty()),
                        customerPhone = current.customerPhone.copy(value = transaction.customerPhone.orEmpty()),
                        quantity = current.quantity.copy(value = transaction.quantity?.let(numberFormatRepository::formatForEditing).orEmpty()),
                        unit = current.unit.copy(value = transaction.unit.orEmpty()),
                        unitPrice = current.unitPrice.copy(value = transaction.unitPrice?.let(numberFormatRepository::formatForEditing).orEmpty()),
                        deliveryLocation = current.deliveryLocation.copy(value = transaction.deliveryLocation.orEmpty()),
                        deliveryNotes = current.deliveryNotes.copy(value = transaction.deliveryNotes.orEmpty()),
                        driverName = current.driverName.copy(value = transaction.driverName.orEmpty()),
                        vehicleNumber = current.vehicleNumber.copy(value = transaction.vehicleNumber.orEmpty()),
                        dateTime = transaction.createdOn,
                        notes = current.notes.copy(value = transaction.notes),
                        selectedCategory = transaction.category,
                        selectedFromAccount = transaction.fromAccount.toAccountUiModel(
                            getFormattedAmountUseCase.invoke(
                                transaction.fromAccount.amount,
                                current.currency
                            ),
                        ),
                        selectedToAccount = transaction.toAccount?.let { toAccount ->
                            toAccount.toAccountUiModel(
                                getFormattedAmountUseCase.invoke(
                                    toAccount.amount,
                                    current.currency
                                ),
                            )
                        } ?: defaultAccount,
                        showDeleteButton = true,
                        attachments = transaction.attachments,
                    )
                }
            }
        }
    }

    // endregion

    // region Save / Delete

    private fun save() {
        val currentState = _state.value
        val amountText = currentState.amount.value
        val amountValue = calculateInvoiceTotal(currentState)
            ?: numberFormatRepository.parseToDouble(amountText)

        if (amountValue == null || amountValue <= 0.0) {
            _state.update { it.copy(amount = it.amount.copy(valueError = true)) }
            return
        }

        if (currentState.transactionType == TransactionType.DEBT &&
            currentState.customerName.value.trim().isBlank()
        ) {
            _state.update { it.copy(customerName = it.customerName.copy(valueError = true)) }
            return
        }

        if (currentState.transactionType.isTransfer() &&
            currentState.selectedFromAccount.id == currentState.selectedToAccount.id
        ) return

        // amountValue is smart-cast to Double after the null check above
        persistTransaction(buildTransactionFromState(currentState, amountValue))
    }

    internal fun buildTransactionFromState(
        state: TransactionCreateState,
        amountValue: Double
    ): Transaction {
        return Transaction(
            id = editingTransaction?.id ?: UUID.randomUUID().toString(),
            notes = state.notes.value,
            categoryId = state.selectedCategory.id,
            fromAccountId = state.selectedFromAccount.id,
            toAccountId = if (state.transactionType.isTransfer()) state.selectedToAccount.id else null,
            type = state.transactionType,
            customerName = if (state.transactionType == TransactionType.DEBT) state.customerName.value.trim() else null,
            customerPhone = if (state.transactionType == TransactionType.DEBT) state.customerPhone.value.trim().takeIf(String::isNotEmpty) else null,
            amount = Amount(amountValue),
            imagePath = "",
            createdOn = state.dateTime,
            updatedOn = Calendar.getInstance().time,
            attachments = state.attachments,
            customerId = if (state.transactionType == TransactionType.DEBT) editingTransaction?.customerId else null,
            invoiceNumber = if (state.transactionType == TransactionType.DEBT) editingTransaction?.invoiceNumber else null,
            quantity = if (state.transactionType == TransactionType.DEBT) {
                numberFormatRepository.parseToDouble(state.quantity.value)?.takeIf { it > 0.0 }
            } else {
                null
            },
            unit = if (state.transactionType == TransactionType.DEBT) {
                state.unit.value.trim().takeIf(String::isNotEmpty)
            } else {
                null
            },
            unitPrice = if (state.transactionType == TransactionType.DEBT) {
                numberFormatRepository.parseToDouble(state.unitPrice.value)?.takeIf { it > 0.0 }
            } else {
                null
            },
            deliveryLocation = if (state.transactionType == TransactionType.DEBT) state.deliveryLocation.value.trim().takeIf(String::isNotEmpty) else null,
            deliveryNotes = if (state.transactionType == TransactionType.DEBT) state.deliveryNotes.value.trim().takeIf(String::isNotEmpty) else null,
            driverName = if (state.transactionType == TransactionType.DEBT) state.driverName.value.trim().takeIf(String::isNotEmpty) else null,
            vehicleNumber = if (state.transactionType == TransactionType.DEBT) state.vehicleNumber.value.trim().takeIf(String::isNotEmpty) else null,
        )
    }

    private fun calculateInvoiceTotal(state: TransactionCreateState): Double? {
        if (state.transactionType != TransactionType.DEBT) return null
        val quantity = numberFormatRepository.parseToDouble(state.quantity.value) ?: return null
        val unitPrice = numberFormatRepository.parseToDouble(state.unitPrice.value) ?: return null
        return (quantity * unitPrice).takeIf { quantity > 0.0 && unitPrice > 0.0 }
    }

    private fun updateInvoiceField(field: InvoiceField, value: String) {
        _state.update { current ->
            val updated = when (field) {
                InvoiceField.CUSTOMER_PHONE -> current.copy(customerPhone = current.customerPhone.copy(value = value))
                InvoiceField.QUANTITY -> current.copy(quantity = current.quantity.copy(value = value))
                InvoiceField.UNIT -> current.copy(unit = current.unit.copy(value = value))
                InvoiceField.UNIT_PRICE -> current.copy(unitPrice = current.unitPrice.copy(value = value))
                InvoiceField.DELIVERY_LOCATION -> current.copy(deliveryLocation = current.deliveryLocation.copy(value = value))
                InvoiceField.DELIVERY_NOTES -> current.copy(deliveryNotes = current.deliveryNotes.copy(value = value))
                InvoiceField.DRIVER_NAME -> current.copy(driverName = current.driverName.copy(value = value))
                InvoiceField.VEHICLE_NUMBER -> current.copy(vehicleNumber = current.vehicleNumber.copy(value = value))
            }
            calculateInvoiceTotal(updated)?.let { total ->
                updated.copy(amount = updated.amount.copy(value = numberFormatRepository.formatForEditing(total)))
            } ?: updated
        }
    }

    private fun persistTransaction(transaction: Transaction) {
        val isNewTransaction = editingTransaction == null
        val previouslyPersistedAttachments = editingTransaction?.attachments ?: emptyList()
        viewModelScope.launch {
            val response = if (editingTransaction != null) {
                updateTransactionUseCase.invoke(transaction)
            } else {
                addTransactionUseCase.invoke(transaction)
            }
            if (response is Resource.Success) {
                (previouslyPersistedAttachments - transaction.attachments.toSet()).forEach {
                    imageStorageRepository.deleteTransactionAttachment(it)
                }
                sessionCreatedAttachmentPaths.removeAll(transaction.attachments)
                discardSessionAttachments()
                if (isNewTransaction) {
                    onNewTransactionCreated()
                }
                closePage()
            }
        }
    }

    // Only ever called after a brand-new transaction (not an edit) is saved successfully.
    // Bumps the running "transactions created" count, and — once someone has logged enough
    // transactions and enough days have passed since install — requests Google Play's in-app
    // review popup exactly once for the life of the install.
    private suspend fun onNewTransactionCreated() {
        feedbackRepository.setTransactionCreated(true)
        if (feedbackRepository.shouldShowFeedbackDialog().first()) {
            // Marked immediately, before we know whether Play actually showed anything —
            // Play never reports that back, so "requested" is the only signal we get.
            feedbackRepository.setFeedbackDialogShown(true)
            _event.send(TransactionCreateEvent.RequestReview)
        }
    }

    private fun deleteTransaction() {
        val transaction = editingTransaction ?: return
        viewModelScope.launch {
            if (deleteTransactionUseCase.invoke(transaction) is Resource.Success) {
                transaction.attachments.forEach {
                    imageStorageRepository.deleteTransactionAttachment(it)
                }
                discardSessionAttachments()
                closePage()
            }
        }
    }

    // endregion

    // region Attachments

    private fun onAttachmentPicked(uri: Uri) {
        viewModelScope.launch {
            val path = imageStorageRepository.saveTransactionAttachment(uri) ?: return@launch
            sessionCreatedAttachmentPaths.add(path)
            _state.update { it.copy(attachments = it.attachments + path) }
        }
    }

    private fun removeAttachment(path: String) {
        _state.update { it.copy(attachments = it.attachments - path) }
    }

    private fun cancelEditing() {
        discardSessionAttachments()
        closePage()
    }

    private fun discardSessionAttachments() {
        sessionCreatedAttachmentPaths.forEach { imageStorageRepository.deleteTransactionAttachment(it) }
        sessionCreatedAttachmentPaths.clear()
    }

    // endregion

    // region State helpers

    private fun mapAccountsToUiModels(
        accounts: List<Account>,
        currency: Currency
    ): List<AccountUiModel> {
        return accounts.map { account ->
            account.toAccountUiModel(
                getFormattedAmountUseCase.invoke(account.amount, currency),
                if (account.type == AccountType.CREDIT) {
                    getFormattedAmountUseCase.invoke(account.getAvailableCreditLimit(), currency)
                } else {
                    null
                }
            )
        }
    }

    private fun setAmountOnChange(amount: String) {
        val amountValue = numberFormatRepository.parseToDouble(amount)
        _state.update {
            it.copy(
                amount = it.amount.copy(
                    value = amount,
                    valueError = amount.isBlank() || amountValue == null || amountValue <= 0.0
                ),
                showNumberPad = false
            )
        }
    }

    private fun setNotes(notes: String) {
        _state.update { it.copy(notes = it.notes.copy(value = notes)) }
    }

    private fun setCustomerName(customerName: String) {
        _state.update { it.copy(customerName = it.customerName.copy(value = customerName, valueError = false)) }
    }

    private fun changeTransactionType(type: TransactionType) {
        transactionType.update { type }
        _state.update { it.copy(transactionType = type) }
    }

    private fun closePage() {
        appComposeNavigator.popBackStack()
    }

    private fun openCategoryCreate() {
        appComposeNavigator.navigate(ExpenseManagerScreens.CategoryCreate(null))
    }

    private fun openAccountCreate() {
        appComposeNavigator.navigate(ExpenseManagerScreens.AccountCreate(null))
    }

    private fun dismissDeleteDialog() {
        _state.update { it.copy(showDeleteDialog = false) }
    }

    private fun showDeleteDialog() {
        _state.update { it.copy(showDeleteDialog = true) }
    }

    // endregion

    fun processAction(action: TransactionCreateAction) {
        when (action) {
            TransactionCreateAction.ClosePage -> cancelEditing()
            TransactionCreateAction.ShowDeleteDialog -> showDeleteDialog()
            TransactionCreateAction.DismissDeleteDialog -> dismissDeleteDialog()
            TransactionCreateAction.Delete -> deleteTransaction()
            TransactionCreateAction.Save -> save()

            is TransactionCreateAction.OpenAccountCreate -> openAccountCreate()
            is TransactionCreateAction.OpenCategoryCreate -> openCategoryCreate()

            is TransactionCreateAction.ChangeTransactionType -> changeTransactionType(action.type)
            is TransactionCreateAction.UpdateInvoiceField -> updateInvoiceField(action.field, action.value)

            is TransactionCreateAction.SetNumberPadValue -> action.amount?.let {
                setAmountOnChange(
                    it
                )
            }

            TransactionCreateAction.ShowCategorySelection -> _state.update {
                it.copy(
                    showCategorySelection = true
                )
            }

            TransactionCreateAction.DismissCategorySelection -> _state.update {
                it.copy(
                    showCategorySelection = false
                )
            }

            is TransactionCreateAction.SelectCategory -> _state.update {
                it.copy(selectedCategory = action.category, showCategorySelection = false)
            }

            is TransactionCreateAction.ShowAccountSelection -> _state.update {
                it.copy(showAccountSelection = true, accountSelection = action.type)
            }

            TransactionCreateAction.DismissAccountSelection -> _state.update {
                it.copy(
                    showAccountSelection = false
                )
            }

            is TransactionCreateAction.SelectAccount -> _state.update {
                when (it.accountSelection) {
                    AccountSelection.FROM_ACCOUNT -> it.copy(
                        selectedFromAccount = action.account,
                        showAccountSelection = false
                    )

                    AccountSelection.TO_ACCOUNT -> it.copy(
                        selectedToAccount = action.account,
                        showAccountSelection = false
                    )
                }
            }

            TransactionCreateAction.ShowNumberPad -> _state.update { it.copy(showNumberPad = true) }
            TransactionCreateAction.DismissNumberPad -> _state.update { it.copy(showNumberPad = false) }

            TransactionCreateAction.ShowDateSelection -> _state.update { it.copy(showDateSelection = true) }
            TransactionCreateAction.ShowTimeSelection -> _state.update { it.copy(showTimeSelection = true) }
            TransactionCreateAction.DismissDateSelection -> _state.update {
                it.copy(showDateSelection = false, showTimeSelection = false)
            }

            is TransactionCreateAction.SelectDate -> _state.update {
                it.copy(
                    dateTime = action.date,
                    showDateSelection = false,
                    showTimeSelection = false
                )
            }

            TransactionCreateAction.ShowAttachmentPicker -> _state.update {
                it.copy(showAttachmentPicker = true)
            }

            TransactionCreateAction.DismissAttachmentPicker -> _state.update {
                it.copy(showAttachmentPicker = false)
            }

            is TransactionCreateAction.AttachmentPicked -> {
                _state.update { it.copy(showAttachmentPicker = false) }
                onAttachmentPicked(action.uri)
            }

            is TransactionCreateAction.RemoveAttachment -> removeAttachment(action.path)
        }
    }

    override fun onCleared() {
        super.onCleared()
        discardSessionAttachments()
    }

    companion object {
        private val defaultCategory = Category(
            id = "1",
            name = "Shopping",
            type = CategoryType.EXPENSE,
            storedIcon = StoredIcon(
                name = "ic_calendar",
                backgroundColor = "#000000",
            ),
            createdOn = Date(),
            updatedOn = Date(),
        )

        private val defaultAccount = AccountUiModel(
            id = "1",
            name = "Shopping",
            storedIcon = StoredIcon(
                name = "ic_calendar",
                backgroundColor = "#000000",
            ),
            amount = Amount(0.0, "$ 0.00"),
            amountTextColor = com.naveenapps.expensemanager.core.common.R.color.green_500,
        )
    }
}
