package com.naveenapps.expensemanager.core.model

import java.util.Date

data class Customer(
    val id: String,
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val creditLimitMilliunits: Long = 0L,
    val createdOn: Date,
    val updatedOn: Date,
)