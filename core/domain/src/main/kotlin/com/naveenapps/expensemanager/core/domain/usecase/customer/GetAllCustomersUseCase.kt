package com.naveenapps.expensemanager.core.domain.usecase.customer

import com.naveenapps.expensemanager.core.model.Customer
import com.naveenapps.expensemanager.core.repository.CustomerRepository
import kotlinx.coroutines.flow.Flow

class GetAllCustomersUseCase(private val repository: CustomerRepository) {
    operator fun invoke(): Flow<List<Customer>> = repository.getAllCustomers()
}