package com.naveenapps.expensemanager.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.naveenapps.expensemanager.core.database.dao.AccountDao
import com.naveenapps.expensemanager.core.database.dao.BudgetDao
import com.naveenapps.expensemanager.core.database.dao.CategoryDao
import com.naveenapps.expensemanager.core.database.dao.CustomerDao
import com.naveenapps.expensemanager.core.database.dao.CustomerPaymentDao
import com.naveenapps.expensemanager.core.database.dao.TransactionDao
import com.naveenapps.expensemanager.core.database.entity.AccountEntity
import com.naveenapps.expensemanager.core.database.entity.BudgetAccountEntity
import com.naveenapps.expensemanager.core.database.entity.BudgetCategoryEntity
import com.naveenapps.expensemanager.core.database.entity.BudgetEntity
import com.naveenapps.expensemanager.core.database.entity.CategoryEntity
import com.naveenapps.expensemanager.core.database.entity.CustomerEntity
import com.naveenapps.expensemanager.core.database.entity.CustomerPaymentEntity
import com.naveenapps.expensemanager.core.database.entity.InvoiceSequenceEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionAttachmentEntity
import com.naveenapps.expensemanager.core.database.entity.TransactionEntity
import com.naveenapps.expensemanager.core.database.utils.AccountTypeConverter
import com.naveenapps.expensemanager.core.database.utils.CategoryTypeConverter
import com.naveenapps.expensemanager.core.database.utils.DateConverter
import com.naveenapps.expensemanager.core.database.utils.TransactionTypeConverter

/**
 * The Room database for this app
 */
@Database(
    entities = [
        CategoryEntity::class,
        TransactionEntity::class,
        AccountEntity::class,
        BudgetEntity::class,
        BudgetCategoryEntity::class,
        BudgetAccountEntity::class,
        TransactionAttachmentEntity::class,
        CustomerEntity::class,
        CustomerPaymentEntity::class,
        InvoiceSequenceEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(
    DateConverter::class,
    TransactionTypeConverter::class,
    CategoryTypeConverter::class,
    AccountTypeConverter::class,
)
abstract class ExpenseManagerDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun transactionDao(): TransactionDao

    abstract fun customerDao(): CustomerDao

    abstract fun customerPaymentDao(): CustomerPaymentDao

    abstract fun accountDao(): AccountDao

    abstract fun budgetDao(): BudgetDao
}
