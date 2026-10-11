package com.naveenapps.expensemanager.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "supplier_payment",
    foreignKeys = [
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplier_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoice_transaction_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["supplier_id"]),
        Index(value = ["invoice_transaction_id"]),
        Index(value = ["account_id"]),
    ],
)
data class SupplierPaymentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "supplier_id")
    val supplierId: String,
    @ColumnInfo(name = "invoice_transaction_id")
    val invoiceTransactionId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "amount")
    val amount: Double,
    @ColumnInfo(name = "notes")
    val notes: String = "",
    @ColumnInfo(name = "paid_on")
    val paidOn: Date,
    @ColumnInfo(name = "amount_decimal", defaultValue = "NULL")
    val amountDecimal: String? = null,
)