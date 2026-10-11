package com.naveenapps.expensemanager.core.data.repository

import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.data.mappers.toDomainModel
import com.naveenapps.expensemanager.core.data.mappers.toEntityModel
import com.naveenapps.expensemanager.core.database.dao.CustomerDao
import com.naveenapps.expensemanager.core.model.Customer
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.CustomerRepository
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CustomerRepositoryImpl(
    private val customerDao: CustomerDao,
    private val dispatchers: AppCoroutineDispatchers,
) : CustomerRepository {

    override fun getAllCustomers(): Flow<List<Customer>> =
        customerDao.getAllCustomers().map { customers -> customers.map { it.toDomainModel() } }

    override suspend fun saveCustomer(customer: Customer): Resource<Boolean> = withContext(dispatchers.io) {
        val name = customer.name.trim()
        if (name.isBlank()) return@withContext Resource.Error(IllegalArgumentException("Customer name is required."))
        if (customer.creditLimitMilliunits < 0L) {
            return@withContext Resource.Error(IllegalArgumentException("Credit limit cannot be negative."))
        }

        val normalizedName = name.lowercase(Locale.ROOT)
        val duplicate = customerDao.findByNormalizedName(normalizedName)
        if (duplicate != null && duplicate.id != customer.id) {
            return@withContext Resource.Error(IllegalArgumentException("A customer with this name already exists."))
        }

        try {
            val existing = customerDao.findById(customer.id)
            val entity = customer.copy(
                name = name,
                phone = customer.phone?.trim()?.ifBlank { null },
                address = customer.address?.trim()?.ifBlank { null },
                notes = customer.notes?.trim()?.ifBlank { null },
            ).toEntityModel()
            if (existing == null) {
                Resource.Success(customerDao.insertIfMissing(entity) != -1L)
            } else {
                customerDao.update(entity.copy(createdOn = existing.createdOn))
                Resource.Success(true)
            }
        } catch (exception: Exception) {
            Resource.Error(exception)
        }
    }
}