package com.naveenapps.expensemanager.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.naveenapps.expensemanager.core.database.entity.AccountEntity
import com.naveenapps.expensemanager.core.database.entity.CustomerPaymentEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionEntity
import com.naveenapps.expensemanager.core.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerPaymentDao {

    @Query("SELECT * FROM customer_payment ORDER BY paid_on DESC")
    fun getAllPayments(): Flow<List<CustomerPaymentEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM customer_payment WHERE invoice_transaction_id = :invoiceId")
    suspend fun getTotalPaid(invoiceId: String): Double

    @Query("SELECT * FROM customer_payment WHERE invoice_transaction_id = :invoiceId ORDER BY paid_on DESC")
    suspend fun getPaymentsForInvoice(invoiceId: String): List<CustomerPaymentEntity>

    @Query("SELECT * FROM `transaction` WHERE id = :invoiceId LIMIT 1")
    suspend fun findInvoice(invoiceId: String): TransactionEntity?

    @Query("SELECT * FROM account WHERE id = :accountId LIMIT 1")
    suspend fun findAccount(accountId: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: CustomerPaymentEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("SELECT * FROM customer_payment WHERE id = :paymentId LIMIT 1")
    suspend fun findPayment(paymentId: String): CustomerPaymentEntity?

    @Query("DELETE FROM customer_payment WHERE id = :paymentId")
    suspend fun removePayment(paymentId: String)

    @Transaction
    suspend fun addPayment(payment: CustomerPaymentEntity): Long {
        val invoice = findInvoice(payment.invoiceTransactionId) ?: return -1L
        if (invoice.type != TransactionType.DEBT || invoice.customerId != payment.customerId || payment.amount <= 0.0) {
            return -1L
        }
        val account = findAccount(payment.accountId) ?: return -1L
        val alreadyPaid = getTotalPaid(payment.invoiceTransactionId)
        if (alreadyPaid + payment.amount > invoice.amount + 0.000001) return -1L

        val insertedId = insertPayment(payment)
        if (insertedId != -1L) {
            updateAccount(account.copy(amount = account.amount + payment.amount))
        }
        return insertedId
    }

    @Transaction
    suspend fun deletePaymentAndUpdateAccount(paymentId: String): Boolean {
        val payment = findPayment(paymentId) ?: return false
        val account = findAccount(payment.accountId) ?: return false
        updateAccount(account.copy(amount = account.amount - payment.amount))
        removePayment(paymentId)
        return true
    }
}