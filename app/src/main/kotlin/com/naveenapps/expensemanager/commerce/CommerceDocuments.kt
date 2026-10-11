package com.naveenapps.expensemanager.commerce

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import jxl.Workbook
import jxl.write.Label

object CommerceDocuments {
    private fun file(context: Context, name: String): File {
        val directory = File(context.cacheDir, "commerce-documents").apply { check(isDirectory || mkdirs()) { "تعذر إنشاء مجلد المستندات" } }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
        val extension = safe.substringAfterLast('.', "")
        val stem = if(extension.isEmpty()) safe else safe.substringBeforeLast('.')
        return File(directory,"$stem-${java.util.UUID.randomUUID()}${if(extension.isEmpty()) "" else ".$extension"}")
    }
    fun invoice(context: Context, s: Snapshot, i: Invoice): File {
        val rows = mutableListOf(
            s.settings["business"] ?: "مؤسستي", "فاتورة بيع • ${i.number}", dateText(i.created),
            "العميل: ${i.customerName}",
        )
        s.customers.firstOrNull { it.id == i.customerId }?.let { rows += "${it.phone} — ${it.address}" }
        rows += "الصنف | الكمية والوحدة | سعر الوحدة | الإجمالي"
        s.items.filter { it.invoiceId == i.id }.forEach {
            rows += "${it.name} | ${it.quantity} ${it.unit} | ${Money.format(it.price)} | ${Money.format(it.total)}"
            if (Money.decimal(it.returned) > BigDecimal.ZERO) rows += "كمية مرتجعة: ${it.returned} ${it.unit}"
        }
        rows += listOf("المجموع: ${Money.format(i.subtotal)} ${i.currency}", "الخصم: ${Money.format(i.discount)}", "الضريبة (${i.taxRate}%): ${Money.format(i.tax)}",
            "الإجمالي الأصلي: ${Money.format(i.total)}", "المرتجعات والتسويات: ${Money.format(i.credit)}", "صافي الفاتورة: ${Money.format(i.net)} ${i.currency}",
            "المدفوع الصافي: ${Money.format(i.paid)}", "المتبقي: ${Money.format(i.remaining)}", "الحالة: ${i.status} • ${i.method}")
        i.due?.let { rows += "الاستحقاق: ${dateText(it)}" }
        if (i.notes.isNotBlank()) rows += "ملاحظات: ${i.notes}"
        rows += "${s.settings["phone"].orEmpty()} • ${s.settings["address"].orEmpty()}"
        rows += s.settings["footer"] ?: "شكرًا لتعاملكم معنا"
        return pdf(context, i.number, rows, s.settings["paper"] ?: "A4", s.settings["logo"])
    }
    fun receipt(context: Context, s: Snapshot, payments: List<Payment>): File {
        require(payments.isNotEmpty())
        val invoice = s.invoices.first { it.id == payments.first().invoiceId }
        return pdf(context, payments.first().receipt, listOf(s.settings["business"] ?: "مؤسستي", "سند قبض • ${payments.first().receipt}",
            "العميل: ${invoice.customerName}", dateText(payments.first().created),
            "المقبوض: ${Money.format(payments.sumOf { it.amount })} ${invoice.currency}", "الطريقة: ${payments.first().method}") +
            payments.map { p -> "${s.invoices.first { it.id == p.invoiceId }.number}: ${Money.format(p.amount)} • ${p.receipt}" } +
            listOf("الرصيد الحالي للعميل: ${Money.format(s.balance(invoice.customerId.orEmpty()))}", payments.first().notes), s.settings["paper"] ?: "A4")
    }
    fun statement(context: Context, s: Snapshot, c: Customer): File {
        val invoiceIds = s.invoices.filter { it.customerId == c.id }.map { it.id }.toSet()
        val events = s.movements.filter { it.invoiceId in invoiceIds }.sortedBy { it.created }
        var balance = 0L
        val rows = events.map { m ->
            val delta = when (m.kind) { "SALE", "RETURN" -> m.amount; else -> -m.amount }
            balance += delta
            "${dateText(m.created)} | ${movementLabel(m.kind)} | ${Money.format(delta)} | الرصيد ${Money.format(balance)}"
        }
        return pdf(context, "statement-${c.id.replace(':', '-')}", listOf(s.settings["business"] ?: "مؤسستي", "كشف حساب: ${c.name}", "${c.phone} • ${c.address}",
            "التاريخ | الحركة | الأثر على الذمة | الرصيد") + rows + "الرصيد المستحق: ${Money.format(s.balance(c.id))} ${s.currency}")
    }
    fun reportRows(s: Snapshot, from: Long, to: Long, kind: String): List<List<String>> {
        val invoices = s.invoices.filter { it.created in from..to }
        return when (kind) {
            "الأصناف", "الكميات", "الربح التقديري" -> listOf(listOf("الصنف", "الوحدة", "الكمية الصافية", "قيمة البنود قبل الخصم والضريبة", "التكلفة المقدرة", "الربح بعد الخصم وقبل الضريبة", "الذمم الموزعة")) + s.products.map { p ->
                val stats = CommerceAnalytics.product(s, p.id, from, to)
                listOf(p.name, stats.quantity.keys.joinToString("، ").ifBlank { p.unit }, stats.quantity.entries.joinToString("، ") { "${it.value.toPlainString()} ${it.key}" }.ifBlank { "0" }, Money.format(stats.baseSales), stats.cost?.let(Money::format) ?: "تكلفة غير مكتملة", stats.profit?.let(Money::format) ?: "غير متاح", Money.format(stats.receivable))
            }
            "العملاء", "الذمم" -> listOf(listOf("العميل", "الهاتف", "صافي مشترياته في الفترة", "الرصيد المستحق الحالي", "آخر عملية")) + s.customers.filter { kind != "الذمم" || s.balance(it.id) > 0 }.map { c -> listOf(c.name, c.phone, Money.format(invoices.filter { it.customerId == c.id }.sumOf { it.net }), Money.format(s.balance(c.id)), s.invoices.firstOrNull { it.customerId == c.id }?.created?.let(::dateText).orEmpty()) }
            "الدخل والمقبوضات", "المدفوعات" -> listOf(listOf("التاريخ", "النوع", "المصدر", "المبلغ", "الطريقة", "الفاتورة")) + s.movements.filter { it.created in from..to && it.kind !in listOf("SALE", "RETURN") && (kind != "المدفوعات" || it.kind != "OTHER_INCOME") }.map { listOf(dateText(it.created), movementLabel(it.kind), it.source, Money.format(it.amount), it.method, s.invoices.firstOrNull { i -> i.id == it.invoiceId }?.number.orEmpty()) }
            else -> listOf(listOf("رقم الفاتورة", "التاريخ", "العميل", "الأصل", "المرتجعات", "الصافي", "المدفوع", "المتبقي", "الحالة", "الطريقة")) + invoices.map { listOf(it.number, dateText(it.created), it.customerName, Money.format(it.total), Money.format(it.credit), Money.format(it.net), Money.format(it.paid), Money.format(it.remaining), it.status, it.method) }
        }
    }
    fun report(context: Context, s: Snapshot, from: Long, to: Long, kind: String, format: String): File {
        val rows = reportRows(s, from, to, kind)
        return when (format) {
            "CSV" -> file(context, "report.csv").apply { writeText("\uFEFF" + rows.joinToString("\r\n") { row -> row.joinToString(",") { "\"${safeCell(it).replace("\"", "\"\"")}\"" } }) }
            "Excel" -> file(context, "report.xls").apply {
                val workbook = Workbook.createWorkbook(this)
                try { val sheet = workbook.createSheet("التقرير", 0); rows.forEachIndexed { y, row -> row.forEachIndexed { x, value -> sheet.addCell(Label(x, y, safeCell(value))) } }; workbook.write() } finally { workbook.close() }
            }
            else -> pdf(context, "report", listOf(s.settings["business"] ?: "مؤسستي", "تقرير $kind", "من ${dateText(from)} إلى ${dateText(to)}", "المبيعات الصافية حسب الحركات: ${Money.format(s.sales(from, to))}", "المقبوضات الصافية: ${Money.format(s.cash(from, to))}") + rows.map { it.joinToString(" | ") })
        }
    }
    private fun safeCell(value: String) = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
    fun pdf(context: Context, name: String, rows: List<String>, paper: String = "A4", logo: String? = null): File {
        val width = when (paper) { "58mm" -> 164; "80mm" -> 227; else -> 595 }
        val height = if (paper == "A4") 842 else 1100
        val margin = if (paper == "A4") 36 else 10
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 45, 60); textSize = if (paper == "A4") 13f else 9f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
        val target = file(context, "$name.pdf")
        val doc = ArabicPdfWriter(target)
        var pageNumber = 1
        var page = doc.startPage(width, height, pageNumber)
        var y = margin
        try {
            if (!logo.isNullOrBlank()) runCatching {
                val bytes = android.util.Base64.decode(logo, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, 56, 56, true)
                    try { page.canvas.drawBitmap(scaled, (width - margin - 56).toFloat(), y.toFloat(), null); y += 66 }
                    finally { if(scaled !== bitmap) scaled.recycle(); bitmap.recycle() }
                }
            }
            var tableRows = 0
            rows.forEachIndexed { index, text ->
                val cells = text.split(" | ")
                if (cells.size > 1 && paper == "A4") {
                    val usable = width - margin * 2
                    val weights = if (cells.size == 4) listOf(.4f, .2f, .2f, .2f) else List(cells.size) { 1f / cells.size }
                    val cellWidths = weights.map { (usable * it).toInt() }
                    paint.textSize = if (cells.size > 6) 10f else 12f
                    paint.isFakeBoldText = tableRows == 0
                    paint.color = Color.rgb(26, 52, 48)
                    val layouts = cells.mapIndexed { column, cell ->
                        StaticLayout.Builder.obtain(cell, 0, cell.length, paint, (cellWidths[column] - 12).coerceAtLeast(10))
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL).setLineSpacing(3f, 1f).build()
                    }
                    val rowHeight = layouts.maxOf { it.height } + 16
                    var drawn = 0
                    while (drawn < rowHeight) {
                        if (height - margin - 18 - y < 30) {
                            footer(page, paint, pageNumber, width, height, margin); doc.finishPage(page)
                            page = doc.startPage(width, height, ++pageNumber); y = margin
                        }
                        val fragment = minOf(rowHeight - drawn, height - margin - 18 - y)
                        val background = Paint().apply { color = if (tableRows == 0) Color.rgb(220, 240, 233) else if (tableRows % 2 == 0) Color.rgb(246, 248, 249) else Color.WHITE }
                        page.canvas.drawRect(margin.toFloat(), y.toFloat(), (width - margin).toFloat(), (y + fragment).toFloat(), background)
                        var right = width - margin
                        layouts.forEachIndexed { column, layout ->
                            val left = right - cellWidths[column]
                            page.canvas.save(); page.canvas.clipRect(left + 6, y, right - 6, y + fragment)
                            page.canvas.translate((left + 6).toFloat(), (y + 8 - drawn).toFloat()); layout.draw(page.canvas); page.canvas.restore()
                            right = left
                        }
                        y += fragment; drawn += fragment
                    }
                    tableRows++
                    y += 2
                    return@forEachIndexed
                }
                paint.textSize = if (paper != "A4") 9f else if (index == 0) 20f else if (index == 1) 15f else 12f
                paint.color = if (index < 2) Color.rgb(18, 108, 90) else Color.rgb(30, 45, 60)
                paint.isFakeBoldText = index < 2 || text.startsWith("الإجمالي") || text.startsWith("المتبقي")
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - margin * 2)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    .setLineSpacing(4f, 1f).setIncludePad(true).build()
                // Draw line-by-line so even a very long note can span pages without clipping.
                for (line in 0 until layout.lineCount) {
                    val lineHeight = layout.getLineBottom(line) - layout.getLineTop(line)
                    if (y + lineHeight > height - margin - 18) {
                        footer(page, paint, pageNumber, width, height, margin)
                        doc.finishPage(page)
                        page = doc.startPage(width, height, ++pageNumber); y = margin
                    }
                    page.canvas.save()
                    page.canvas.clipRect(margin, y, width - margin, y + lineHeight)
                    page.canvas.translate(margin.toFloat(), (y - layout.getLineTop(line)).toFloat())
                    layout.draw(page.canvas)
                    page.canvas.restore()
                    y += lineHeight
                }
                y += 7
            }
            footer(page, paint, pageNumber, width, height, margin)
            doc.finishPage(page)
            return doc.finish()
        } finally { doc.close() }
    }
    private fun footer(page: ArabicPdfWriter.Page, paint: TextPaint, n: Int, width: Int, height: Int, margin: Int) { paint.isFakeBoldText = false; page.canvas.drawText(n.toString(), width / 2f, (height - margin / 2).toFloat(), paint) }
    fun share(context: Context, file: File, mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = android.content.ClipData.newRawUri(file.name, uri)
        val chooser = Intent.createChooser(intent, "مشاركة الملف")
        if(context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
    fun print(context: Context, file: File, paper: String = "A4") {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val media = when (paper) { "58mm" -> PrintAttributes.MediaSize("58mm", "58mm", 2283, 15276); "80mm" -> PrintAttributes.MediaSize("80mm", "80mm", 3150, 15276); else -> PrintAttributes.MediaSize.ISO_A4 }
        manager.print(file.name, CommercePrintAdapter(file), PrintAttributes.Builder().setMediaSize(media).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
    }
    fun barcode(context: Context, s: Snapshot, p: Product): File {
        val code = p.barcode.ifBlank { p.sku }
        val format = if (code.all { it.code <= 127 }) BarcodeFormat.CODE_128 else BarcodeFormat.QR_CODE
        val matrix = MultiFormatWriter().encode(code, format, if (format == BarcodeFormat.CODE_128) 800 else 400, if (format == BarcodeFormat.CODE_128) 220 else 400)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        val doc = ArabicPdfWriter(file(context, "barcode-${p.id.replace(':', '-')}.pdf"))
        try {
            val page = doc.startPage(595, 300, 1)
            page.canvas.drawBitmap(bitmap, null, if (format == BarcodeFormat.CODE_128) android.graphics.Rect(50, 70, 545, 230) else android.graphics.Rect(217, 70, 377, 230), null)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; color = Color.BLACK }
            val text = "${s.settings["business"].orEmpty()} • ${p.name}"
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 495).setAlignment(Layout.Alignment.ALIGN_CENTER).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL).build()
            page.canvas.save(); page.canvas.translate(50f, 25f); layout.draw(page.canvas); page.canvas.restore()
            page.canvas.drawText(code, 50f, 265f, paint)
            doc.finishPage(page)
            return doc.finish()
        } finally { doc.close() }
    }
}
fun movementLabel(kind: String) = when (kind) {
    "SALE" -> "مبيعات"; "RETURN" -> "مرتجع / تسوية"; "INVOICE_PAYMENT" -> "قبض عند الإصدار"; "DEBT_COLLECTION" -> "تحصيل ذمم"; "OTHER_INCOME" -> "دخل آخر"; "REFUND" -> "رد نقدي"; else -> kind
}
