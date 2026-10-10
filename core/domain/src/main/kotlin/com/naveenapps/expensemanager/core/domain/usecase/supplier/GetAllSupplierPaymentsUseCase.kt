package com.naveenapps.expensemanager.core.domain.usecase.supplier

import com.naveenapps.expensemanager.core.model.SupplierPayment
import com.naveenapps.expensemanager.core.repository.SupplierPaymentRepository
import kotlinx.coroutines.flow.Flow

class GetAllSupplierPaymentsUseCase(private val repository: SupplierPaymentRepository) {
    operator fun invoke(): Flow<List<SupplierPayment>> = repository.getAllPayments()
}