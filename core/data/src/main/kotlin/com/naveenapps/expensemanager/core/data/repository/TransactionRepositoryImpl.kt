package com.naveenapps.expensemanager.core.data.repository

import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.common.utils.fromLocalToUTCTimeStamp
import com.naveenapps.expensemanager.core.data.mappers.toDomainModel
import com.naveenapps.expensemanager.core.data.mappers.toEntityModel
import com.naveenapps.expensemanager.core.database.dao.AccountDao
import com.naveenapps.expensemanager.core.database.dao.CategoryDao
import com.naveenapps.expensemanager.core.database.dao.CustomerDao
import com.naveenapps.expensemanager.core.database.dao.TransactionDao
import com.naveenapps.expensemanager.core.database.entity.TransactionEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionRelation
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import com.naveenapps.expensemanager.core.model.isIncome
import com.naveenapps.expensemanager.core.model.isTransfer
import com.naveenapps.expensemanager.core.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TransactionRepositoryImpl(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val customerDao: CustomerDao,
    private val dispatchers: AppCoroutineDispatchers,
) : TransactionRepository {

    override fun getAllTransaction(): Flow<List<Transaction>?> =
        transactionDao.getAllTransaction().map {
            convertTransactionAndCategory(it)
        }

    override suspend fun findTransactionById(transactionId: String): Resource<Transaction> =
        withContext(dispatchers.io) {
            return@withContext try {
                val transaction = transactionDao.findById(transactionId)

                if (transaction != null) {
                    Resource.Success(convertTransactionCategoryRelation(transaction))
                } else {
                    Resource.Error(KotlinNullPointerException())
                }
            } catch (e: Exception) {
                Resource.Error(e)
            }
        }

    override suspend fun addTransaction(transaction: Transaction): Resource<Boolean> =
        withContext(dispatchers.io) {
            return@withContext try {
                val response = transactionDao.insertTransaction(
                    transaction.toEntityModel().withCustomer(customerDao, transaction),
                    when (transaction.type) {
                        TransactionType.INCOME -> transaction.amount.amount
                        TransactionType.DEBT -> 0.0
                        else -> transaction.amount.amount * -1
                    },
                    transaction.type.isTransfer(),
                    transaction.attachments,
                )
                Resource.Success(response != -1L)
            } catch (exception: Exception) {
                Resource.Error(exception)
            }
        }

    override suspend fun updateTransaction(transaction: Transaction): Resource<Boolean> =
        withContext(dispatchers.io) {
            return@withContext try {
                val transactionEntity = transaction.toEntityModel()
                val entityWithCustomer = transactionEntity.withCustomer(customerDao, transaction)
                transactionDao.removePreviousEnteredAmount(transactionEntity)
                transactionDao.updateTransaction(
                    entityWithCustomer,
                    when (transaction.type) {
                        TransactionType.INCOME -> transaction.amount.amount
                        TransactionType.DEBT -> 0.0
                        else -> transaction.amount.amount * -1
                    },
                    transaction.type.isTransfer(),
                    transaction.attachments,
                )
                Resource.Success(true)
            } catch (exception: Exception) {
                Resource.Error(exception)
            }
        }

    override suspend fun deleteTransaction(transaction: Transaction): Resource<Boolean> =
        withContext(dispatchers.io) {
            return@withContext try {
                val transactionEntity = transaction.toEntityModel()
                transactionDao.removePreviousEnteredAmount(transactionEntity)
                val response = transactionDao.delete(transactionEntity)
                Resource.Success(response != -1)
            } catch (exception: Exception) {
                Resource.Error(exception)
            }
        }

    private fun convertTransactionAndCategory(
        transactionWithCategory: List<TransactionRelation>?,
    ): MutableList<Transaction> {
        val outputTransactions = mutableListOf<Transaction>()

        if (transactionWithCategory?.isNotEmpty() == true) {
            transactionWithCategory.forEach {
                convertTransactionCategoryRelation(it)?.let { transaction ->
                    outputTransactions.add(transaction)
                }
            }
        }
        return outputTransactions
    }

    private fun convertTransactionCategoryRelation(relation: TransactionRelation): Transaction? {
        return relation.transactionEntity.toDomainModel().copy(
            customerId = relation.customerEntity?.id ?: relation.transactionEntity.customerId,
            customerName = relation.customerEntity?.name ?: relation.transactionEntity.customerName,
            customerPhone = relation.customerEntity?.phone,
        ).apply {
            category = relation.categoryEntity?.toDomainModel() ?: return null
            fromAccount = relation.fromAccountEntity?.toDomainModel() ?: return null
            toAccount = relation.toAccountEntity?.toDomainModel()
            attachments = relation.attachmentEntities.map { it.imagePath }
        }
    }

    private suspend fun convertTransactionCategoryRelation(relation: TransactionEntity): Transaction {
        val customer = relation.customerId?.let { customerDao.findById(it) }
        val transaction = relation.toDomainModel().copy(
            customerName = customer?.name ?: relation.customerName,
            customerPhone = customer?.phone,
        )
        categoryDao.findById(transaction.categoryId)?.let {
            transaction.category = it.toDomainModel()
        }
        accountDao.findById(transaction.fromAccountId)?.let {
            transaction.fromAccount = it.toDomainModel()
        }
        transaction.toAccountId?.let {
            accountDao.findById(it)?.let { accountEntity ->
                transaction.toAccount = accountEntity.toDomainModel()
            }
        }
        transaction.attachments = transactionDao.getTransactionAttachments(transaction.id)
            .map { it.imagePath }
        return transaction
    }

    private suspend fun TransactionEntity.withCustomer(
        customerDao: CustomerDao,
        transaction: Transaction,
    ): TransactionEntity {
        val name = transaction.customerName?.trim().orEmpty()
        if (name.isBlank() || transaction.type != TransactionType.DEBT) return this
        val normalizedName = name.lowercase()
        val now = transaction.updatedOn
        val existing = customerDao.findByNormalizedName(normalizedName)
        val customerId = if (existing != null) {
            customerDao.update(
                existing.copy(
                    name = name,
                    phone = transaction.customerPhone?.trim()?.takeIf(String::isNotEmpty) ?: existing.phone,
                    updatedOn = now,
                ),
            )
            existing.id
        } else {
            val newId = java.util.UUID.randomUUID().toString()
            customerDao.insertIfMissing(
                com.naveenapps.expensemanager.core.database.entity.CustomerEntity(
                    id = newId,
                    name = name,
                    normalizedName = normalizedName,
                    phone = transaction.customerPhone?.trim()?.takeIf(String::isNotEmpty),
                    createdOn = transaction.createdOn,
                    updatedOn = now,
                ),
            )
            customerDao.findByNormalizedName(normalizedName)?.id ?: newId
        }
        return copy(customerId = customerId, customerName = name)
    }

    override fun getAllFilteredTransaction(
        accounts: List<String>,
        categories: List<String>,
        transactionType: List<Int>,
    ): Flow<List<Transaction>?> {
        return transactionDao.getAllFilteredTransaction(
            accounts,
            categories,
            transactionType,
        ).map {
            convertTransactionAndCategory(it)
        }
    }

    override fun getFilteredTransaction(
        accounts: List<String>,
        categories: List<String>,
        transactionType: List<Int>,
        startDate: Long,
        endDate: Long,
    ): Flow<List<Transaction>?> {
        return transactionDao.getFilteredTransaction(
            accounts,
            categories,
            transactionType,
            startDate.fromLocalToUTCTimeStamp(),
            endDate.fromLocalToUTCTimeStamp(),
        ).map {
            convertTransactionAndCategory(it)
        }
    }
}
