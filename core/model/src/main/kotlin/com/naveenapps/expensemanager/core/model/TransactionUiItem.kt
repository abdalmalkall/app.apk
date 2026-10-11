package com.naveenapps.expensemanager.core.model

import androidx.compose.runtime.Stable
import com.naveenapps.expensemanager.core.common.utils.toCompleteDateWithDate

@Stable
data class TransactionGroup(
    val date: String,
    val amountTextColor: Int,
    val totalAmount: Amount,
    val transactions: List<TransactionUiItem>,
    val isLastItem: Boolean = false,
)

@Stable
data class TransactionUiItem(
    val id: String,
    val amount: Amount = Amount(0.0),
    val notes: String?,
    val categoryName: String,
    val transactionType: TransactionType,
    val categoryIcon: StoredIcon,
    val date: String,
    val fromAccountName: String,
    val fromAccountIcon: StoredIcon,
    val toAccountName: String? = null,
    val toAccountIcon: StoredIcon? = null,
    val categoryTitleResId: Int? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val supplierName: String? = null,
    val invoiceNumber: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val unitPrice: Double? = null,
    val deliveryLocation: String? = null,
    val deliveryNotes: String? = null,
    val driverName: String? = null,
    val vehicleNumber: String? = null,
)

fun Transaction.toTransactionUIModel(amount: Amount): TransactionUiItem {
    return TransactionUiItem(
        this.id,
        amount,
        this.notes,
        this.category.name,
        this.type,
        categoryIcon = this.category.storedIcon,
        date = this.createdOn.toCompleteDateWithDate(),
        fromAccountName = this.fromAccount.name,
        fromAccountIcon = this.fromAccount.storedIcon,
        toAccountName = this.toAccount?.name,
        toAccountIcon = this.toAccount?.storedIcon,
        categoryTitleResId = this.category.titleResId,
        customerName = this.customerName,
        customerPhone = this.customerPhone,
        supplierName = this.supplierName,
        invoiceNumber = this.invoiceNumber,
        quantity = this.quantity,
        unit = this.unit,
        unitPrice = this.unitPrice,
        deliveryLocation = this.deliveryLocation,
        deliveryNotes = this.deliveryNotes,
        driverName = this.driverName,
        vehicleNumber = this.vehicleNumber,
    )
}
