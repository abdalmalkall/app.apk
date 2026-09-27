package com.naveenapps.expensemanager.core.repository

import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.model.Resource
import kotlinx.coroutines.flow.Flow

interface CustomerPaymentRepository {

    fun getAllPayments(): Flow<List<CustomerPayment>>

    suspend fun addPayment(payment: CustomerPayment): Resource<Boolean>

    suspend fun deletePayment(paymentId: String): Resource<Boolean>
}