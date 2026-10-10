package com.naveenapps.expensemanager.core.domain.usecase.supplier

import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.Supplier
import com.naveenapps.expensemanager.core.repository.SupplierRepository

class SaveSupplierUseCase(private val repository: SupplierRepository) {
    suspend operator fun invoke(supplier: Supplier): Resource<Boolean> = repository.saveSupplier(supplier)
}