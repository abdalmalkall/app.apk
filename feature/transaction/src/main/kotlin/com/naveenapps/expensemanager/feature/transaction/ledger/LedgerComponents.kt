package com.naveenapps.expensemanager.feature.transaction.ledger

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naveenapps.expensemanager.core.model.Account
import com.naveenapps.expensemanager.core.model.AccountStatement
import com.naveenapps.expensemanager.core.model.LedgerMoney
import com.naveenapps.expensemanager.core.common.utils.toCompleteDateWithDate
import com.naveenapps.expensemanager.feature.transaction.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

enum class DebtFilter { ALL, OUTSTANDING, SETTLED }
enum class LedgerSort { LAST_ACTIVITY, DEBT, NAME }

@Composable
fun LedgerControls(total: String, filter: DebtFilter, sort: LedgerSort,
    onFilter: (DebtFilter) -> Unit, onSort: (LedgerSort) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("${stringResource(R.string.ledger_total_due)}: $total", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DebtFilter.entries.forEach { value ->
                FilterChip(selected = filter == value, onClick = { onFilter(value) }, label = {
                    Text(stringResource(when (value) {
                        DebtFilter.ALL -> R.string.ledger_all
                        DebtFilter.OUTSTANDING -> R.string.ledger_debtors
                        DebtFilter.SETTLED -> R.string.ledger_settled
                    }))
                })
            }
        }
        var expanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(when (sort) {
                    LedgerSort.LAST_ACTIVITY -> R.string.ledger_sort_recent
                    LedgerSort.DEBT -> R.string.ledger_sort_debt
                    LedgerSort.NAME -> R.string.ledger_sort_name
                }))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                LedgerSort.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(stringResource(when (value) {
                        LedgerSort.LAST_ACTIVITY -> R.string.ledger_sort_recent
                        LedgerSort.DEBT -> R.string.ledger_sort_debt
                        LedgerSort.NAME -> R.string.ledger_sort_name
                    })) }, onClick = { onSort(value); expanded = false })
                }
            }
        }
    }
}

private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
private fun parseDate(value: String): Date? = runCatching {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
    dateFormat().parse(value)
}.getOrNull()

/** The account is always an explicit selection. The form commits date/method/notes before save. */
@Composable
fun PaymentFields(id: String, partyName: String, totalDue: String, invoice: String,
    amount: String, accountId: String?, accounts: List<Account>, paidOn: Date, method: String, notes: String,
    error: Boolean, saving: Boolean, onAmount: (String) -> Unit, onAccount: (String) -> Unit,
    onDetails: (Date, String, String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    var date by remember(id) { mutableStateOf(dateFormat().format(paidOn)) }
    var dateError by remember(id) { mutableStateOf(false) }
    var menu by remember(id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.payment_amount)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(partyName, style = MaterialTheme.typography.titleMedium)
                Text("${stringResource(R.string.ledger_total_due)}: $totalDue")
                Text("${stringResource(R.string.invoice_number)}: $invoice")
                OutlinedTextField(value = amount, onValueChange = onAmount, enabled = !saving,
                    label = { Text(stringResource(R.string.payment_amount)) }, singleLine = true, isError = error)
                OutlinedTextField(value = date, onValueChange = { date = it; dateError = false }, enabled = !saving,
                    label = { Text(stringResource(R.string.ledger_payment_date)) }, singleLine = true, isError = dateError)
                OutlinedTextField(value = method, onValueChange = { onDetails(paidOn, it, notes) }, enabled = !saving,
                    label = { Text(stringResource(R.string.ledger_payment_method)) }, singleLine = true)
                Box {
                    TextButton(onClick = { menu = true }, enabled = !saving) {
                        Text(accounts.firstOrNull { it.id == accountId }?.name ?: stringResource(R.string.select_account))
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        accounts.forEach { account -> DropdownMenuItem(text = { Text(account.name) },
                            onClick = { onAccount(account.id); menu = false }) }
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { onDetails(paidOn, method, it) }, enabled = !saving,
                    label = { Text(stringResource(R.string.customer_notes)) })
                if (error) Text(stringResource(R.string.invalid_payment_message), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.ledger_overpayment_rule), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            val parsed = parseDate(date)
            if (parsed == null) dateError = true else {
                // Preserve the original time of day when only the calendar date changes.
                val time = Calendar.getInstance().apply { time = paidOn }
                val selected = Calendar.getInstance().apply {
                    timeInMillis = parsed.time
                    set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, time.get(Calendar.MINUTE))
                    set(Calendar.SECOND, time.get(Calendar.SECOND))
                    set(Calendar.MILLISECOND, time.get(Calendar.MILLISECOND))
                }.time
                onDetails(selected, method, notes)
                onSave()
            }
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun StatementDialog(statement: AccountStatement, onPeriod: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    var from by remember(statement.name) { mutableStateOf(dateFormat().format(Date(statement.from))) }
    var to by remember(statement.name) { mutableStateOf(dateFormat().format(Date(statement.to))) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ledger_statement)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statement.name, style = MaterialTheme.typography.titleMedium)
                statement.phone?.let { Text(it) }
                statement.address?.let { Text(it) }
                OutlinedTextField(value = from, onValueChange = { from = it }, label = { Text(stringResource(R.string.ledger_from)) }, singleLine = true, isError = error)
                OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text(stringResource(R.string.ledger_to)) }, singleLine = true, isError = error)
                TextButton(onClick = {
                    val start = parseDate(from)
                    val end = parseDate(to)?.let { Calendar.getInstance().apply {
                        time = it; add(Calendar.DAY_OF_MONTH, 1)
                    }.timeInMillis - 1 }
                    if (start == null || end == null || start.time > end) error = true else {
                        error = false; onPeriod(start.time, end)
                    }
                }) { Text(stringResource(R.string.ledger_apply_period)) }
                Text("${stringResource(R.string.ledger_opening)}: ${LedgerMoney.display(statement.openingBalance)}")
                statement.rows.forEach { row ->
                    HorizontalDivider()
                    Text("${Date(row.movement.timestamp).toCompleteDateWithDate()} • ${row.movement.reference}")
                    Text(row.movement.description)
                    Text("${stringResource(R.string.ledger_obligation)}: ${LedgerMoney.display(row.movement.obligation)}")
                    Text("${stringResource(R.string.total_paid)}: ${LedgerMoney.display(row.movement.payment)}")
                    Text("${stringResource(R.string.ledger_running)}: ${LedgerMoney.display(row.balance)}")
                }
                Text("${stringResource(R.string.ledger_closing)}: ${LedgerMoney.display(statement.closingBalance)}", style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ledger_print_unavailable), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
