package com.naveenapps.expensemanager.core.data.mappers

import com.naveenapps.expensemanager.core.database.entity.CustomerPaymentEntity
import com.naveenapps.expensemanager.core.model.CustomerPayment

fun CustomerPayment.toEntityModel(): CustomerPaymentEntity = CustomerPaymentEntity(
    id = id,
    customerId = customerId,
    invoiceTransactionId = invoiceTransactionId,
    accountId = accountId,
    amount = amount,
    notes = notes,
    paidOn = paidOn,
)

fun CustomerPaymentEntity.toDomainModel(): CustomerPayment = CustomerPayment(
    id = id,
    customerId = customerId,
    invoiceTransactionId = invoiceTransactionId,
    accountId = accountId,
    amount = amount,
    notes = notes,
    paidOn = paidOn,
)