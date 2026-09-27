package com.naveenapps.expensemanager.feature.transaction.customer

sealed class CustomerLedgerAction {
    data object ClosePage : CustomerLedgerAction()
    data class UpdateSearch(val query: String) : CustomerLedgerAction()
    data class OpenTransactionEdit(val transactionId: String) : CustomerLedgerAction()
}
