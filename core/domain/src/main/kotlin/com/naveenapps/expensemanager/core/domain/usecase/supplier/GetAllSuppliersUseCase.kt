package com.naveenapps.expensemanager.core.domain.usecase.supplier

import com.naveenapps.expensemanager.core.model.Supplier
import com.naveenapps.expensemanager.core.repository.SupplierRepository
import kotlinx.coroutines.flow.Flow

class GetAllSuppliersUseCase(private val repository: SupplierRepository) {
    operator fun invoke(): Flow<List<Supplier>> = repository.getAllSuppliers()
}