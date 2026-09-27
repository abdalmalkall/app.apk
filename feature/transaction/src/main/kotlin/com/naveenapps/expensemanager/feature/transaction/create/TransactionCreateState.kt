package com.naveenapps.expensemanager.feature.transaction.create

import androidx.compose.runtime.Stable
import com.naveenapps.expensemanager.core.model.AccountUiModel
import com.naveenapps.expensemanager.core.model.Category
import com.naveenapps.expensemanager.core.model.Currency
import com.naveenapps.expensemanager.core.model.TextFieldValue
import com.naveenapps.expensemanager.core.model.TransactionType
import java.util.Date

@Stable
data class TransactionCreateState(
    val amount: TextFieldValue<String>,
    val notes: TextFieldValue<String>,
    val customerName: TextFieldValue<String>,
    val customerPhone: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val quantity: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val unit: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val unitPrice: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val deliveryLocation: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val deliveryNotes: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val driverName: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val vehicleNumber: TextFieldValue<String> = TextFieldValue("", onValueChange = null),
    val dateTime: Date,
    val transactionType: TransactionType,
    val currency: Currency,
    val selectedCategory: Category,
    val selectedFromAccount: AccountUiModel,
    val selectedToAccount: AccountUiModel,
    val accounts: List<AccountUiModel>,
    val categories: List<Category>,
    val accountSelection: AccountSelection,
    val showDeleteDialog: Boolean,
    val showDeleteButton: Boolean,
    val showNumberPad: Boolean,
    val showCategorySelection: Boolean,
    val showAccountSelection: Boolean,
    val showDateSelection: Boolean,
    val showTimeSelection: Boolean,
    val attachments: List<String> = emptyList(),
    val showAttachmentPicker: Boolean = false,
)

enum class AccountSelection {
    FROM_ACCOUNT,
    TO_ACCOUNT
}

data class TransactionCreateInitSetupState(
    val isCategorySyncCompleted: Boolean,
    val isAccountSyncCompleted: Boolean,
)