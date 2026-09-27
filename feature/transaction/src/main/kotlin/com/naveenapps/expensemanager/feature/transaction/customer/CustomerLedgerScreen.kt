package com.naveenapps.expensemanager.feature.transaction.customer

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naveenapps.expensemanager.core.designsystem.components.EmptyItem
import com.naveenapps.expensemanager.core.designsystem.ui.components.AppCardView
import com.naveenapps.expensemanager.core.designsystem.ui.components.ExpenseManagerTopAppBar
import com.naveenapps.expensemanager.core.common.utils.toCompleteDateWithDate
import com.naveenapps.expensemanager.feature.transaction.R
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun CustomerLedgerScreen(viewModel: CustomerLedgerViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    CustomerLedgerContent(state = state, onAction = viewModel::processAction)
}

@Composable
private fun CustomerLedgerContent(
    state: CustomerLedgerState,
    onAction: (CustomerLedgerAction) -> Unit,
) {
    state.paymentDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { onAction(CustomerLedgerAction.DismissPayment) },
            title = { Text(stringResource(R.string.record_customer_payment)) },
            text = {
                Column {
                    Text("${stringResource(R.string.invoice_number)}: ${draft.invoiceNumber}")
                    Text("${stringResource(R.string.remaining_amount)}: ${draft.remainingAmount.amountString.orEmpty()}")
                    OutlinedTextField(
                        value = draft.amount,
                        onValueChange = { onAction(CustomerLedgerAction.UpdatePaymentAmount(it)) },
                        label = { Text(stringResource(R.string.payment_amount)) },
                        isError = draft.error,
                        supportingText = {
                            if (draft.error) Text(stringResource(R.string.invalid_payment_message))
                        },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onAction(CustomerLedgerAction.SavePayment) }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(CustomerLedgerAction.DismissPayment) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            ExpenseManagerTopAppBar(
                title = stringResource(R.string.debt_customer_ledger),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                navigationBackClick = { onAction(CustomerLedgerAction.ClosePage) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { onAction(CustomerLedgerAction.UpdateSearch(it)) },
                label = { Text(stringResource(R.string.search_customer)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            if (state.filteredCustomers.isEmpty()) {
                EmptyItem(
                    emptyItemText = stringResource(R.string.no_debt_customers_found),
                    icon = com.naveenapps.expensemanager.core.designsystem.R.drawable.ic_no_transaction,
                    modifier = Modifier
                        .fillMaxSize(),
                )
                return@Scaffold
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = state.filteredCustomers,
                    key = { it.id },
                ) { customer ->
                    val invoiceUnavailable = stringResource(R.string.invoice_not_assigned)
                    val isExpanded = state.expandedCustomerId == customer.id
                    AppCardView(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            onAction(CustomerLedgerAction.ToggleCustomerDetails(customer.id))
                                        },
                                ) {
                                    Text(
                                        text = customer.customerName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = customer.phone ?: stringResource(R.string.customer_phone_missing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = customer.totalAmount.amountString ?: "",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    IconButton(
                                        onClick = {
                                            onAction(
                                                CustomerLedgerAction.ShareCustomerStatement(
                                                    customerName = customer.customerName,
                                                    phone = customer.phone,
                                                    totalAmount = customer.totalAmount.amountString ?: "",
                                                    transactions = customer.transactions.map {
                                                        val transaction = it.transaction
                                                        "${transaction.invoiceNumber ?: invoiceUnavailable} | ${transaction.date} | ${transaction.amount.amountString.orEmpty()} | ${it.paidAmount.amountString.orEmpty()} | ${it.remainingAmount.amountString.orEmpty()}"
                                                    },
                                                ),
                                            )
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = stringResource(R.string.share_customer_statement),
                                        )
                                    }
                                }
                            }

                            Text(
                                text = stringResource(
                                    R.string.customer_transaction_count,
                                    customer.transactionCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )

                            Text(
                                text = "${stringResource(R.string.total_sales)}: ${customer.totalSales.amountString.orEmpty()}   " +
                                    "${stringResource(R.string.total_paid)}: ${customer.totalPaid.amountString.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )

                            if (isExpanded) {
                                customer.transactions.forEach { invoice ->
                                    val transaction = invoice.transaction
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        onAction(CustomerLedgerAction.OpenTransactionEdit(transaction.id))
                                                    },
                                            ) {
                                                Text(
                                                    text = "${transaction.invoiceNumber ?: invoiceUnavailable} • ${transaction.date}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    text = "${transaction.categoryName} • ${transaction.quantity ?: ""} ${transaction.unit.orEmpty()} • ${transaction.amount.amountString.orEmpty()}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    onAction(
                                                        CustomerLedgerAction.ShareInvoice(
                                                            customerName = customer.customerName,
                                                            phone = customer.phone,
                                                            invoiceNumber = transaction.invoiceNumber.orEmpty(),
                                                            totalAmount = transaction.amount.amountString.orEmpty(),
                                                            transactions = listOf(
                                                                "${transaction.categoryName} | ${transaction.quantity ?: ""} ${transaction.unit.orEmpty()} | ${transaction.unitPrice ?: ""} | ${transaction.amount.amountString.orEmpty()}",
                                                            ),
                                                        ),
                                                    )
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = stringResource(R.string.share_invoice),
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${stringResource(R.string.total_paid)}: ${invoice.paidAmount.amountString.orEmpty()} • " +
                                                "${stringResource(R.string.remaining_amount)}: ${invoice.remainingAmount.amountString.orEmpty()} • " +
                                                stringResource(invoice.paymentStatus.label),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        invoice.payments.forEach { payment ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "${payment.paidOn.toCompleteDateWithDate()}: ${payment.amount}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                IconButton(onClick = {
                                                    onAction(CustomerLedgerAction.DeletePayment(payment.id))
                                                }) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Delete,
                                                        contentDescription = stringResource(R.string.delete_payment),
                                                    )
                                                }
                                            }
                                        }
                                        if (invoice.remainingAmount.amount > 0.0) {
                                            Button(
                                                onClick = {
                                                    onAction(CustomerLedgerAction.StartPayment(customer.id, invoice))
                                                },
                                            ) {
                                                Text(stringResource(R.string.record_customer_payment))
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

private val CustomerPaymentStatus.label: Int
    get() = when (this) {
        CustomerPaymentStatus.UNPAID -> R.string.payment_unpaid
        CustomerPaymentStatus.PARTIALLY_PAID -> R.string.payment_partial
        CustomerPaymentStatus.PAID -> R.string.payment_paid
    }
