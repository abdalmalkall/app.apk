package com.naveenapps.expensemanager.commerce

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommerceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun invoiceFormSavesPartialSaleAndCollectionDialogSettlesDebt() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "ui-${UUID.randomUUID()}.db"
        val store = CommerceStore(context, name)
        try {
            store.saveProduct(Product(id = "sand-1", name = "رمل 1", sku = "SAND-1", price = 12000))
            val customer = store.saveCustomer(Customer(name = "أحمد", phone = "079"))
            var s by mutableStateOf(store.snapshot())
            var selected by mutableStateOf("")
            var collection by mutableStateOf(false)
            compose.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (selected.isBlank()) NewInvoicePage(s, false, "", {}, {}, {}, { token, c, lines, discount, tax, paid, method, notes, due ->
                                selected = store.issueInvoice(token, c, lines, discount, tax, paid, method, notes, due)
                                s = store.snapshot()
                            })
                            else InvoiceDetail(s, s.invoices.first { it.id == selected }, {}, { collection = true }, {}, {}, {})
                        }
                        if (collection) CollectionDialog(s.debt, false, { collection = false }, { token, amount, method, notes -> store.collectDebt(token, customer, amount, method, notes, selected); s = store.snapshot(); collection = false })
                    }
                }
            }
            compose.onNodeWithText("اختيار العميل: عميل نقدي").performClick()
            compose.onNodeWithText("أحمد · 079 · ${customer.takeLast(6)}").performClick()
            compose.onNodeWithText("الصنف: اختر صنفًا").performScrollTo().performClick()
            compose.onNodeWithText("رمل 1 · SAND-1").performClick()
            compose.onNodeWithText("الكمية • طن").performScrollTo().performTextReplacement("5")
            compose.onNodeWithText("إضافة إلى الفاتورة").performScrollTo().performClick()
            compose.onNodeWithText("المدفوع • فارغ = دفع كامل").performScrollTo().performTextReplacement("25")
            compose.onNodeWithText("حفظ وإصدار الفاتورة").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(60000L, s.sales()); assertEquals(25000L, s.cash()); assertEquals(35000L, s.debt)
            compose.onNodeWithText("تحصيل من هذه الفاتورة").performScrollTo()
            compose.onNodeWithText("تحصيل من هذه الفاتورة").performClick()
            compose.onNodeWithText("حفظ").performClick()
            compose.waitForIdle()
            assertEquals(60000L, s.sales()); assertEquals(60000L, s.cash()); assertEquals(0L, s.debt)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test
    @OptIn(ExperimentalTestApi::class)
    fun hardwareEnterAddsTheBarcodeProductBeforeACollidingSku() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "barcode-ui-${UUID.randomUUID()}.db"
        val store = CommerceStore(context, name)
        try {
            store.saveProduct(Product(id = "sand-1", name = "رمل 1", sku = "SAND-1", barcode = "1234567890128", price = 12000))
            store.saveProduct(Product(name = "أول بالترتيب", sku = "1234567890128", price = 8000))
            var s by mutableStateOf(store.snapshot())
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        NewInvoicePage(s, false, "", {}, {}, {}, { token, customer, lines, discount, tax, paid, method, notes, due ->
                            store.issueInvoice(token, customer, lines, discount, tax, paid, method, notes, due)
                            s = store.snapshot()
                        })
                    }
                }
            }
            val code = compose.onNodeWithText("باركود أو SKU • القارئ الخارجي ثم Enter")
            code.performScrollTo().performTextReplacement("1234567890128")
            code.performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithText("حفظ وإصدار الفاتورة").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, s.invoices.size)
            assertEquals("sand-1", s.items.single().productId)
            assertEquals("1", s.items.single().quantity)
            assertEquals(12000L, s.sales()); assertEquals(12000L, s.cash()); assertEquals(0L, s.debt)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun applicationNavigationRendersArabicPagesWithoutOldExpenseInterface() {
        compose.setContent { CommerceApp() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("نظرة واضحة على أعمالك").fetchSemanticsNodes().isNotEmpty() }
        val dir = File(System.getProperty("user.dir"), "build/commerce-verification").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "dashboard-ar.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        listOf("المنتجات", "العملاء", "الذمم", "الفواتير", "الدخل والمقبوضات", "التقارير", "الإعدادات").forEach { page ->
            compose.onNodeWithContentDescription("القائمة").performClick()
            compose.onAllNodesWithText(page).filter(hasClickAction()).onFirst().performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("القائمة").assertExists()
        }
    }
}
