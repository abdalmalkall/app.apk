package com.naveenapps.expensemanager.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.naveenapps.expensemanager.core.database.entity.AccountEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionAttachmentEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionRelation
import com.naveenapps.expensemanager.core.database.entity.InvoiceSequenceEntity
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.model.isTransfer
import com.naveenapps.expensemanager.core.model.LedgerMoney
import com.naveenapps.expensemanager.core.database.entity.adjustBalance
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID

@Dao
interface TransactionDao : BaseDao<TransactionEntity> {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    override suspend fun insert(value: TransactionEntity): Long

    @Query("SELECT * from `transaction` WHERE id=:id")
    fun findById(id: String): TransactionEntity?

    @Query("SELECT next_value FROM invoice_sequence WHERE id = 1")
    suspend fun getNextInvoiceSequence(): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInvoiceSequenceIfMissing(sequence: InvoiceSequenceEntity)

    @Query("UPDATE invoice_sequence SET next_value = next_value + 1 WHERE id = 1")
    suspend fun advanceInvoiceSequence()

    // @Transaction is required here (not just on writes) because TransactionRelation embeds
    // multiple @Relation fields (category, fromAccount, toAccount, attachments). Room fetches
    // each relation via a separate sub-query and stitches the results back onto the parent rows
    // using a key->list map; without @Transaction that stitching isn't atomic with the main
    // query, so a concurrent write (e.g. attachments being deleted/reinserted on save) between
    // the main query and a relation sub-query can leave a row's key missing from the map and
    // throw NoSuchElementException from the generated getValue(...) call.
    @Transaction
    @Query("SELECT * FROM `transaction`")
    fun getAllTransaction(): Flow<List<TransactionRelation>?>

    @Transaction
    @Query(
        """
        SELECT * FROM `transaction`
        WHERE `transaction`.from_account_id IN(:accounts)
        AND `transaction`.category_id IN(:categories)
        AND `transaction`.type IN(:transactionTypes)
        ORDER BY `transaction`.created_on DESC
        """,
    )
    fun getAllFilteredTransaction(
        accounts: List<String>,
        categories: List<String>,
        transactionTypes: List<Int>,
    ): Flow<List<TransactionRelation>?>

    @Transaction
    @Query(
        """
        SELECT * FROM `transaction`
        WHERE `transaction`.from_account_id IN(:accounts)
        AND `transaction`.category_id IN(:categories)
        AND `transaction`.type IN(:transactionTypes)
        AND `transaction`.created_on BETWEEN :fromDate AND :toDate
        ORDER BY `transaction`.created_on DESC
        """,
    )
    fun getFilteredTransaction(
        accounts: List<String>,
        categories: List<String>,
        transactionTypes: List<Int>,
        fromDate: Long,
        toDate: Long,
    ): Flow<List<TransactionRelation>?>

    @Update
    suspend fun updateAccount(accountEntity: AccountEntity)

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun findAccountById(id: String): AccountEntity?

    @Query(
        "SELECT * FROM transaction_attachment WHERE transaction_id = :transactionId ORDER BY created_on ASC",
    )
    suspend fun getTransactionAttachments(transactionId: String): List<TransactionAttachmentEntity>

