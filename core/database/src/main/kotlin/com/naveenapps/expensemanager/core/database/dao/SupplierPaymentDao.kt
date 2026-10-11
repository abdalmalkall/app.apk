package com.naveenapps.expensemanager.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.naveenapps.expensemanager.core.database.entity.AccountEntity
import com.naveenapps.expensemanager.core.database.entity.SupplierPaymentEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionEntity
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.model.LedgerMoney
import com.naveenapps.expensemanager.core.database.entity.adjustBalance
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierPaymentDao {

    @Query("SELECT * FROM supplier_payment ORDER BY paid_on DESC, id ASC")
    fun getAllPayments(): Flow<List<SupplierPaymentEntity>>

    suspend fun getTotalPaid(invoiceId: String): Double =
        LedgerMoney.totalExact(getPaymentsForInvoice(invoiceId).map { LedgerMoney.exact(it.amount, it.amountDecimal) }).toDouble()

    @Query("SELECT * FROM supplier_payment WHERE invoice_transaction_id = :invoiceId ORDER BY paid_on, id")
    suspend fun getPaymentsForInvoice(invoiceId: String): List<SupplierPaymentEntity>

    @Query("SELECT * FROM `transaction` WHERE id = :invoiceId LIMIT 1")
    suspend fun findInvoice(invoiceId: String): TransactionEntity?

    @Query("SELECT * FROM account WHERE id = :accountId LIMIT 1")
    suspend fun findAccount(accountId: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: SupplierPaymentEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("SELECT * FROM supplier_payment WHERE id = :paymentId LIMIT 1")
    suspend fun findPayment(paymentId: String): SupplierPaymentEntity?

    @Query("DELETE FROM supplier_payment WHERE id = :paymentId")
    suspend fun removePayment(paymentId: String)

    @Transaction
    suspend fun addPayment(payment: SupplierPaymentEntity): Long {
        if (!LedgerMoney.validNewAmount(payment.amount, payment.amountDecimal)) return -1L
        val exactAmount = LedgerMoney.exact(payment.amount, payment.amountDecimal)
        // The stable payment ID is an idempotency key, including retries after a lost response.
        val existing = findPayment(payment.id)
        if (existing != null) return if (existing.copy(amountDecimal = null) == payment.copy(amountDecimal = null) &&
            LedgerMoney.exact(existing.amount, existing.amountDecimal).compareTo(exactAmount) == 0) 0L else -1L
        val invoice = findInvoice(payment.invoiceTransactionId) ?: return -1L
        if (invoice.type != TransactionType.DEBT || invoice.supplierId != payment.supplierId) return -1L
        if (invoice.customerId != null && invoice.supplierId != null) return -1L
        if (!invoice.amount.isFinite() || invoice.amount <= 0.0) return -1L
        if (payment.paidOn.before(invoice.createdOn)) return -1L
        val account = findAccount(payment.accountId) ?: return -1L
        val alreadyPaid = LedgerMoney.totalExact(getPaymentsForInvoice(payment.invoiceTransactionId).map { LedgerMoney.exact(it.amount, it.amountDecimal) })
        if (alreadyPaid.add(exactAmount) > LedgerMoney.exact(invoice.amount, invoice.amountDecimal)) return -1L

        val insertedId = insertPayment(payment.copy(amountDecimal = exactAmount.toPlainString()))
        if (insertedId != -1L) updateAccount(account.adjustBalance(exactAmount.negate()))
        return insertedId
    }

    @Transaction
    suspend fun deletePaymentAndUpdateAccount(paymentId: String): Boolean {
        val payment = findPayment(paymentId) ?: return false
        val account = findAccount(payment.accountId) ?: return false
        updateAccount(account.adjustBalance(LedgerMoney.exact(payment.amount, payment.amountDecimal)))
        removePayment(paymentId)
        return true
    }

    companion object {
        private const val AMOUNT_TOLERANCE = 0.000001
    }
}
