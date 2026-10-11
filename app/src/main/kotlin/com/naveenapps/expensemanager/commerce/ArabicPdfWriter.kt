package com.naveenapps.expensemanager.commerce

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Portable PDF with Arabic rendered by Android at 216 dpi. It needs no reader-side fonts.
 * Pages are written incrementally; only one uncompressed page is held in memory.
 * Text is rasterized and therefore not selectable. Each JPEG is embedded as a PDF image XObject.
 */
internal class ArabicPdfWriter(private val file: File) : AutoCloseable {
    data class Page(val bitmap: Bitmap, val canvas: Canvas, val width: Int, val height: Int)
    private val out = FileOutputStream(file)
    private var offset = 0L
    private val offsets = mutableMapOf<Int, Long>()
    private val pages = mutableListOf<Int>()
    private var current: Page? = null
    private var finished = false
    init { write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n") }
    fun startPage(width: Int, height: Int, number: Int): Page {
        check(current == null && number == pages.size + 1)
        val bitmap = Bitmap.createBitmap(width * 3, height * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(3f, 3f)
        return Page(bitmap, canvas, width, height).also { current = it }
    }
    fun finishPage(page: Page) {
        check(current === page)
        val jpeg = ByteArrayOutputStream()
        require(page.bitmap.compress(Bitmap.CompressFormat.JPEG, 94, jpeg)) { "تعذر إنشاء صورة PDF" }
        val bytes = jpeg.toByteArray()
        appendJpegPage(page.width, page.height, page.bitmap.width, page.bitmap.height, bytes)
        page.bitmap.recycle()
        current = null
    }
    /** Copy existing JPEG pages losslessly when selecting printer page ranges. */
    fun appendJpegPage(width:Int,height:Int,imageWidth:Int,imageHeight:Int,bytes:ByteArray,targetWidth:Int=width,targetHeight:Int=height) {
        val pageId=3+pages.size*3
        val imageId=pageId+1
        val contentId=pageId+2
        obj(imageId, "<< /Type /XObject /Subtype /Image /Width $imageWidth /Height $imageHeight /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>", bytes)
        val scale=minOf(targetWidth.toDouble()/width,targetHeight.toDouble()/height)
        val w=width*scale; val h=height*scale
        val content="q $w 0 0 $h ${(targetWidth-w)/2} ${(targetHeight-h)/2} cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII)
        obj(contentId, "<< /Length ${content.size} >>", content)
        obj(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $targetWidth $targetHeight] /Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>")
        pages+=pageId
    }
    fun finish(): File {
        check(current == null && !finished && pages.isNotEmpty())
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count ${pages.size} /Kids [${pages.joinToString(" ") { "$it 0 R" }}] >>")
        val start = offset
        val count = offsets.keys.max() + 1
        write("xref\n0 $count\n0000000000 65535 f \n")
        for (id in 1 until count) write("${offsets.getValue(id).toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
        out.flush()
        out.fd.sync()
        finished = true
        return file
    }
    private fun obj(id: Int, text: String, data: ByteArray? = null) {
        offsets[id] = offset
        write("$id 0 obj\n$text\n")
        if (data != null) { write("stream\n"); out.write(data); offset += data.size; write("\nendstream\n") }
        write("endobj\n")
    }
    private fun write(text: String) { val bytes = text.toByteArray(Charsets.ISO_8859_1); out.write(bytes); offset += bytes.size }
    override fun close() { current?.bitmap?.recycle(); current = null; out.close(); if (!finished) file.delete() }
}
