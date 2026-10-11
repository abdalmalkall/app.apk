package com.naveenapps.expensemanager.core.model

import java.util.Date

data class Supplier(
    val id: String,
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val companyName: String? = null,
    val notes: String? = null,
    val createdOn: Date,
    val updatedOn: Date,
)