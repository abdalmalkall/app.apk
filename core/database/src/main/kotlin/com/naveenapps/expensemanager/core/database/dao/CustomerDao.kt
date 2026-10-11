package com.naveenapps.expensemanager.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.naveenapps.expensemanager.core.database.entity.CustomerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {

    @Query("SELECT * FROM customer ORDER BY normalized_name")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customer WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CustomerEntity?

    @Query("SELECT * FROM customer WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(customer: CustomerEntity): Long

    @Update
    suspend fun update(customer: CustomerEntity)
}