package com.naveenapps.expensemanager.feature.transaction.di

import com.naveenapps.expensemanager.feature.transaction.create.TransactionCreateViewModel
import com.naveenapps.expensemanager.feature.transaction.customer.CustomerLedgerViewModel
import com.naveenapps.expensemanager.feature.transaction.list.TransactionListViewModel
import com.naveenapps.expensemanager.feature.transaction.numberpad.NumberPadViewModel
import com.naveenapps.expensemanager.feature.transaction.supplier.SupplierLedgerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val TransactionViewModelModule = module {
    viewModel {
        TransactionCreateViewModel(
            savedStateHandle = get(),
            getCurrencyUseCase = get(),
            getAllAccountsUseCase = get(),
            getAllCategoryUseCase = get(),
            getAllSuppliersUseCase = get(),
            getAllCustomersUseCase = get(),
            getDefaultCurrencyUseCase = get(),
            getFormattedAmountUseCase = get(),
            findTransactionByIdUseCase = get(),
            addTransactionUseCase = get(),
            updateTransactionUseCase = get(),
            deleteTransactionUseCase = get(),
            settingsRepository = get(),
            imageStorageRepository = get(),
            appComposeNavigator = get(),
            numberFormatRepository = get(),
            feedbackRepository = get(),
        )
    }
    viewModel {
        TransactionListViewModel(
            getCurrencyUseCase = get(),
            getFormattedAmountUseCase = get(),
            getTransactionWithFilterUseCase = get(),
            appCoroutineDispatchers = get(),
            appComposeNavigator = get()
        )
    }
    viewModel {
        CustomerLedgerViewModel(
            getCurrencyUseCase = get(),
            getFormattedAmountUseCase = get(),
            getAllTransactionsUseCase = get(),
            getAllCustomerPaymentsUseCase = get(),
            getAllCustomersUseCase = get(),
            saveCustomerUseCase = get(),
            addCustomerPaymentUseCase = get(),
            deleteCustomerPaymentUseCase = get(),
            getAllAccountsUseCase = get(),
            settingsRepository = get(),
            numberFormatRepository = get(),
            appCoroutineDispatchers = get(),
            appComposeNavigator = get(),
            shareRepository = get(),
        )
    }
    viewModel { NumberPadViewModel() }
    viewModel {
        SupplierLedgerViewModel(
            getCurrencyUseCase = get(),
            getFormattedAmountUseCase = get(),
            getAllTransactionsUseCase = get(),
            getAllSuppliersUseCase = get(),
            getAllSupplierPaymentsUseCase = get(),
            saveSupplierUseCase = get(),
            addSupplierPaymentUseCase = get(),
            deleteSupplierPaymentUseCase = get(),
            getAllAccountsUseCase = get(),
            settingsRepository = get(),
            numberFormatRepository = get(),
            appCoroutineDispatchers = get(),
            navigator = get(),
        )
    }
}

