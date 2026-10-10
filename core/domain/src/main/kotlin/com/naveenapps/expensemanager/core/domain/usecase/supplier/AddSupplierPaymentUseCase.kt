package com.naveenapps.expensemanager.core.domain.usecase.supplier

import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.SupplierPayment
import com.naveenapps.expensemanager.core.repository.SupplierPaymentRepository

class AddSupplierPaymentUseCase(private val repository: SupplierPaymentRepository) {
    suspend operator fun invoke(payment: SupplierPayment): Resource<Boolean> = repository.addPayment(payment)
}