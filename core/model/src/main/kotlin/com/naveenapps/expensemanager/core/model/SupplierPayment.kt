package com.naveenapps.expensemanager.core.model

import java.util.Date

data class SupplierPayment(
    val id: String,
    val supplierId: String,
    val invoiceTransactionId: String,
    val accountId: String,
    val amount: Double,
    val notes: String = "",
    val paidOn: Date,
)