package com.naveenapps.expensemanager.core.data.repository

import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.data.mappers.toDomainModel
import com.naveenapps.expensemanager.core.data.mappers.toEntityModel
import com.naveenapps.expensemanager.core.database.dao.SupplierPaymentDao
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.SupplierPayment
import com.naveenapps.expensemanager.core.repository.SupplierPaymentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SupplierPaymentRepositoryImpl(
    private val supplierPaymentDao: SupplierPaymentDao,
    private val dispatchers: AppCoroutineDispatchers,
) : SupplierPaymentRepository {

    override fun getAllPayments(): Flow<List<SupplierPayment>> =
        supplierPaymentDao.getAllPayments().map { payments -> payments.map { it.toDomainModel() } }

    override suspend fun addPayment(payment: SupplierPayment): Resource<Boolean> = withContext(dispatchers.io) {
        try {
            Resource.Success(supplierPaymentDao.addPayment(payment.toEntityModel()) != -1L)
        } catch (exception: Exception) {
            Resource.Error(exception)
        }
    }

    override suspend fun deletePayment(paymentId: String): Resource<Boolean> = withContext(dispatchers.io) {
        try {
            Resource.Success(supplierPaymentDao.deletePaymentAndUpdateAccount(paymentId))
        } catch (exception: Exception) {
            Resource.Error(exception)
        }
    }
}