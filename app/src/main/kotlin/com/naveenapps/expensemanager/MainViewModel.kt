package com.naveenapps.expensemanager

import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveenapps.expensemanager.core.domain.usecase.settings.onboarding.GetOnboardingStatusUseCase
import com.naveenapps.expensemanager.core.domain.usecase.settings.theme.GetCurrentThemeUseCase
import com.naveenapps.expensemanager.core.model.Theme
import com.naveenapps.expensemanager.core.repository.SettingsRepository
import com.naveenapps.expensemanager.feature.theme.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch


class MainViewModel(
    getCurrentThemeUseCase: GetCurrentThemeUseCase,
    getOnboardingStatusUseCase: GetOnboardingStatusUseCase,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _currentTheme = MutableStateFlow(
        Theme(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            R.string.choose_theme,
        ),
    )
    val currentTheme = _currentTheme.asStateFlow()

    private val _onboardingStatus = MutableStateFlow<Boolean?>(null)
    val onboardingStatus = _onboardingStatus.asStateFlow()

    // Unknown settings must not briefly expose the ledger before DataStore has loaded.
    private val _isAppLockEnabled = MutableStateFlow<Boolean?>(null)
    val isAppLockEnabled = _isAppLockEnabled.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated = _isAuthenticated.asStateFlow()

    init {
        getCurrentThemeUseCase.invoke().onEach {
            _currentTheme.value = it
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            _onboardingStatus.value = getOnboardingStatusUseCase.invoke()
        }

        settingsRepository.isAppLockEnabled().onEach {
            if (it && _isAppLockEnabled.value != true) _isAuthenticated.value = false
            _isAppLockEnabled.value = it
        }.launchIn(viewModelScope)
    }

    fun onAuthenticationSuccess() {
        _isAuthenticated.value = true
    }

    fun onAppBackgrounded() {
        if (_isAppLockEnabled.value != false) _isAuthenticated.value = false
    }
}
