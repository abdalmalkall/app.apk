package com.naveenapps.expensemanager.core.domain.usecase.customer

import org.koin.dsl.module

val CustomerUseCaseModule = module {
    single { GetAllCustomerPaymentsUseCase(repository = get()) }
    single { AddCustomerPaymentUseCase(repository = get()) }
    single { DeleteCustomerPaymentUseCase(repository = get()) }
}