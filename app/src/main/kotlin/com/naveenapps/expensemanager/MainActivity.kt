package com.naveenapps.expensemanager

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.naveenapps.designsystem.theme.NaveenAppsTheme
import com.naveenapps.expensemanager.core.designsystem.utils.shouldUseDarkTheme
import com.naveenapps.expensemanager.core.navigation.AppComposeNavigator
import com.naveenapps.expensemanager.core.navigation.ExpenseManagerScreens
import com.naveenapps.expensemanager.core.repository.ActivityComponentProvider
import com.naveenapps.expensemanager.ui.AppLockScreen
import com.naveenapps.expensemanager.ui.MainScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.scope.AndroidScopeComponent
import org.koin.androidx.scope.activityScope
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.scope.Scope

internal class MainActivity : AppCompatActivity(), AndroidScopeComponent {

    private val appComposeNavigator: AppComposeNavigator by inject()

    override val scope: Scope by activityScope()

    private val viewModel: MainViewModel by viewModel()

    private val activityComponentProvider: ActivityComponentProvider by scope.inject()

    private var authenticationError by mutableStateOf("")

    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode == RESULT_OK) { authenticationError = ""; viewModel.onAuthenticationSuccess() }
        else authenticationError = "لم يكتمل التحقق من قفل الجهاز؛ حاول مجددًا"
    }

    private val activityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result: ActivityResult ->
        if (result.resultCode != RESULT_OK) {
            Log.i("App", "Update flow failed! Result code: " + result.resultCode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        activityComponentProvider.getBackupRepository()

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.onboardingStatus.collectLatest {
                    splashScreen.setKeepOnScreenCondition {
                        it == null || viewModel.isAppLockEnabled.value == null
                    }
                }
            }
        }

        setContent {
            // Provided explicitly rather than relying on the automatic ViewTree lookup that
            // rememberLauncherForActivityResult() falls back to (LocalView.current walking up to
            // the decor view's ViewTreeActivityResultRegistryOwner). That lookup can race with
            // window attachment right after this Activity is (re)created — e.g. following an
            // in-app locale switch, a config change, or process restoration — occasionally
            // resolving to null and crashing deep inside the Home screen's NavHost destination
            // with "No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner".
            // Providing it here up front removes that race entirely.
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides this) {
                val currentTheme by viewModel.currentTheme.collectAsState()
                val onBoardingStatus by viewModel.onboardingStatus.collectAsState()
                val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsState()
                val isAuthenticated by viewModel.isAuthenticated.collectAsState()
                val isDarkTheme = shouldUseDarkTheme(theme = currentTheme.mode)

                if (onBoardingStatus != null && isAppLockEnabled != null) {
                    val showLock = isAppLockEnabled == true && !isAuthenticated

                    if (showLock) {
                        NaveenAppsTheme(isDarkTheme = isDarkTheme) {
                            LaunchedEffect(Unit) { showBiometricPrompt() }
                            AppLockScreen(onUnlockClick = ::showBiometricPrompt, error = authenticationError)
                        }
                    } else {
                        MainScreen(
                            composeNavigator = appComposeNavigator,
                            componentProvider = activityComponentProvider,
                            isDarkTheme = isDarkTheme,
                            landingScreen = if (onBoardingStatus == true) {
                                ExpenseManagerScreens.Home
                            } else {
                                ExpenseManagerScreens.IntroScreen
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        launchAppUpdateCheck()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    @Suppress("DEPRECATION")
    private fun confirmDeviceCredential() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val intent = keyguard?.createConfirmDeviceCredentialIntent(getString(R.string.app_lock_title), getString(R.string.app_lock_description))
        if(intent != null) credentialLauncher.launch(intent)
        else authenticationError = "قفل التطبيق مفعّل؛ اضبط رمزًا أو بصمة لقفل الجهاز من إعدادات Android ثم حاول مجددًا"
    }

    private fun showBiometricPrompt() {
        if (isFinishing || isDestroyed) return
        authenticationError = ""
        // STRONG|CREDENTIAL is unsupported on Android 9/10. Keep strong biometrics
        // there and launch system credential confirmation separately; never grant access on error.
        val authenticators = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else BiometricManager.Authenticators.BIOMETRIC_STRONG
        if(BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            confirmDeviceCredential()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticationError = ""
                viewModel.onAuthenticationSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if(Build.VERSION.SDK_INT < 30 && errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) confirmDeviceCredential()
                else authenticationError = errString.toString()
            }
            override fun onAuthenticationFailed() { authenticationError = "لم تنجح مطابقة البصمة؛ حاول مجددًا" }
        })
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock_title))
            .setDescription(getString(R.string.app_lock_description))
            .setAllowedAuthenticators(authenticators)
        if(Build.VERSION.SDK_INT < 30) builder.setNegativeButtonText("استخدام رمز قفل الجهاز")
        prompt.authenticate(builder.build())
    }

    private fun launchAppUpdateCheck() {
        val appUpdateManager = AppUpdateManagerFactory.create(this)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        // appUpdateInfo() is async (backed by a Play Core service call), so the result can land
        // after this Activity instance has already been destroyed — e.g. a config change like a
        // rotation or an in-app locale switch (AppCompatDelegate.setApplicationLocales triggers a
        // recreate). Once destroyed, activityResultLauncher is unregistered by the framework, so
        // calling launch() on it from a stale callback crashes with
        // "Attempting to launch an unregistered ActivityResultLauncher". Scoping the listener to
        // this Activity (rather than a plain addOnSuccessListener) makes Play Services drop the
        // callback automatically once the Activity stops, so it never fires against a destroyed
        // instance.
        appUpdateInfoTask.addOnSuccessListener(this) { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) &&
                (appUpdateInfo.clientVersionStalenessDays() ?: -1) >= DAYS_FOR_FLEXIBLE_UPDATE
            ) {
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    activityResultLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                )
            }
        }
    }

    companion object {
        private const val DAYS_FOR_FLEXIBLE_UPDATE: Int = 3
    }
}
