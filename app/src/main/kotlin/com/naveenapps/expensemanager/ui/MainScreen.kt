package com.naveenapps.expensemanager.ui

import androidx.compose.runtime.Composable
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.navigation.ExpenseManagerScreens
import com.naveenapps.expensemanager.core.repository.ActivityComponentProvider

@Composable
fun MainScreen(
    composeNavigator: AppComposeNavigator,
    componentProvider: ActivityComponentProvider,
    isDarkTheme: Boolean,
    landingScreen: ExpenseManagerScreens,
) {
    com.naveenapps.expensemanager.commerce.CommerceApp()
}
