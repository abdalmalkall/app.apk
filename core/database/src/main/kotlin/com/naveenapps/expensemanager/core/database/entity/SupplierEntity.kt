package com.naveenapps.expensemanager.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "supplier",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
data class SupplierEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "phone")
    val phone: String? = null,
    @ColumnInfo(name = "created_on")
    val createdOn: Date,
    @ColumnInfo(name = "updated_on")
    val updatedOn: Date,
)