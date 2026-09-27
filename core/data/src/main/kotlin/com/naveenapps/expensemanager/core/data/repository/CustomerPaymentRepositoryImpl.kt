package com.naveenapps.expensemanager.core.data.repository

import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.data.mappers.toDomainModel
import com.naveenapps.expensemanager.core.data.mappers.toEntityModel
import com.naveenapps.expensemanager.core.database.dao.CustomerPaymentDao
import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.CustomerPaymentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CustomerPaymentRepositoryImpl(
    private val customerPaymentDao: CustomerPaymentDao,
    private val dispatchers: AppCoroutineDispatchers,
) : CustomerPaymentRepository {

    override fun getAllPayments(): Flow<List<CustomerPayment>> =
        customerPaymentDao.getAllPayments().map { payments -> payments.map { it.toDomainModel() } }

    override suspend fun addPayment(payment: CustomerPayment): Resource<Boolean> = withContext(dispatchers.io) {
        runCatching {
            customerPaymentDao.addPayment(payment.toEntityModel()) > 0L
        }.fold(
            onSuccess = { Resource.Success(it) },
            onFailure = { Resource.Error(it as? Exception ?: Exception(it)) },
        )
    }

    override suspend fun deletePayment(paymentId: String): Resource<Boolean> = withContext(dispatchers.io) {
        runCatching {
            customerPaymentDao.deletePaymentAndUpdateAccount(paymentId)
        }.fold(
            onSuccess = { Resource.Success(it) },
            onFailure = { Resource.Error(it as? Exception ?: Exception(it)) },
        )
    }
}