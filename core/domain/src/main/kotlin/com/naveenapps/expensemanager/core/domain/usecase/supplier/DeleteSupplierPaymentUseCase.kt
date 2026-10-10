package com.naveenapps.expensemanager.core.domain.usecase.supplier

import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.SupplierPaymentRepository

class DeleteSupplierPaymentUseCase(private val repository: SupplierPaymentRepository) {
    suspend operator fun invoke(paymentId: String): Resource<Boolean> = repository.deletePayment(paymentId)
}