package com.naveenapps.expensemanager.core.data.repository

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import com.naveenapps.expensemanager.core.common.utils.openAppSettings
import com.naveenapps.expensemanager.core.common.utils.openEmailOption
import com.naveenapps.expensemanager.core.common.utils.openEmailToOption
import com.naveenapps.expensemanager.core.common.utils.openPrintOption
import com.naveenapps.expensemanager.core.common.utils.openRateUs
import com.naveenapps.expensemanager.core.common.utils.openShareOption
import com.naveenapps.expensemanager.core.common.utils.openWebPage
import com.naveenapps.expensemanager.core.repository.FirebaseSettingsRepository
import com.naveenapps.expensemanager.core.repository.ShareRepository
import java.io.File

class ShareRepositoryImpl(
    val context: Context,
    private val firebaseSettingsRepository: FirebaseSettingsRepository
) : ShareRepository {

    override fun sendEmail(file: File?) {
        file?.let {
            context.openEmailOption(it)
        } ?: run {
            openEmailToOption(context, firebaseSettingsRepository.getFeedbackEmail())
        }
    }

    override fun share(file: File?) {
        file?.let {
            context.openShareOption(it)
        }
    }

    override fun shareInvoice(
        customerName: String,
        phone: String?,
        invoiceNumber: String,
        totalAmount: String,
        transactions: List<String>,
    ) {
        val body = buildString {
            appendLine("Invoice Number: $invoiceNumber")
            appendLine("Customer Name: $customerName")
            phone?.takeIf(String::isNotBlank)?.let { appendLine("Phone: $it") }
            appendLine("Total Amount: $totalAmount")
            appendLine()
            appendLine("Details:")
            transactions.forEach { appendLine("- $it") }
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Invoice $invoiceNumber")
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Send invoice"))
    }

    override fun shareCustomerStatement(
        customerName: String,
        phone: String?,
        totalAmount: String,
        transactions: List<String>,
    ) {
        val body = buildString {
            appendLine("Customer Statement")
            appendLine("Customer Name: $customerName")
            phone?.takeIf(String::isNotBlank)?.let { appendLine("Phone: $it") }
            appendLine()
            transactions.forEach { appendLine(it) }
            appendLine()
            appendLine("Outstanding Balance: $totalAmount")
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Statement for $customerName")
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share customer statement"))
    }

    override fun print(file: File?) {
        file?.let {
            context.openPrintOption(it)
        }
    }

    override fun openRateUs() {
        context.openRateUs()
    }

    override suspend fun requestInAppReview() {
        // `context` is normally an Activity here (this repository is Activity-scoped in
        // KoinActivityModule.kt), which `launchReview` requires. Guard anyway in case this
        // ever resolves from the app-scoped registration instead, and never let a failure here
        // (no network, Play throttling it, etc.) surface to the user.
        val activity = context as? Activity ?: return
        runCatching {
            val reviewManager = ReviewManagerFactory.create(context)
            val reviewInfo = reviewManager.requestReview()
            reviewManager.launchReview(activity, reviewInfo)
        }
    }

    override fun openPrivacy() {
        context.openWebPage(firebaseSettingsRepository.getPrivacyURL())
    }

    override fun openTerms() {
        context.openWebPage(firebaseSettingsRepository.getTermsURL())
    }

    override fun openAboutUs() {
        context.openWebPage(firebaseSettingsRepository.getAboutUsURL())
    }

    override fun openGithub() {
        context.openWebPage(firebaseSettingsRepository.getGithubURL())
    }

    override fun openInstagram() {
        context.openWebPage(firebaseSettingsRepository.getInstagramURL())
    }

    override fun openTwitter() {
        context.openWebPage(firebaseSettingsRepository.getTwitterURL())
    }

    override fun openAppSettings() {
        context.openAppSettings()
    }
}