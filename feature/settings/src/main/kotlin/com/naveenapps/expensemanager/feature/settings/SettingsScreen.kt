package com.naveenapps.expensemanager.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.EditNotifications
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SettingsApplications
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naveenapps.designsystem.theme.NaveenAppsPreviewTheme
import com.naveenapps.designsystem.utils.AppPreviewsLightAndDarkMode
import com.naveenapps.expensemanager.core.designsystem.ui.components.AppCardView
import com.naveenapps.expensemanager.core.designsystem.ui.components.ExpenseManagerTopAppBar
import com.naveenapps.expensemanager.core.designsystem.ui.components.SettingRow
import com.naveenapps.expensemanager.core.designsystem.ui.components.SettingToggleRow
import com.naveenapps.expensemanager.core.designsystem.ui.components.SettingsSection
import com.naveenapps.expensemanager.core.designsystem.utils.ObserveAsEvents
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.Resource
import com.naveenapps.expensemanager.core.repository.BackupRepository
import com.naveenapps.expensemanager.core.repository.DriveBackupItem
import com.naveenapps.expensemanager.core.repository.DriveApiException
import com.naveenapps.expensemanager.core.repository.ShareRepository
import com.naveenapps.expensemanager.feature.language.LanguageDialogView
import com.naveenapps.expensemanager.feature.theme.ThemeDialogView
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    shareRepository: ShareRepository,
    backupRepository: BackupRepository,
    viewModel: SettingsViewModel = koinViewModel()
) {

    val state by viewModel.state.collectAsState()
    var pendingBackupAction by remember { mutableStateOf<SettingEvent?>(null) }
    var pendingBackupPassphrase by remember { mutableStateOf("") }
    var passphraseAction by remember { mutableStateOf<SettingEvent?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseError by remember { mutableStateOf(false) }
    var driveBackups by remember { mutableStateOf<List<DriveBackupItem>>(emptyList()) }
    var selectedBackupId by remember { mutableStateOf<String?>(null) }
    var restoreAccessToken by remember { mutableStateOf("") }
    var isBackupPickerVisible by remember { mutableStateOf(false) }
    var isLoadingBackups by remember { mutableStateOf(false) }
    var isBackupOperationInProgress by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun runBackupAction(
        action: SettingEvent,
        backupPassphrase: String,
        accessToken: String,
        backupFileId: String? = null,
    ) {
        if (isBackupOperationInProgress) return
        coroutineScope.launch {
            isBackupOperationInProgress = true
            try {
                val result = withContext(Dispatchers.IO) {
                    when (action) {
                        SettingEvent.Backup -> backupRepository.backupData(null, backupPassphrase, accessToken)
                        SettingEvent.Restore -> backupRepository.restoreData(null, backupPassphrase, accessToken, backupFileId)
                        SettingEvent.RateUs -> return@withContext Resource.Success(true)
                    }
                }
                if (result is Resource.Success && action == SettingEvent.Restore) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(launchIntent)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } else {
                        Toast.makeText(context, context.getString(R.string.restore_restart_required), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                if (result is Resource.Success && action == SettingEvent.Backup) {
                    Toast.makeText(context, context.getString(R.string.backup_completed), Toast.LENGTH_LONG).show()
                }
                if (result is Resource.Error) {
                    val driveError = result.exception as? DriveApiException
                    if (driveError?.statusCode == 401) {
                        Identity.getAuthorizationClient(context).clearToken(
                            ClearTokenRequest.builder().setToken(accessToken).build(),
                        )
                    }
                    val message = driveError?.message ?: context.getString(R.string.backup_operation_failed)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                isBackupOperationInProgress = false
            }
        }
    }

    fun loadDriveBackups(backupPassphrase: String, accessToken: String) {
        coroutineScope.launch {
            isLoadingBackups = true
            try {
                when (val result = withContext(Dispatchers.IO) { backupRepository.listDriveBackups(accessToken) }) {
                    is Resource.Success -> {
                        if (result.data.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.backup_list_empty), Toast.LENGTH_LONG).show()
                        } else {
                            driveBackups = result.data
                            selectedBackupId = result.data.first().id
                            pendingBackupPassphrase = backupPassphrase
                            restoreAccessToken = accessToken
                            isBackupPickerVisible = true
                        }
                    }

                    is Resource.Error -> {
                        val driveError = result.exception as? DriveApiException
                        if (driveError?.statusCode == 401) {
                            Identity.getAuthorizationClient(context).clearToken(
                                ClearTokenRequest.builder().setToken(accessToken).build(),
                            )
                        }
                        Toast.makeText(
                            context,
                            driveError?.message ?: context.getString(R.string.backup_operation_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } finally {
                isLoadingBackups = false
            }
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val action = pendingBackupAction
        pendingBackupAction = null
        if (result.resultCode == android.app.Activity.RESULT_OK && action != null) {
            try {
                val authorizationResult = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(result.data)
                val accessToken = authorizationResult.accessToken
                if (accessToken.isNullOrBlank()) {
                    Toast.makeText(context, context.getString(R.string.google_drive_sign_in_failed), Toast.LENGTH_LONG).show()
                } else if (action == SettingEvent.Restore) {
                    loadDriveBackups(pendingBackupPassphrase, accessToken)
                } else {
                    runBackupAction(action, pendingBackupPassphrase, accessToken)
                }
            } catch (exception: Exception) {
                Toast.makeText(context, context.getString(R.string.google_drive_sign_in_failed), Toast.LENGTH_LONG).show()
            } finally {
                pendingBackupPassphrase = ""
            }
        } else if (action != null) {
            pendingBackupPassphrase = ""
            Toast.makeText(context, context.getString(R.string.google_drive_sign_in_failed), Toast.LENGTH_LONG).show()
        }
    }

    fun startBackupAction(action: SettingEvent, backupPassphrase: String) {
        pendingBackupAction = action
        pendingBackupPassphrase = backupPassphrase
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(DRIVE_FILE_SCOPE),
                    Scope(DRIVE_APPDATA_SCOPE),
                ),
            )
            .build()
        Identity.getAuthorizationClient(context).authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    authorizationResult.pendingIntent?.let { pendingIntent ->
                        authorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                    } ?: run {
                        pendingBackupAction = null
                        pendingBackupPassphrase = ""
                        Toast.makeText(context, context.getString(R.string.google_drive_sign_in_failed), Toast.LENGTH_LONG).show()
                    }
                } else {
                    pendingBackupAction = null
                    val accessToken = authorizationResult.accessToken
                    if (accessToken.isNullOrBlank()) {
                        pendingBackupPassphrase = ""
                        Toast.makeText(context, context.getString(R.string.google_drive_sign_in_failed), Toast.LENGTH_LONG).show()
                    } else if (action == SettingEvent.Restore) {
                        loadDriveBackups(backupPassphrase, accessToken)
                        pendingBackupPassphrase = ""
                    } else {
                        runBackupAction(action, backupPassphrase, accessToken)
                        pendingBackupPassphrase = ""
                    }
                }
            }
            .addOnFailureListener {
                pendingBackupAction = null
                pendingBackupPassphrase = ""
                Toast.makeText(context, context.getString(R.string.google_drive_sign_in_failed), Toast.LENGTH_LONG).show()
            }
    }

    ObserveAsEvents(viewModel.event) {
        when (it) {
            SettingEvent.RateUs -> {
                shareRepository.openRateUs()
            }

            SettingEvent.Backup -> {
                passphraseAction = SettingEvent.Backup
            }

            SettingEvent.Restore -> {
                passphraseAction = SettingEvent.Restore
            }
        }
    }

    passphraseAction?.let { action ->
        AlertDialog(
            onDismissRequest = {
                passphraseAction = null
                passphrase = ""
                passphraseError = false
            },
            title = {
                Text(stringResource(if (action == SettingEvent.Backup) R.string.backup else R.string.restore))
            },
            text = {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        passphraseError = false
                    },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (passphraseError) R.string.backup_passphrase_minimum_error
                                else R.string.backup_passphrase_portability_note,
                            ),
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = passphraseError,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (passphrase.length < 12) {
                        passphraseError = true
                    } else {
                        val selectedAction = action
                        val selectedPassphrase = passphrase
                        passphraseAction = null
                        passphrase = ""
                        startBackupAction(selectedAction, selectedPassphrase)
                    }
                }) {
                    Text(stringResource(if (action == SettingEvent.Backup) R.string.backup else R.string.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    passphraseAction = null
                    passphrase = ""
                    passphraseError = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (isBackupPickerVisible) {
        AlertDialog(
            onDismissRequest = {
                isBackupPickerVisible = false
                pendingBackupPassphrase = ""
                restoreAccessToken = ""
            },
            title = { Text(stringResource(R.string.restore)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.backup_count, driveBackups.size))
                    Text(stringResource(R.string.restore_select_backup))
                    driveBackups.forEach { backup ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedBackupId = backup.id }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (backup.legacy) stringResource(R.string.backup_legacy_label, backup.name) else backup.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${DateFormat.getDateTimeInstance().format(Date(backup.modifiedAtEpochMs))} · ${formatBackupSize(backup.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (selectedBackupId == backup.id) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedBackupId != null && !isLoadingBackups,
                    onClick = {
                        val selectedId = selectedBackupId ?: return@TextButton
                        isBackupPickerVisible = false
                        runBackupAction(SettingEvent.Restore, pendingBackupPassphrase, restoreAccessToken, selectedId)
                        pendingBackupPassphrase = ""
                        restoreAccessToken = ""
                    },
                ) {
                    Text(stringResource(R.string.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    isBackupPickerVisible = false
                    pendingBackupPassphrase = ""
                    restoreAccessToken = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (isLoadingBackups || isBackupOperationInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(if (isLoadingBackups) R.string.backup_loading else R.string.backup_working)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(stringResource(if (isLoadingBackups) R.string.backup_loading else R.string.backup_working))
                }
            },
            confirmButton = {},
        )
    }

    SettingsScreenScaffoldView(
        state = state,
        onAction = viewModel::processAction
    )
}

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

private fun formatBackupSize(sizeBytes: Long): String = when {
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
    sizeBytes < 1024 * 1024 * 1024 -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun SettingsScreenScaffoldView(
    state: SettingState,
    onAction: (SettingAction) -> Unit,
) {
    if (state.showThemeSelection) {
        ThemeDialogView {
            onAction.invoke(SettingAction.DismissThemeSelection)
        }
    }

    if (state.showLanguageSelection) {
        LanguageDialogView {
            onAction.invoke(SettingAction.DismissLanguageSelection)
        }
    }

    Scaffold(
        topBar = {
            ExpenseManagerTopAppBar(
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                navigationBackClick = {
                    onAction.invoke(SettingAction.ClosePage)
                },
                title = stringResource(R.string.settings),
            )
        },
    ) { innerPadding ->
        SettingsScreenContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            state = state,
            onAction = onAction,
        )
    }
}

@Composable
private fun SettingsScreenContent(
    modifier: Modifier = Modifier,
    state: SettingState,
    onAction: (SettingAction) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(0.dp))

        // Personalization — shapes how the app looks and feels, so it comes first.
        SettingsSection(title = stringResource(R.string.personalization)) {
            AppCardView {
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.ShowThemeSelection) },
                    title = stringResource(id = R.string.theme),
                    subtitle = if (state.theme != null) {
                        stringResource(id = state.theme.titleResId)
                    } else {
                        stringResource(id = R.string.system_default)
                    },
                    icon = Icons.Outlined.Palette,
                    showDivider = true,
                )
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.ShowLanguageSelection) },
                    title = stringResource(
                        id = com.naveenapps.expensemanager.feature.language.R.string.language,
                    ),
                    subtitle = if (state.locale != null) {
                        stringResource(id = state.locale.titleResId)
                    } else {
                        stringResource(id = R.string.system_default)
                    },
                    icon = Icons.Outlined.Language,
                    showDivider = true,
                )
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.OpenCurrencyEdit) },
                    title = stringResource(id = R.string.currency),
                    subtitle = "${state.currency.name} (${state.currency.symbol})",
                    icon = Icons.Outlined.Payments,
                )
            }
        }

        // Notifications
        SettingsSection(title = stringResource(R.string.notifications)) {
            AppCardView {
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.OpenNotification) },
                    title = stringResource(id = R.string.reminder_notification),
                    subtitle = stringResource(id = R.string.selected_daily_reminder_time),
                    icon = Icons.Outlined.EditNotifications,
                )
            }
        }

        // Defaults — affects daily data entry, so it's promoted out of "Advanced".
        SettingsSection(title = stringResource(R.string.defaults)) {
            AppCardView {
                if (state.accounts.isNotEmpty() && state.selectedAccount != null) {
                    DropdownSettingItem(
                        label = stringResource(id = R.string.default_account),
                        selectedValue = state.selectedAccount.name,
                        icon = Icons.Outlined.AccountBalance,
                        items = state.accounts.map { it.name },
                        onItemSelected = { index ->
                            onAction.invoke(SettingAction.SelectAccount(state.accounts[index]))
                        },
                    )
                }

                if (state.expenseCategories.isNotEmpty() && state.selectedExpenseCategory != null) {
                    DropdownSettingItem(
                        label = stringResource(id = R.string.default_expense_category),
                        selectedValue = state.selectedExpenseCategory.titleResId?.let {
                            stringResource(it)
                        } ?: state.selectedExpenseCategory.name,
                        icon = Icons.AutoMirrored.Outlined.TrendingUp,
                        items = state.expenseCategories.map {
                            it.titleResId?.let { resId -> stringResource(resId) } ?: it.name
                        },
                        onItemSelected = { index ->
                            onAction.invoke(SettingAction.SelectExpenseCategory(state.expenseCategories[index]))
                        },
                    )
                }

                if (state.incomeCategories.isNotEmpty() && state.selectedIncomeCategory != null) {
                    DropdownSettingItem(
                        label = stringResource(id = R.string.default_income_category),
                        selectedValue = state.selectedIncomeCategory.titleResId?.let {
                            stringResource(it)
                        } ?: state.selectedIncomeCategory.name,
                        icon = Icons.AutoMirrored.Outlined.TrendingDown,
                        items = state.incomeCategories.map {
                            it.titleResId?.let { resId -> stringResource(resId) } ?: it.name
                        },
                        onItemSelected = { index ->
                            onAction.invoke(SettingAction.SelectIncomeCategory(state.incomeCategories[index]))
                        },
                    )
                }
                SettingToggleRow(
                    title = stringResource(id = R.string.compact_summary),
                    subtitle = stringResource(id = R.string.compact_summary_message),
                    icon = Icons.Outlined.DashboardCustomize,
                    checked = state.isCompactSummary,
                    onCheckedChange = { onAction.invoke(SettingAction.ToggleCompactSummary) },
                )
            }
        }

        // Backup and restore remain unavailable until their flows are fully verified.
        SettingsSection(title = stringResource(R.string.data_and_backup)) {
            AppCardView {
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.OpenExport) },
                    title = stringResource(id = R.string.export),
                    subtitle = stringResource(id = R.string.export_message),
                    icon = Icons.Outlined.Upload,
                )
            }
        }

        // Security
        SettingsSection(title = stringResource(R.string.security)) {
            AppCardView {
                SettingToggleRow(
                    title = stringResource(id = R.string.app_lock),
                    subtitle = stringResource(id = R.string.app_lock_message),
                    icon = Icons.Outlined.Lock,
                    checked = state.isAppLockEnabled,
                    onCheckedChange = { onAction.invoke(SettingAction.ToggleAppLock) },
                )
            }
        }

        // Advanced — only the genuinely rare, set-once-and-forget items live behind this now.
        SettingsSection(title = stringResource(R.string.advanced)) {
            AppCardView {
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.OpenAdvancedSettings) },
                    title = stringResource(id = R.string.advanced),
                    subtitle = stringResource(id = R.string.advanced_config_message),
                    icon = Icons.Outlined.SettingsApplications,
                )
            }
        }

        // Support — informational, so it stays last.
        SettingsSection(title = stringResource(R.string.support)) {
            AppCardView {
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.OpenRateUs) },
                    title = stringResource(id = R.string.rate_us),
                    subtitle = stringResource(id = R.string.rate_us_message),
                    icon = Icons.Outlined.RateReview,
                    showDivider = true,
                )
                SettingRow(
                    onClick = { onAction.invoke(SettingAction.OpenAboutUs) },
                    title = stringResource(id = com.naveenapps.expensemanager.feature.about.R.string.about_us),
                    subtitle = stringResource(id = R.string.about_the_app_information),
                    icon = Icons.Outlined.Info,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// Moved here from AdvancedSettingsScreen along with the Defaults section it belongs to.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSettingItem(
    label: String,
    selectedValue: String,
    icon: ImageVector,
    items: List<String>,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        modifier = modifier.fillMaxWidth(),
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = selectedValue,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.medium,
        ) {
            items.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item,
                            fontWeight = if (item == selectedValue) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = if (item == selectedValue) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                    onClick = {
                        onItemSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}


@AppPreviewsLightAndDarkMode
@Composable
fun SettingsScreenPreview() {
    NaveenAppsPreviewTheme(padding = 0.dp) {
        SettingsScreenScaffoldView(
            state = SettingState(
                currency = Currency("$", "US Dollar"),
                theme = null,
                showThemeSelection = false
            ),
            onAction = {}
        )
    }
}
