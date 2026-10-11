package com.naveenapps.expensemanager.core.domain.usecase.customer

import org.koin.dsl.module

val CustomerUseCaseModule = module {
    single { GetAllCustomersUseCase(repository = get()) }
    single { SaveCustomerUseCase(repository = get()) }
    single { GetAllCustomerPaymentsUseCase(repository = get()) }
    single { AddCustomerPaymentUseCase(repository = get()) }
    single { DeleteCustomerPaymentUseCase(repository = get()) }
}