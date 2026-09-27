package com.naveenapps.expensemanager.core.domain.usecase.customer

import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.CustomerPaymentRepository

class DeleteCustomerPaymentUseCase(private val repository: CustomerPaymentRepository) {
    suspend operator fun invoke(paymentId: String): Resource<Boolean> = repository.deletePayment(paymentId)
}