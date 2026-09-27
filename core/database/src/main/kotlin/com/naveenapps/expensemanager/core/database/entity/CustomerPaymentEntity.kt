package com.naveenapps.expensemanager.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "customer_payment",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoice_transaction_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["customer_id"]),
        Index(value = ["invoice_transaction_id"]),
        Index(value = ["account_id"]),
    ],
)
data class CustomerPaymentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "customer_id")
    val customerId: String,
    @ColumnInfo(name = "invoice_transaction_id")
    val invoiceTransactionId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "amount")
    val amount: Double,
    @ColumnInfo(name = "notes")
    val notes: String,
    @ColumnInfo(name = "paid_on")
    val paidOn: Date,
)