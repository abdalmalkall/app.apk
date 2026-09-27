package com.naveenapps.expensemanager.core.model

import java.util.Date

data class CustomerPayment(
    val id: String,
    val customerId: String,
    val invoiceTransactionId: String,
    val accountId: String,
    val amount: Double,
    val notes: String = "",
    val paidOn: Date,
)