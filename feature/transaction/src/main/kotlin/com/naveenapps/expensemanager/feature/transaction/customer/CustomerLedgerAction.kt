package com.naveenapps.expensemanager.feature.transaction.customer

sealed class CustomerLedgerAction {
    data object ClosePage : CustomerLedgerAction()
    data class UpdateSearch(val query: String) : CustomerLedgerAction()
    data class ToggleCustomerDetails(val customerId: String) : CustomerLedgerAction()
    data class OpenTransactionEdit(val transactionId: String) : CustomerLedgerAction()
    data class StartPayment(val customerId: String, val invoice: CustomerInvoiceItem) : CustomerLedgerAction()
    data class UpdatePaymentAmount(val amount: String) : CustomerLedgerAction()
    data object DismissPayment : CustomerLedgerAction()
    data object SavePayment : CustomerLedgerAction()
    data class DeletePayment(val paymentId: String) : CustomerLedgerAction()
    data class ShareInvoice(
        val customerName: String,
        val phone: String?,
        val invoiceNumber: String,
        val totalAmount: String,
        val transactions: List<String>,
    ) : CustomerLedgerAction()

    data class ShareCustomerStatement(
        val customerName: String,
        val phone: String?,
        val totalAmount: String,
        val transactions: List<String>,
    ) : CustomerLedgerAction()
}
