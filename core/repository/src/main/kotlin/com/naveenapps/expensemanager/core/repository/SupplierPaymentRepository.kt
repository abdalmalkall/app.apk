package com.naveenapps.expensemanager.core.repository

import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.SupplierPayment
import kotlinx.coroutines.flow.Flow

interface SupplierPaymentRepository {
    fun getAllPayments(): Flow<List<SupplierPayment>>

    suspend fun addPayment(payment: SupplierPayment): Resource<Boolean>

    suspend fun deletePayment(paymentId: String): Resource<Boolean>
}