    @Query("DELETE FROM transaction_attachment WHERE transaction_id = :transactionId")
    suspend fun removeTransactionAttachments(transactionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactionAttachment(attachmentEntity: TransactionAttachmentEntity): Long

    @Transaction
    suspend fun insertTransaction(
        transactionEntity: TransactionEntity,
        amountToDetect: Double,
        isTransfer: Boolean,
        attachmentPaths: List<String> = emptyList(),
    ): Long {
        validateLedgerInvoice(transactionEntity)
        if (findById(transactionEntity.id) != null) return -1L
        val entityToInsert = if (
            transactionEntity.type == TransactionType.DEBT &&
            transactionEntity.customerName?.isNotBlank() == true &&
            transactionEntity.supplierId == null &&
            transactionEntity.invoiceNumber == null
        ) {
            insertInvoiceSequenceIfMissing(InvoiceSequenceEntity(nextValue = 1))
            val sequence = getNextInvoiceSequence() ?: 1
            advanceInvoiceSequence()
            transactionEntity.copy(invoiceNumber = "INV-${sequence.toString().padStart(4, '0')}")
        } else {
            transactionEntity
        }
        val id = insert(entityToInsert.copy(amountDecimal = LedgerMoney.exact(entityToInsert.amount, entityToInsert.amountDecimal).toPlainString()))
        if (id != -1L) {
            attachmentPaths.forEach { path ->
                insertTransactionAttachment(
                    TransactionAttachmentEntity(
                        id = UUID.randomUUID().toString(),
                        transactionId = entityToInsert.id,
                        imagePath = path,
                        createdOn = Date(),
                    ),
                )
            }
            val accountEntity = findAccountById(transactionEntity.fromAccountId)
            if (accountEntity != null) {
                updateAccount(
                    accountEntity.adjustBalance(balanceEffect(transactionEntity)),
                )
            }
            if (transactionEntity.type.isTransfer() && transactionEntity.toAccountId?.isNotBlank() == true) {
                val toAccountEntity = findAccountById(
                    transactionEntity.toAccountId!!,
                )
                if (toAccountEntity != null) {
                    updateAccount(
                        toAccountEntity.adjustBalance(balanceEffect(transactionEntity).negate()),
                    )
                }
            }
        }
        return id
    }

    @Query("SELECT amount FROM customer_payment WHERE invoice_transaction_id = :id")
    suspend fun customerPaymentAmounts(id: String): List<Double>

    @Query("SELECT amount FROM supplier_payment WHERE invoice_transaction_id = :id")
    suspend fun supplierPaymentAmounts(id: String): List<Double>

    fun balanceEffect(invoice: TransactionEntity): java.math.BigDecimal = when (invoice.type) {
        TransactionType.DEBT -> java.math.BigDecimal.ZERO
        TransactionType.INCOME -> LedgerMoney.exact(invoice.amount, invoice.amountDecimal)
        else -> LedgerMoney.exact(invoice.amount, invoice.amountDecimal).negate()
    }

    fun validateLedgerInvoice(invoice: TransactionEntity, historicalAmount: Boolean = false) {
        require(invoice.amount.isFinite() && invoice.amount > 0.0) { "Invalid amount" }
        require(invoice.customerId == null || invoice.supplierId == null) { "Invoice cannot belong to both parties" }
        if (!historicalAmount && (invoice.type == TransactionType.DEBT || invoice.supplierId != null)) {
            require(LedgerMoney.validNewAmount(invoice.amount, invoice.amountDecimal)) { "Use a consistent amount with at most three decimal places" }
        }
        if (invoice.type == TransactionType.DEBT) require(invoice.customerId != null || invoice.supplierId != null) { "Debt requires a party" }
        if (invoice.type.isTransfer()) {
            require(!invoice.toAccountId.isNullOrBlank()) { "Transfer requires a destination account" }
            require(invoice.toAccountId != invoice.fromAccountId) { "Transfer accounts must differ" }
        }
    }

    @Transaction
    suspend fun deleteTransactionAndUpdateAccount(entity: TransactionEntity): Int {
        val stored = findById(entity.id) ?: return 0
        require(customerPaymentAmounts(stored.id).isEmpty() && supplierPaymentAmounts(stored.id).isEmpty()) {
            "Reverse payments before deleting their invoice"
        }
        removePreviousEnteredAmount(stored)
        return delete(stored)
    }

    suspend fun removePreviousEnteredAmount(transactionEntity: TransactionEntity) {
        val previousTransaction = findById(transactionEntity.id)

        if (previousTransaction != null) {
            val previousAmountToDetect = balanceEffect(previousTransaction).negate()
            val previousSelectedFromAccount = findAccountById(previousTransaction.fromAccountId)
            if (previousSelectedFromAccount != null) {
                updateAccount(
                    previousSelectedFromAccount.adjustBalance(previousAmountToDetect),
                )
            }

            if (previousTransaction.type.isTransfer() && previousTransaction.toAccountId?.isNotBlank() == true) {
                val toAccountEntity = findAccountById(
                    previousTransaction.toAccountId!!,
                )
                if (toAccountEntity != null) {
                    updateAccount(
                        toAccountEntity.adjustBalance(previousAmountToDetect.negate()),
                    )
                }
            }
        }
    }

    @Transaction
    suspend fun updateTransaction(
        transactionEntity: TransactionEntity,
        amountToDetect: Double,
        isTransfer: Boolean,
        attachmentPaths: List<String> = emptyList(),
    ) {
        val previous = findById(transactionEntity.id) ?: error("Invoice not found")
        val sameAmount = LedgerMoney.exact(previous.amount, previous.amountDecimal)
            .compareTo(LedgerMoney.exact(transactionEntity.amount, transactionEntity.amountDecimal)) == 0
        validateLedgerInvoice(transactionEntity, historicalAmount = sameAmount)
        val payments = customerPaymentAmounts(previous.id) + supplierPaymentAmounts(previous.id)
        if (payments.isNotEmpty()) {
            require(previous.type == transactionEntity.type && previous.customerId == transactionEntity.customerId &&
                previous.supplierId == transactionEntity.supplierId && sameAmount &&
                previous.createdOn == transactionEntity.createdOn && previous.invoiceNumber == transactionEntity.invoiceNumber &&
                previous.fromAccountId == transactionEntity.fromAccountId) {
                "A paid invoice cannot change its financial identity; reverse its payments first"
            }
        }
        removePreviousEnteredAmount(previous)
        update(transactionEntity.copy(amountDecimal = LedgerMoney.exact(transactionEntity.amount, transactionEntity.amountDecimal).toPlainString()))

        removeTransactionAttachments(transactionEntity.id)
        attachmentPaths.forEach { path ->
            insertTransactionAttachment(
                TransactionAttachmentEntity(
                    id = UUID.randomUUID().toString(),
                    transactionId = transactionEntity.id,
                    imagePath = path,
                    createdOn = Date(),
                ),
            )
        }

        val accountEntity = findAccountById(transactionEntity.fromAccountId)
        if (accountEntity != null) {
            updateAccount(
                accountEntity.adjustBalance(balanceEffect(transactionEntity)),
            )
        }
        if (transactionEntity.type.isTransfer() && transactionEntity.toAccountId?.isNotBlank() == true) {
            val toAccountEntity = findAccountById(
                transactionEntity.toAccountId!!,
            )
            if (toAccountEntity != null) {
                updateAccount(
                    toAccountEntity.adjustBalance(balanceEffect(transactionEntity).negate()),
                )
            }
        }
    }
}
