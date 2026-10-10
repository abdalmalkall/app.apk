package com.naveenapps.expensemanager.core.database.di

import androidx.room.Room
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import com.naveenapps.expensemanager.core.database.MIGRATION_2_3
import com.naveenapps.expensemanager.core.database.MIGRATION_3_4
import com.naveenapps.expensemanager.core.database.MIGRATION_4_5
import com.naveenapps.expensemanager.core.database.MIGRATION_5_6
import com.naveenapps.expensemanager.core.database.MIGRATION_6_7
import com.naveenapps.expensemanager.core.database.MIGRATION_7_8
import com.naveenapps.expensemanager.core.database.MIGRATION_8_9
import com.naveenapps.expensemanager.core.database.MIGRATION_9_10
import com.naveenapps.expensemanager.core.database.MIGRATION_10_11
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

private const val DATA_BASE_NAME = "expense_manager_database.db"

val DatabaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            ExpenseManagerDatabase::class.java,
            DATA_BASE_NAME,
        ).addMigrations(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
        ).build()
    }
    single { get<ExpenseManagerDatabase>().categoryDao() }
    single { get<ExpenseManagerDatabase>().accountDao() }
    single { get<ExpenseManagerDatabase>().transactionDao() }
    single { get<ExpenseManagerDatabase>().customerDao() }
    single { get<ExpenseManagerDatabase>().customerPaymentDao() }
    single { get<ExpenseManagerDatabase>().supplierDao() }
    single { get<ExpenseManagerDatabase>().supplierPaymentDao() }
    single { get<ExpenseManagerDatabase>().budgetDao() }
}
