package com.naveenapps.expensemanager.core.repository

import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.Supplier
import kotlinx.coroutines.flow.Flow

interface SupplierRepository {
    fun getAllSuppliers(): Flow<List<Supplier>>

    suspend fun saveSupplier(supplier: Supplier): Resource<Boolean>
}