package com.naveenapps.expensemanager.core.domain.usecase.customer

import com.naveenapps.expensemanager.core.model.Customer
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.CustomerRepository

class SaveCustomerUseCase(private val repository: CustomerRepository) {
    suspend operator fun invoke(customer: Customer): Resource<Boolean> = repository.saveCustomer(customer)
}