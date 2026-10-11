package com.naveenapps.expensemanager.feature.transaction.customer

sealed class CustomerLedgerAction {
    data class SetFilter(val filter: com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter) : CustomerLedgerAction()
    data class SetSort(val sort: com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort) : CustomerLedgerAction()
    data class ShowStatement(val id: String) : CustomerLedgerAction()
    data class StatementPeriod(val from: Long, val to: Long) : CustomerLedgerAction()
    data object DismissStatement : CustomerLedgerAction()
    data object DismissMessage : CustomerLedgerAction()
    data class PaymentDetails(val paidOn: java.util.Date, val method: String, val notes: String) : CustomerLedgerAction()
    data object ClosePage : CustomerLedgerAction()
    data class UpdateSearch(val query: String) : CustomerLedgerAction()
    data object AddCustomer : CustomerLedgerAction()
    data class EditCustomer(val customerId: String) : CustomerLedgerAction()
    data class UpdateCustomerDraft(val field: CustomerProfileField, val value: String) : CustomerLedgerAction()
    data object DismissCustomerEditor : CustomerLedgerAction()
    data object SaveCustomer : CustomerLedgerAction()
    data class ToggleCustomerDetails(val customerId: String) : CustomerLedgerAction()
    data class OpenTransactionEdit(val transactionId: String) : CustomerLedgerAction()
    data class StartPayment(val customerId: String, val invoice: CustomerInvoiceItem) : CustomerLedgerAction()
    data class UpdatePaymentAmount(val amount: String) : CustomerLedgerAction()
    data class StartInvoice(val customerId: String) : CustomerLedgerAction()
    data class SelectPaymentAccount(val accountId: String) : CustomerLedgerAction()
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

enum class CustomerProfileField {
    NAME,
    PHONE,
    ADDRESS,
    NOTES,
    CREDIT_LIMIT,
}
