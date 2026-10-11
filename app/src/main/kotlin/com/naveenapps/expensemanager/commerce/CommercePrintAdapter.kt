package com.naveenapps.expensemanager.commerce

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.util.UUID

internal class CommercePrintAdapter(private val file: File) : PrintDocumentAdapter() {
    private var width: Int? = null
    private var height: Int? = null
    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, signal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (signal.isCanceled) { callback.onLayoutCancelled(); return }
        try {
            CommercePdfFile(file).use { pdf ->
                width = new.mediaSize?.widthMils?.let { ((it * 72L + 500) / 1000).toInt() }
                height = new.mediaSize?.heightMils?.let { ((it * 72L + 500) / 1000).toInt() }
                callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pdf.pageCount).build(), old != new)
            }
        } catch (e: Exception) { callback.onLayoutFailed(e.localizedMessage) }
    }
    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal, callback: WriteResultCallback) {
        val output = File(file.parentFile, "print-${UUID.randomUUID()}.pdf")
        try {
            if (signal.isCanceled) { callback.onWriteCancelled(); return }
            val written = selectPages(output, file, pages, signal, width, height)
            if (signal.isCanceled) { callback.onWriteCancelled(); return }
            output.inputStream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(destination).use { input.copyTo(it) } }
            if(signal.isCanceled) { callback.onWriteCancelled(); return }
            callback.onWriteFinished(written.map { PageRange(it, it) }.toTypedArray())
        } catch (e: Exception) { if (signal.isCanceled) callback.onWriteCancelled() else callback.onWriteFailed(e.localizedMessage) }
        finally { output.delete() }
    }
    companion object {
        fun indices(count: Int, ranges: Array<out PageRange>): List<Int> = (0 until count).filter { n -> ranges.any { n in it.start..it.end } }
        fun selectPages(output:File, source:File, ranges:Array<out PageRange>, signal:CancellationSignal, width:Int?=null, height:Int?=null): List<Int> {
            CommercePdfFile(source).use { reader ->
                val selected=indices(reader.pageCount,ranges); require(selected.isNotEmpty()) { "لا توجد صفحات للطباعة" }
                ArabicPdfWriter(output).use { writer ->
                    selected.forEachIndexed { n,index ->
                        if(signal.isCanceled) error("ألغيت الطباعة")
                        val page=reader.page(index)
                        writer.appendJpegPage(page.width,page.height,page.imageWidth,page.imageHeight,page.jpeg,width ?: page.width,height ?: page.height)
                    }
                    writer.finish()
                }
                return selected
            }
        }
    }
}
