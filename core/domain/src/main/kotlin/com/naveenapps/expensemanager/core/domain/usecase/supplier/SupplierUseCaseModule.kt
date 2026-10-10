package com.naveenapps.expensemanager.core.domain.usecase.supplier

import org.koin.dsl.module

val SupplierUseCaseModule = module {
    single { GetAllSuppliersUseCase(repository = get()) }
    single { SaveSupplierUseCase(repository = get()) }
    single { GetAllSupplierPaymentsUseCase(repository = get()) }
    single { AddSupplierPaymentUseCase(repository = get()) }
    single { DeleteSupplierPaymentUseCase(repository = get()) }
}