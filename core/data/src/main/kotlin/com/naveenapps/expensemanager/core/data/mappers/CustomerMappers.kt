package com.naveenapps.expensemanager.core.data.mappers

import com.naveenapps.expensemanager.core.database.entity.CustomerEntity
import com.naveenapps.expensemanager.core.model.Customer

fun CustomerEntity.toDomainModel() = Customer(
    id = id,
    name = name,
    phone = phone,
    address = address,
    notes = notes,
    creditLimitMilliunits = creditLimitMilliunits,
    createdOn = createdOn,
    updatedOn = updatedOn,
)

fun Customer.toEntityModel() = CustomerEntity(
    id = id,
    name = name,
    normalizedName = name.trim().lowercase(java.util.Locale.ROOT),
    phone = phone,
    address = address,
    notes = notes,
    creditLimitMilliunits = creditLimitMilliunits,
    createdOn = createdOn,
    updatedOn = updatedOn,
)