package com.naveenapps.expensemanager.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.naveenapps.expensemanager.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao : BaseDao<CategoryEntity> {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    override suspend fun insert(value: CategoryEntity): Long

    @Query("""
        SELECT (SELECT COUNT(*) FROM `transaction` WHERE category_id = :id)
             + (SELECT COUNT(*) FROM budget_category_relation WHERE category_id = :id)
    """)
    suspend fun referenceCount(id: String): Int

    @Delete
    suspend fun deleteUnreferenced(value: CategoryEntity): Int

    @Transaction
    override suspend fun delete(value: CategoryEntity): Int {
        require(referenceCount(value.id) == 0) { "Category is used by transactions or budgets; keep it to preserve history" }
        return deleteUnreferenced(value)
    }

    @Query("SELECT * FROM category ORDER BY created_on DESC")
    fun getCategories(): Flow<List<CategoryEntity>?>

    @Query("SELECT * FROM category WHERE id = :aModelId")
    fun findByIdFlow(aModelId: String): Flow<CategoryEntity?>

    @Query("SELECT * FROM category ORDER BY created_on DESC")
    suspend fun getAllValues(): List<CategoryEntity>?

    @Query("SELECT * FROM category WHERE id = :aModelId")
    suspend fun findById(aModelId: String): CategoryEntity?
}
