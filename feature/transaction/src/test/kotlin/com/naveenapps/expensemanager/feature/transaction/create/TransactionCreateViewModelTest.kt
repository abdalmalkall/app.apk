package com.naveenapps.expensemanager.feature.transaction.create

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.domain.usecase.account.GetAllAccountsUseCase
import com.naveenapps.expensemanager.core.domain.usecase.category.GetAllCategoryUseCase
import com.naveenapps.expensemanager.core.domain.usecase.supplier.GetAllSuppliersUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetDefaultCurrencyUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.currency.GetFormattedAmountUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.AddTransactionUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.DeleteTransactionUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.FindTransactionByIdUseCase
import com.naveenapps.expensemanager.core.domain.usecase.transaction.UpdateTransactionUseCase
import com.naveenapps.expensemanager.core.model.AccountUiModel
import com.naveenapps.expensemanager.core.model.Account
import com.naveenapps.expensemanager.core.model.AccountType
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Category
import com.naveenapps.expensemanager.core.model.CategoryType
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.StoredIcon
import com.naveenapps.expensemanager.core.model.TextFieldValue
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.repository.FeedbackRepository
import com.naveenapps.expensemanager.core.repository.ImageStorageRepository
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import com.naveenapps.expensemanager.core.settings.domain.repository.NumberFormatRepository
import com.naveenapps.expensemanager.core.testing.BaseCoroutineTest
import com.naveenapps.expensemanager.core.testing.FAKE_EXPENSE_TRANSACTION
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionCreateViewModelTest : BaseCoroutineTest() {

    private val getCurrencyUseCase: GetCurrencyUseCase = mock()
    private val getAllAccountsUseCase: GetAllAccountsUseCase = mock()
    private val getAllCategoryUseCase: GetAllCategoryUseCase = mock()
    private val getAllSuppliersUseCase: GetAllSuppliersUseCase = mock()
    private val getDefaultCurrencyUseCase: GetDefaultCurrencyUseCase = mock()
    private val getFormattedAmountUseCase: GetFormattedAmountUseCase = mock()
    private val findTransactionByIdUseCase: FindTransactionByIdUseCase = mock()
    private val addTransactionUseCase: AddTransactionUseCase = mock()
    private val updateTransactionUseCase: UpdateTransactionUseCase = mock()
    private val deleteTransactionUseCase: DeleteTransactionUseCase = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val imageStorageRepository: ImageStorageRepository = mock()
    private val appComposeNavigator: AppComposeNavigator = mock()
    private val numberFormatRepository: NumberFormatRepository = mock()
    private val feedbackRepository: FeedbackRepository = mock()

    private lateinit var viewModel: TransactionCreateViewModel

    private val fakeCurrency = Currency(symbol = "$", name = "USD")

    private val fakeFromAccount = AccountUiModel(
        id = "from-account-id",
        name = "Checking",
        storedIcon = StoredIcon(name = "ic_wallet", backgroundColor = "#000000"),
        amount = Amount(1000.0),
        amountTextColor = 0,
    )

    private val fakeToAccount = AccountUiModel(
        id = "to-account-id",
        name = "Savings",
        storedIcon = StoredIcon(name = "ic_wallet", backgroundColor = "#000000"),
        amount = Amount(500.0),
        amountTextColor = 0,
    )

    private val fakeCategory = Category(
        id = "category-id",
        name = "Food",
        type = CategoryType.EXPENSE,
        storedIcon = StoredIcon(name = "ic_food", backgroundColor = "#FF0000"),
        createdOn = Date(),
        updatedOn = Date(),
    )

    override fun onCreate() {
        super.onCreate()

        whenever(getDefaultCurrencyUseCase.invoke()).thenReturn(fakeCurrency)
        whenever(numberFormatRepository.formatForEditing(0.0)).thenReturn("0.00")
        whenever(getCurrencyUseCase.invoke()).thenReturn(flowOf(fakeCurrency))
        whenever(getAllAccountsUseCase.invoke()).thenReturn(flowOf(emptyList()))
        whenever(getAllCategoryUseCase.invoke()).thenReturn(flowOf(emptyList()))
        whenever(getAllSuppliersUseCase.invoke()).thenReturn(flowOf(emptyList()))
        whenever(settingsRepository.getDefaultAccount()).thenReturn(flowOf(null))
        whenever(settingsRepository.getDefaultIncomeCategory()).thenReturn(flowOf(null))
        whenever(settingsRepository.getDefaultExpenseCategory()).thenReturn(flowOf(null))

        viewModel = createViewModel()
    }

    private fun createViewModel() = TransactionCreateViewModel(
            savedStateHandle = SavedStateHandle(),
            getCurrencyUseCase = getCurrencyUseCase,
            getAllAccountsUseCase = getAllAccountsUseCase,
            getAllCategoryUseCase = getAllCategoryUseCase,
            getAllSuppliersUseCase = getAllSuppliersUseCase,
            getDefaultCurrencyUseCase = getDefaultCurrencyUseCase,
            getFormattedAmountUseCase = getFormattedAmountUseCase,
            findTransactionByIdUseCase = findTransactionByIdUseCase,
            addTransactionUseCase = addTransactionUseCase,
            updateTransactionUseCase = updateTransactionUseCase,
            deleteTransactionUseCase = deleteTransactionUseCase,
            settingsRepository = settingsRepository,
            imageStorageRepository = imageStorageRepository,
            appComposeNavigator = appComposeNavigator,
            numberFormatRepository = numberFormatRepository,
            feedbackRepository = feedbackRepository,
        )

    // region helpers

    private fun buildState(
        notes: String = "Test notes",
        dateTime: Date = Date(),
        transactionType: TransactionType = TransactionType.EXPENSE,
        selectedCategory: Category = fakeCategory,
        selectedFromAccount: AccountUiModel = fakeFromAccount,
        selectedToAccount: AccountUiModel = fakeToAccount,
        customerName: String = "",
    ) = TransactionCreateState(
        amount = TextFieldValue(value = "100.00", valueError = false, onValueChange = null),
        notes = TextFieldValue(value = notes, valueError = false, onValueChange = null),
        customerName = TextFieldValue(value = customerName, valueError = false, onValueChange = null),
        dateTime = dateTime,
        transactionType = transactionType,
        currency = fakeCurrency,
        selectedCategory = selectedCategory,
        selectedFromAccount = selectedFromAccount,
        selectedToAccount = selectedToAccount,
        accounts = emptyList(),
        categories = emptyList(),
        accountSelection = AccountSelection.FROM_ACCOUNT,
        showDeleteDialog = false,
        showDeleteButton = false,
        showNumberPad = false,
        showCategorySelection = false,
        showAccountSelection = false,
        showDateSelection = false,
        showTimeSelection = false,
    )

    private fun setEditingTransaction(transaction: Transaction) {
        val field = TransactionCreateViewModel::class.java.getDeclaredField("editingTransaction")
        field.isAccessible = true
        field.set(viewModel, transaction)
    }

    // endregion

    // region toAccountId

    @Test
    fun `expense transaction sets toAccountId to null`() {
        val result = viewModel.buildTransactionFromState(
            buildState(transactionType = TransactionType.EXPENSE),
            amountValue = 100.0,
        )

        assertThat(result.toAccountId).isNull()
    }

    @Test
    fun `income transaction sets toAccountId to null`() {
        val result = viewModel.buildTransactionFromState(
            buildState(transactionType = TransactionType.INCOME),
            amountValue = 100.0,
        )

        assertThat(result.toAccountId).isNull()
    }

    @Test
    fun `transfer transaction sets toAccountId to the to-account id`() {
        val result = viewModel.buildTransactionFromState(
            buildState(
                transactionType = TransactionType.TRANSFER,
                selectedToAccount = fakeToAccount,
            ),
            amountValue = 100.0,
        )

        assertThat(result.toAccountId).isEqualTo(fakeToAccount.id)
    }

    @Test
    fun `debt transaction preserves its type and amount without a destination account`() {
        val result = viewModel.buildTransactionFromState(
            buildState(transactionType = TransactionType.DEBT),
            amountValue = 100.0,
        )

        assertThat(result.type).isEqualTo(TransactionType.DEBT)
        assertThat(result.amount.amount).isEqualTo(100.0)
        assertThat(result.toAccountId).isNull()
    }

    // endregion

    // region id (create vs edit mode)

    @Test
    fun `create mode generates a non-blank id`() {
        val result = viewModel.buildTransactionFromState(buildState(), amountValue = 100.0)

        assertThat(result.id).isNotEmpty()
    }

    @Test
    fun `create mode reuses one id across save retries`() {
        val first = viewModel.buildTransactionFromState(buildState(), amountValue = 100.0)
        val second = viewModel.buildTransactionFromState(buildState(), amountValue = 100.0)

        assertThat(first.id).isEqualTo(second.id)
    }

    @Test
    fun `supplier credit purchase is linked to supplier and not customer`() {
        val state = buildState(transactionType = TransactionType.DEBT).copy(
            supplierPurchaseMode = true,
            supplierId = "supplier-1",
            supplierName = "Stone Supply",
        )

        val first = viewModel.buildTransactionFromState(state, amountValue = 200.0)
        val retry = viewModel.buildTransactionFromState(state, amountValue = 200.0)

        assertThat(first.type).isEqualTo(TransactionType.DEBT)
        assertThat(first.supplierId).isEqualTo("supplier-1")
        assertThat(first.supplierName).isEqualTo("Stone Supply")
        assertThat(first.customerName).isNull()
        assertThat(first.customerPhone).isNull()
        assertThat(first.invoiceNumber).startsWith("PUR-${first.id}")
        assertThat(retry.id).isEqualTo(first.id)
        assertThat(retry.invoiceNumber).isEqualTo(first.invoiceNumber)
    }

    @Test
    fun `supplier cash purchase is linked to supplier as an expense`() {
        val result = viewModel.buildTransactionFromState(
            buildState(transactionType = TransactionType.EXPENSE).copy(
                supplierPurchaseMode = true,
                supplierId = "supplier-1",
                supplierName = "Stone Supply",
            ),
            amountValue = 200.0,
        )

        assertThat(result.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(result.supplierId).isEqualTo("supplier-1")
        assertThat(result.customerId).isNull()
        assertThat(result.invoiceNumber).startsWith("PUR-")
    }

    @Test
    fun `edit mode reuses the existing transaction id`() {
        setEditingTransaction(FAKE_EXPENSE_TRANSACTION.copy(id = "existing-id-123"))

        val result = viewModel.buildTransactionFromState(buildState(), amountValue = 100.0)

        assertThat(result.id).isEqualTo("existing-id-123")
    }

    // endregion

    // region field mapping

    @Test
    fun `maps notes from state`() {
        val result = viewModel.buildTransactionFromState(
            buildState(notes = "Dinner at restaurant"),
            amountValue = 100.0,
        )

        assertThat(result.notes).isEqualTo("Dinner at restaurant")
    }

    @Test
    fun `maps categoryId from the selected category`() {
        val result = viewModel.buildTransactionFromState(
            buildState(selectedCategory = fakeCategory),
            amountValue = 100.0,
        )

        assertThat(result.categoryId).isEqualTo(fakeCategory.id)
    }

    @Test
    fun `maps fromAccountId from the selected from-account`() {
        val result = viewModel.buildTransactionFromState(
            buildState(selectedFromAccount = fakeFromAccount),
            amountValue = 100.0,
        )

        assertThat(result.fromAccountId).isEqualTo(fakeFromAccount.id)
    }

    @Test
    fun `maps transaction type from state`() {
        TransactionType.entries.forEach { type ->
            val result = viewModel.buildTransactionFromState(
                buildState(transactionType = type),
                amountValue = 100.0,
            )

            assertThat(result.type).isEqualTo(type)
        }
    }

    @Test
    fun `maps customer name for debt transaction from state`() {
        val result = viewModel.buildTransactionFromState(
            buildState(transactionType = TransactionType.DEBT, customerName = "Ahmed"),
            amountValue = 100.0,
        )

        assertThat(result.customerName).isEqualTo("Ahmed")
    }

    @Test
    fun `maps amount from the amountValue parameter`() {
        val result = viewModel.buildTransactionFromState(buildState(), amountValue = 250.75)

        assertThat(result.amount.amount).isEqualTo(250.75)
    }

    @Test
    fun `maps state dateTime as createdOn`() {
        val fixedDate = Date(1_000_000_000L)

        val result = viewModel.buildTransactionFromState(
            buildState(dateTime = fixedDate),
            amountValue = 100.0,
        )

        assertThat(result.createdOn).isEqualTo(fixedDate)
    }

    @Test
    fun `sets updatedOn to the current time`() {
        val before = Date()
        val result = viewModel.buildTransactionFromState(buildState(), amountValue = 100.0)
        val after = Date()

        assertThat(result.updatedOn.time).isAtLeast(before.time)
        assertThat(result.updatedOn.time).isAtMost(after.time)
    }

    @Test
    fun `sets imagePath to empty string`() {
        val result = viewModel.buildTransactionFromState(buildState(), amountValue = 100.0)

        assertThat(result.imagePath).isEmpty()
    }

    @Test fun `all compatible categories remain available and income can select its category`() = runTest {
        val income = fakeCategory.copy(id = "income-category", name = "Salary", type = CategoryType.INCOME)
        whenever(getAllCategoryUseCase.invoke()).thenReturn(flowOf(listOf(fakeCategory, income)))
        val model = createViewModel()
        advanceUntilIdle()
        assertThat(model.state.value.categories.map { it.id }).containsExactly(fakeCategory.id)
        model.processAction(TransactionCreateAction.ChangeTransactionType(TransactionType.INCOME))
        advanceUntilIdle()
        assertThat(model.state.value.categories.map { it.id }).containsExactly(income.id)
        assertThat(model.state.value.selectedCategory.id).isEqualTo(income.id)
    }

    @Test fun `account updates preserve the selected source and destination accounts`() = runTest {
        val first = Account("first", "First", AccountType.REGULAR, fakeFromAccount.storedIcon, Date(), Date(), amount = 100.0)
        val second = first.copy(id = "second", name = "Second")
        val accounts = MutableStateFlow(listOf(first, second))
        whenever(getAllAccountsUseCase.invoke()).thenReturn(accounts)
        whenever(getFormattedAmountUseCase.invoke(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenAnswer {
            Amount(it.getArgument<Double>(0))
        }
        val model = createViewModel()
        advanceUntilIdle()
        model.processAction(TransactionCreateAction.ShowAccountSelection(AccountSelection.FROM_ACCOUNT))
        model.processAction(TransactionCreateAction.SelectAccount(model.state.value.accounts.first { it.id == "second" }))
        model.processAction(TransactionCreateAction.ShowAccountSelection(AccountSelection.TO_ACCOUNT))
        model.processAction(TransactionCreateAction.SelectAccount(model.state.value.accounts.first { it.id == "first" }))
        accounts.value = listOf(first.copy(amount = 90.0), second.copy(amount = 110.0))
        advanceUntilIdle()
        assertThat(model.state.value.selectedFromAccount.id).isEqualTo("second")
        assertThat(model.state.value.selectedFromAccount.amount.amount).isEqualTo(110.0)
        assertThat(model.state.value.selectedToAccount.id).isEqualTo("first")
        assertThat(model.state.value.selectedToAccount.amount.amount).isEqualTo(90.0)
    }

    // endregion
}
