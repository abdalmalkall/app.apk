package com.naveenapps.expensemanager

import android.app.Application
import androidx.work.Configuration
import org.koin.androidx.workmanager.factory.KoinWorkerFactory

class ExpenseManagerApplication : Application(), Configuration.Provider {
	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(KoinWorkerFactory())
			.build()
}
