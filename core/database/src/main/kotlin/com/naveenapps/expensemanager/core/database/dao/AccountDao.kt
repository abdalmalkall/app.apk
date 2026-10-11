package com.naveenapps.expensemanager.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.naveenapps.expensemanager.core.database.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao : BaseDao<AccountEntity> {

    // REPLACE deletes the old row first and would cascade into transaction history.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    override suspend fun insert(value: AccountEntity): Long

    @Query("""
        SELECT (SELECT COUNT(*) FROM `transaction` WHERE from_account_id = :id OR to_account_id = :id)
             + (SELECT COUNT(*) FROM customer_payment WHERE account_id = :id)
             + (SELECT COUNT(*) FROM supplier_payment WHERE account_id = :id)
             + (SELECT COUNT(*) FROM budget_account_relation WHERE account_id = :id)
    """)
    suspend fun referenceCount(id: String): Int

    @Delete
    suspend fun deleteUnreferenced(value: AccountEntity): Int

    @Transaction
    override suspend fun delete(value: AccountEntity): Int {
        require(referenceCount(value.id) == 0) { "Account is used by transactions, payments or budgets; keep it to preserve history" }
        return deleteUnreferenced(value)
    }

    @Query("SELECT * FROM account ORDER BY sequence ASC")
    fun getAccounts(): Flow<List<AccountEntity>?>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun findById(id: String): AccountEntity?
}
