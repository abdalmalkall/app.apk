package com.naveenapps.expensemanager.core.data.mappers

import com.naveenapps.expensemanager.core.database.entity.TransactionEntity
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Transaction

fun Transaction.toEntityModel(): TransactionEntity {
    return TransactionEntity(
        id = id,
        notes = notes,
        categoryId = categoryId,
        fromAccountId = fromAccountId,
        toAccountId = toAccountId,
        type = type,
        customerName = customerName,
        amount = amount.amount,
        amountDecimal = com.naveenapps.expensemanager.core.model.LedgerMoney.exact(amount.amount, amount.amountDecimal).toPlainString(),
        imagePath = imagePath,
        createdOn = createdOn,
        updatedOn = updatedOn,
        customerId = customerId,
        invoiceNumber = invoiceNumber,
        quantity = quantity,
        unit = unit,
        unitPrice = unitPrice,
        deliveryLocation = deliveryLocation,
        deliveryNotes = deliveryNotes,
        driverName = driverName,
        vehicleNumber = vehicleNumber,
        supplierId = supplierId,
        supplierName = supplierName,
    )
}

fun TransactionEntity.toDomainModel(): Transaction {
    return Transaction(
        id = id,
        notes = notes,
        categoryId = categoryId,
        fromAccountId = fromAccountId,
        toAccountId = toAccountId,
        type = type,
        customerName = customerName,
        amount = Amount(amount, amountDecimal = amountDecimal),
        imagePath = imagePath,
        createdOn = createdOn,
        updatedOn = updatedOn,
        customerId = customerId,
        invoiceNumber = invoiceNumber,
        quantity = quantity,
        unit = unit,
        unitPrice = unitPrice,
        deliveryLocation = deliveryLocation,
        deliveryNotes = deliveryNotes,
        driverName = driverName,
        vehicleNumber = vehicleNumber,
        supplierId = supplierId,
        supplierName = supplierName,
    )
}
