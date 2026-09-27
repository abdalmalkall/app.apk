package com.naveenapps.expensemanager.core.domain.usecase.customer

import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.CustomerPaymentRepository

class AddCustomerPaymentUseCase(private val repository: CustomerPaymentRepository) {
    suspend operator fun invoke(payment: CustomerPayment): Resource<Boolean> {
        if (payment.amount <= 0.0) return Resource.Error(Exception("Payment must be greater than zero"))
        return repository.addPayment(payment)
    }
}