package com.naveenapps.expensemanager.core.data.mappers

import com.naveenapps.expensemanager.core.database.entity.SupplierEntity
import com.naveenapps.expensemanager.core.database.entity.SupplierPaymentEntity
import com.naveenapps.expensemanager.core.model.Supplier
import com.naveenapps.expensemanager.core.model.SupplierPayment

fun SupplierEntity.toDomainModel() = Supplier(
    id = id,
    name = name,
    phone = phone,
    createdOn = createdOn,
    updatedOn = updatedOn,
)

fun Supplier.toEntityModel() = SupplierEntity(
    id = id,
    name = name,
    normalizedName = name.trim().lowercase(),
    phone = phone,
    createdOn = createdOn,
    updatedOn = updatedOn,
)

fun SupplierPaymentEntity.toDomainModel() = SupplierPayment(
    id = id,
    supplierId = supplierId,
    invoiceTransactionId = invoiceTransactionId,
    accountId = accountId,
    amount = amount,
    notes = notes,
    paidOn = paidOn,
)

fun SupplierPayment.toEntityModel() = SupplierPaymentEntity(
    id = id,
    supplierId = supplierId,
    invoiceTransactionId = invoiceTransactionId,
    accountId = accountId,
    amount = amount,
    notes = notes,
    paidOn = paidOn,
)