package com.naveenapps.expensemanager.commerce

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommerceDocumentsTest {
    @Test fun arabicPdfAndThermalFormatsGenerateRealPdfWithMultiplePages() {
        val c: Context = ApplicationProvider.getApplicationContext()
        val name = "pdf-${UUID.randomUUID()}.db"
        val store = CommerceStore(c, name)
        try {
            val customer = store.saveCustomer(Customer(name = "أحمد محمد", address = "عمّان — الأردن"))
            val invoice = store.issueInvoice("pdf", customer, listOf(DraftLine("sand-1", "5", 12000, "طن")), 0, "0", 25000, "نقدي", "فاتورة رمل مع نص عربي صحيح")
            val s = store.snapshot()
            val sample = CommerceDocuments.invoice(c, s, s.invoices.first { it.id == invoice })
            assertTrue(sample.length() > 1000)
            assertTrue(sample.readBytes().take(5).toByteArray().toString(Charsets.US_ASCII).startsWith("%PDF-"))
            val dir = File(System.getProperty("user.dir"), "build/commerce-verification").apply { mkdirs() }
            sample.copyTo(File(dir, "arabic-invoice.pdf"), overwrite = true)
            listOf("58mm", "80mm").forEach { paper ->
                val thermal = CommerceDocuments.invoice(c, s.copy(settings = s.settings + ("paper" to paper)), s.invoices.single())
                assertTrue(thermal.length() > 1000)
                thermal.copyTo(File(dir, "invoice-$paper.pdf"), overwrite = true)
            }
            val long = CommerceDocuments.pdf(c, "long", listOf("فاتورة عربية") + List(200) { "بند رقم $it: رمل مغسول 3.75 طن" })
            assertTrue(long.length() > 1000)
            long.copyTo(File(dir, "multipage.pdf"), overwrite = true)
            assertTrue(CommerceDocuments.statement(c, s, s.customers.single()).length() > 1000)
            assertTrue(CommerceDocuments.receipt(c, s, s.payments).length() > 1000)
        } finally { store.close(); c.deleteDatabase(name) }
    }
    @Test fun excelAndCsvContainActualArabicDataAndEscapeFormulaInjection() {
        val c: Context = ApplicationProvider.getApplicationContext()
        val s = Snapshot(customers = listOf(Customer(id = "c", name = "=1+1", phone = "079", notes = "عربي")), settings = mapOf("currency" to "JOD"))
        val csv = CommerceDocuments.report(c, s, 0, 1000, "العملاء", "CSV")
        assertTrue(csv.readText().startsWith("\uFEFF")); assertTrue(csv.readText().contains("'=1+1")); assertTrue(csv.readText().contains("العميل"))
        val excel = CommerceDocuments.report(c, s, 0, 1000, "العملاء", "Excel")
        val workbook = jxl.Workbook.getWorkbook(excel)
        try { assertEquals("العميل", workbook.getSheet(0).getCell(0, 0).contents); assertEquals("'=1+1", workbook.getSheet(0).getCell(0, 1).contents) } finally { workbook.close() }
    }
    @Test fun allRequestedBarcodeFormatsDecodeGeneratedImages() {
        val samples = mapOf(BarcodeFormat.EAN_13 to "1234567890128", BarcodeFormat.EAN_8 to "12345670", BarcodeFormat.UPC_A to "012345678905", BarcodeFormat.UPC_E to "01234565", BarcodeFormat.CODE_128 to "SAND-1", BarcodeFormat.CODE_39 to "SAND-2", BarcodeFormat.ITF to "1234567890", BarcodeFormat.QR_CODE to "SAND-1", BarcodeFormat.DATA_MATRIX to "SAND-1", BarcodeFormat.PDF_417 to "SAND-1", BarcodeFormat.AZTEC to "SAND-1")
        samples.forEach { (format, content) ->
            val matrix = MultiFormatWriter().encode(content, format, 1000, 500, mapOf(EncodeHintType.MARGIN to 24))
            val pixels = IntArray(matrix.width * matrix.height) { pos -> if (matrix[pos % matrix.width, pos / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
            val reader = MultiFormatReader()
            val result = try { reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(format), DecodeHintType.TRY_HARDER to true)) } catch (e: Exception) { throw AssertionError("Failed to decode $format", e) }
            assertEquals("$format", content, result.text)
        }
    }
}
