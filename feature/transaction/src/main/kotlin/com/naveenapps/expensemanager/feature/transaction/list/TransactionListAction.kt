package com.naveenapps.expensemanager.feature.transaction.list

sealed class TransactionListAction {

    data object ClosePage : TransactionListAction()

    data object OpenCreateTransaction : TransactionListAction()

    data object OpenCustomerLedger : TransactionListAction()

    data object OpenSupplierLedger : TransactionListAction()

    data class OpenEdiTransaction(val transactionId: String) : TransactionListAction()
}