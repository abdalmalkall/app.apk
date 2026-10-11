package com.naveenapps.expensemanager.feature.transaction.supplier

sealed class SupplierLedgerAction {
    data class SetFilter(val filter: com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter) : SupplierLedgerAction()
    data class SetSort(val sort: com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort) : SupplierLedgerAction()
    data class ShowStatement(val id: String) : SupplierLedgerAction()
    data class StatementPeriod(val from: Long, val to: Long) : SupplierLedgerAction()
    data object DismissStatement : SupplierLedgerAction()
    data object DismissMessage : SupplierLedgerAction()
    data class PaymentDetails(val paidOn: java.util.Date, val method: String, val notes: String) : SupplierLedgerAction()
    data object ClosePage : SupplierLedgerAction()
    data class UpdateSearch(val query: String) : SupplierLedgerAction()
    data object AddSupplier : SupplierLedgerAction()
    data class EditSupplier(val supplierId: String) : SupplierLedgerAction()
    data class UpdateSupplierField(val field: SupplierProfileField, val value: String) : SupplierLedgerAction()
    data object DismissSupplierEditor : SupplierLedgerAction()
    data object SaveSupplier : SupplierLedgerAction()
    data class ToggleSupplier(val supplierId: String) : SupplierLedgerAction()
    data class StartPayment(val supplierId: String, val invoice: SupplierInvoiceItem) : SupplierLedgerAction()
    data class UpdatePaymentAmount(val amount: String) : SupplierLedgerAction()
    data class SelectPaymentAccount(val accountId: String) : SupplierLedgerAction()
    data object DismissPayment : SupplierLedgerAction()
    data object SavePayment : SupplierLedgerAction()
    data class DeletePayment(val paymentId: String) : SupplierLedgerAction()
    data class OpenPurchase(val id: String) : SupplierLedgerAction()
    data class StartPurchase(val supplierId: String) : SupplierLedgerAction()
}

enum class SupplierProfileField {
    NAME,
    PHONE,
    ADDRESS,
    COMPANY_NAME,
    NOTES,
}