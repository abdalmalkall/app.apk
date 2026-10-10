package com.naveenapps.expensemanager.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.naveenapps.expensemanager.core.database.entity.SupplierEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {

    @Query("SELECT * FROM supplier ORDER BY normalized_name")
    fun getAllSuppliers(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM supplier WHERE id = :supplierId LIMIT 1")
    suspend fun findById(supplierId: String): SupplierEntity?

    @Query("SELECT * FROM supplier WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): SupplierEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(supplier: SupplierEntity): Long

    @Update
    suspend fun update(supplier: SupplierEntity)
}