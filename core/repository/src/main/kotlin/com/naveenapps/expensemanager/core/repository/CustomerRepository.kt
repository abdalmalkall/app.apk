package com.naveenapps.expensemanager.core.repository

import com.naveenapps.expensemanager.core.model.Customer
import com.naveenapps.expensemanager.core.model.Resource
import kotlinx.coroutines.flow.Flow

interface CustomerRepository {
    fun getAllCustomers(): Flow<List<Customer>>

    suspend fun saveCustomer(customer: Customer): Resource<Boolean>
}