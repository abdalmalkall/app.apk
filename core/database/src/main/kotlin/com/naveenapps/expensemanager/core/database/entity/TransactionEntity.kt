package com.naveenapps.expensemanager.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.naveenapps.expensemanager.core.model.TransactionType
import java.util.Date

@Entity(
    tableName = "transaction",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("from_account_id"),
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("to_account_id"),
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("category_id"),
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("customer_id"),
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["invoice_number"], unique = true),
        Index(value = ["customer_id"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "notes")
    val notes: String,
    @ColumnInfo(name = "category_id")
    var categoryId: String,
    @ColumnInfo(name = "from_account_id")
    var fromAccountId: String,
    @ColumnInfo(name = "type")
    var type: TransactionType,
    @ColumnInfo(name = "customer_name")
    var customerName: String? = null,
    @ColumnInfo(name = "amount")
    var amount: Double,
    @ColumnInfo(name = "image_path")
    var imagePath: String,
    @ColumnInfo(name = "created_on")
    var createdOn: Date,
    @ColumnInfo(name = "updated_on")
    var updatedOn: Date,
    @ColumnInfo(name = "to_account_id")
    var toAccountId: String?,
    @ColumnInfo(name = "customer_id", defaultValue = "NULL")
    var customerId: String? = null,
    @ColumnInfo(name = "invoice_number", defaultValue = "NULL")
    var invoiceNumber: String? = null,
    @ColumnInfo(name = "quantity", defaultValue = "NULL")
    var quantity: Double? = null,
    @ColumnInfo(name = "unit", defaultValue = "NULL")
    var unit: String? = null,
    @ColumnInfo(name = "unit_price", defaultValue = "NULL")
    var unitPrice: Double? = null,
    @ColumnInfo(name = "delivery_location", defaultValue = "NULL")
    var deliveryLocation: String? = null,
    @ColumnInfo(name = "delivery_notes", defaultValue = "NULL")
    var deliveryNotes: String? = null,
    @ColumnInfo(name = "driver_name", defaultValue = "NULL")
    var driverName: String? = null,
    @ColumnInfo(name = "vehicle_number", defaultValue = "NULL")
    var vehicleNumber: String? = null,
)
