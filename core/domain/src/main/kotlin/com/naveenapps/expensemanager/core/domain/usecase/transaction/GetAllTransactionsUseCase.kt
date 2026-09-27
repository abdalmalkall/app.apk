package com.naveenapps.expensemanager.core.domain.usecase.transaction

import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow

class GetAllTransactionsUseCase(private val repository: TransactionRepository) {
    operator fun invoke(): Flow<List<Transaction>?> = repository.getAllTransaction()
}