package com.naveenapps.expensemanager.feature.transaction.supplier

import com.naveenapps.expensemanager.core.common.utils.toCompleteDateWithDate
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.naveenapps.expensemanager.feature.transaction.ledger.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naveenapps.expensemanager.core.designsystem.components.EmptyItem
import com.naveenapps.expensemanager.core.designsystem.ui.components.AppCardView
import com.naveenapps.expensemanager.core.designsystem.ui.components.ExpenseManagerTopAppBar
import com.naveenapps.expensemanager.feature.transaction.R
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SupplierLedgerScreen(viewModel: SupplierLedgerViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    SupplierLedgerContent(state, viewModel::processAction)
}

@Composable
private fun SupplierLedgerContent(
    state: SupplierLedgerState,
    onAction: (SupplierLedgerAction) -> Unit,
) {
    val supplierDraft = state.supplierDraft
    if (supplierDraft != null) {
        AlertDialog(
            onDismissRequest = { if (!supplierDraft.isSaving) onAction(SupplierLedgerAction.DismissSupplierEditor) },
            title = { Text(stringResource(if (supplierDraft.id == null) R.string.add_supplier else R.string.edit_supplier)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = supplierDraft.name,
                        onValueChange = { onAction(SupplierLedgerAction.UpdateSupplierField(SupplierProfileField.NAME, it)) },
                        label = { Text(stringResource(R.string.supplier_name)) },
                        isError = supplierDraft.error && supplierDraft.name.isBlank(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = supplierDraft.phone,
                        onValueChange = { onAction(SupplierLedgerAction.UpdateSupplierField(SupplierProfileField.PHONE, it)) },
                        label = { Text(stringResource(R.string.supplier_phone)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = supplierDraft.address,
                        onValueChange = { onAction(SupplierLedgerAction.UpdateSupplierField(SupplierProfileField.ADDRESS, it)) },
                        label = { Text(stringResource(R.string.supplier_address)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = supplierDraft.companyName,
                        onValueChange = { onAction(SupplierLedgerAction.UpdateSupplierField(SupplierProfileField.COMPANY_NAME, it)) },
                        label = { Text(stringResource(R.string.supplier_company)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = supplierDraft.notes,
                        onValueChange = { onAction(SupplierLedgerAction.UpdateSupplierField(SupplierProfileField.NOTES, it)) },
                        label = { Text(stringResource(R.string.supplier_notes)) },
                        minLines = 2,
                    )
                    if (supplierDraft.error) Text(stringResource(R.string.supplier_save_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !supplierDraft.isSaving, onClick = { onAction(SupplierLedgerAction.SaveSupplier) }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(enabled = !supplierDraft.isSaving, onClick = { onAction(SupplierLedgerAction.DismissSupplierEditor) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    state.paymentDraft?.let { draft ->
        val party = state.allSuppliers.firstOrNull { it.supplier.id == draft.supplierId }
        PaymentFields(
            id = draft.paymentId, partyName = party?.supplier?.name.orEmpty(),
            totalDue = party?.outstanding?.amountString.orEmpty(),
            invoice = draft.invoiceTransactionId,
            amount = draft.amount, accountId = draft.accountId, accounts = state.accounts,
            paidOn = draft.paidOn, method = draft.method, notes = draft.notes,
            error = draft.error, saving = draft.isSaving,
            onAmount = { onAction(SupplierLedgerAction.UpdatePaymentAmount(it)) },
            onAccount = { onAction(SupplierLedgerAction.SelectPaymentAccount(it)) },
            onDetails = { date, method, notes -> onAction(SupplierLedgerAction.PaymentDetails(date, method, notes)) },
            onSave = { onAction(SupplierLedgerAction.SavePayment) },
            onDismiss = { onAction(SupplierLedgerAction.DismissPayment) },
        )
    }
    state.statement?.let { statement ->
        StatementDialog(statement, { from, to -> onAction(SupplierLedgerAction.StatementPeriod(from, to)) },
            { onAction(SupplierLedgerAction.DismissStatement) })
    }

    val selected = state.allSuppliers.firstOrNull { it.supplier.id == state.expandedSupplierId }
    BackHandler(enabled = selected != null) { onAction(SupplierLedgerAction.ToggleSupplier(state.expandedSupplierId!!)) }
    Scaffold(
        topBar = {
            ExpenseManagerTopAppBar(
                title = selected?.supplier?.name ?: stringResource(R.string.suppliers),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                navigationBackClick = { if (selected != null) onAction(SupplierLedgerAction.ToggleSupplier(state.expandedSupplierId!!)) else onAction(SupplierLedgerAction.ClosePage) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selected == null) {
                Button(
                    onClick = { onAction(SupplierLedgerAction.AddSupplier) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add_supplier))
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { onAction(SupplierLedgerAction.UpdateSearch(it)) },
                    label = { Text(stringResource(R.string.search_supplier)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LedgerControls(state.totalOutstanding, state.debtFilter, state.sort,
                    { onAction(SupplierLedgerAction.SetFilter(it)) }, { onAction(SupplierLedgerAction.SetSort(it)) })
            }
            state.message?.let { message ->
                Snackbar(action = { TextButton(onClick = { onAction(SupplierLedgerAction.DismissMessage) }) { Text(stringResource(R.string.cancel)) } }) { Text(stringResource(message)) }
            }
            if (state.isLoading) CircularProgressIndicator(Modifier.padding(16.dp))
            if (!state.isLoading && selected == null && state.filteredSuppliers.isEmpty()) {
                EmptyItem(
                    emptyItemText = stringResource(R.string.no_suppliers_found),
                    icon = com.naveenapps.expensemanager.core.designsystem.R.drawable.ic_no_transaction,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selected == null && state.recentPurchases.isNotEmpty()) {
                        item {
                            Text(stringResource(R.string.ledger_recent_purchases), style = MaterialTheme.typography.titleMedium)
                            state.recentPurchases.forEach { purchase ->
                                Text("${purchase.supplierName.orEmpty()} • ${purchase.date} • ${purchase.amount.amountString.orEmpty()}",
                                    modifier = Modifier.fillMaxWidth().clickable { onAction(SupplierLedgerAction.OpenPurchase(purchase.id)) }.padding(vertical = 8.dp))
                            }
                        }
                    }
                    items(if (selected != null) listOf(selected) else state.filteredSuppliers, key = { it.supplier.id }) { item ->
                        val expanded = state.expandedSupplierId == item.supplier.id
                        AppCardView(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        Modifier.weight(1f).clickable {
                                            onAction(SupplierLedgerAction.ToggleSupplier(item.supplier.id))
                                        },
                                    ) {
                                        Text(item.supplier.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(item.supplier.phone.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "${stringResource(R.string.total_purchases)}: ${item.totalPurchases.amountString.orEmpty()}  " +
                                                "${stringResource(R.string.total_paid)}: ${item.totalPaid.amountString.orEmpty()}  " +
                                                "${stringResource(R.string.remaining_amount)}: ${item.outstanding.amountString.orEmpty()}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    IconButton(onClick = { onAction(SupplierLedgerAction.EditSupplier(item.supplier.id)) }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_supplier))
                                    }
                                }
                                if (expanded) {
                                    Text("${stringResource(R.string.ledger_invoice_count)}: ${item.invoices.size}")
                                    if (item.lastActivity > 0) Text("${stringResource(R.string.ledger_last_activity)}: ${java.util.Date(item.lastActivity).toCompleteDateWithDate()}")
                                    if (item.lastPurchase > 0) Text("${stringResource(R.string.ledger_last_purchase)}: ${java.util.Date(item.lastPurchase).toCompleteDateWithDate()}")
                                    item.supplier.notes?.let { Text(it) }
                                    TextButton(onClick = { onAction(SupplierLedgerAction.ShowStatement(item.supplier.id)) }) {
                                        Text(stringResource(R.string.ledger_statement))
                                    }
                                    item.supplier.companyName?.takeIf(String::isNotBlank)?.let { Text(it) }
                                    item.supplier.address?.takeIf(String::isNotBlank)?.let { Text(it) }
                                    Button(onClick = { onAction(SupplierLedgerAction.StartPurchase(item.supplier.id)) }) {
                                        Text(stringResource(R.string.record_purchase))
                                    }
                                    item.invoices.forEach { invoice ->
                                        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                            Text(
                                                "${invoice.transaction.date} • ${invoice.transaction.amount.amountString.orEmpty()}",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                "${stringResource(R.string.total_paid)}: ${invoice.paid.amountString.orEmpty()} • " +
                                                    "${stringResource(R.string.remaining_amount)}: ${invoice.remaining.amountString.orEmpty()}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            invoice.payments.forEach { payment ->
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("${payment.paidOn.toCompleteDateWithDate()}: ${payment.amount} • ${payment.notes}", Modifier.weight(1f))
                                                    IconButton(onClick = { onAction(SupplierLedgerAction.DeletePayment(payment.id)) }) {
                                                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_payment))
                                                    }
                                                }
                                            }
                                            if (invoice.remaining.amount > 0.0) {
                                                TextButton(onClick = { onAction(SupplierLedgerAction.StartPayment(item.supplier.id, invoice)) }) {
                                                    Text(stringResource(R.string.pay_supplier))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
