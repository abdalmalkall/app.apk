package com.naveenapps.expensemanager.core.domain.usecase.customer

import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.repository.CustomerPaymentRepository
import kotlinx.coroutines.flow.Flow

class GetAllCustomerPaymentsUseCase(private val repository: CustomerPaymentRepository) {
    operator fun invoke(): Flow<List<CustomerPayment>> = repository.getAllPayments()
}