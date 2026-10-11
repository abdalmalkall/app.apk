package com.naveenapps.expensemanager.core.data.repository

import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.data.mappers.toDomainModel
import com.naveenapps.expensemanager.core.data.mappers.toEntityModel
import com.naveenapps.expensemanager.core.database.dao.SupplierDao
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.Supplier
import com.naveenapps.expensemanager.core.repository.SupplierRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

class SupplierRepositoryImpl(
    private val supplierDao: SupplierDao,
    private val dispatchers: AppCoroutineDispatchers,
) : SupplierRepository {

    override fun getAllSuppliers(): Flow<List<Supplier>> =
        supplierDao.getAllSuppliers().map { suppliers -> suppliers.map { it.toDomainModel() } }

    override suspend fun saveSupplier(supplier: Supplier): Resource<Boolean> = withContext(dispatchers.io) {
        val name = supplier.name.trim()
        if (name.isBlank()) return@withContext Resource.Error(IllegalArgumentException("Supplier name is required."))
        val normalizedName = name.lowercase(Locale.ROOT)
        val existingByName = supplierDao.findByNormalizedName(normalizedName)
        if (existingByName != null && existingByName.id != supplier.id) {
            return@withContext Resource.Error(IllegalArgumentException("A supplier with this name already exists."))
        }
        try {
            val existing = supplierDao.findById(supplier.id)
            if (existing == null) {
                val inserted = supplierDao.insertIfMissing(
                    supplier.copy(
                        name = name,
                        phone = supplier.phone?.trim()?.ifBlank { null },
                        address = supplier.address?.trim()?.ifBlank { null },
                        companyName = supplier.companyName?.trim()?.ifBlank { null },
                        notes = supplier.notes?.trim()?.ifBlank { null },
                    ).toEntityModel(),
                )
                Resource.Success(inserted != -1L)
            } else {
                supplierDao.update(
                    supplier.copy(
                        createdOn = existing.createdOn,
                        name = name,
                        phone = supplier.phone?.trim()?.ifBlank { null },
                        address = supplier.address?.trim()?.ifBlank { null },
                        companyName = supplier.companyName?.trim()?.ifBlank { null },
                        notes = supplier.notes?.trim()?.ifBlank { null },
                    ).toEntityModel(),
                )
                Resource.Success(true)
            }
        } catch (exception: Exception) {
            Resource.Error(exception)
        }
    }
}