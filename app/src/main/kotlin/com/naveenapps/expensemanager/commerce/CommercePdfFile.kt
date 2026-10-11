package com.naveenapps.expensemanager.commerce

import java.io.File
import java.io.RandomAccessFile

/** Reads the exact self-contained image-PDF format emitted by ArabicPdfWriter.
 * Xref offsets and stream lengths are used; binary JPEG bytes are never searched for delimiters. */
internal class CommercePdfFile(file:File):AutoCloseable {
    private val input=RandomAccessFile(file,"r")
    private val offsets:List<Long>
    val pageCount:Int
    data class Page(val width:Int,val height:Int,val imageWidth:Int,val imageHeight:Int,val jpeg:ByteArray)
    init {
        try {
            require(input.readLine()=="%PDF-1.4") { "صيغة PDF غير مدعومة للطباعة" }
            input.seek((input.length()-1024).coerceAtLeast(0))
            val tail=ByteArray((input.length()-input.filePointer).toInt()); input.readFully(tail)
            val start=Regex("startxref\\s+([0-9]+)\\s+%%EOF").find(tail.toString(Charsets.ISO_8859_1))?.groupValues?.get(1)?.toLong() ?: error("PDF تالف")
            require(start in 0 until input.length()); input.seek(start); require(input.readLine()=="xref")
            val header=input.readLine().split(' '); require(header[0]=="0")
            val count=header[1].toInt(); require(count in 6..300003 && (count-3)%3==0)
            offsets=(0 until count).map { n -> val parts=input.readLine().trim().split(Regex("\\s+")); val offset=parts[0].toLong(); if(n>0) require(offset in 0 until start && parts[2]=="n"); offset }
            input.seek(offsets[2]); require(input.readLine()=="2 0 obj")
            val declared=Regex("/Count ([0-9]+)").find(input.readLine())?.groupValues?.get(1)?.toInt() ?: error("PDF بلا صفحات")
            require(declared==(count-3)/3); pageCount=declared
        } catch(e:Exception) { input.close(); throw e }
    }
    fun page(n:Int):Page {
        require(n in 0 until pageCount)
        val pageId=3+n*3; input.seek(offsets[pageId]); require(input.readLine()=="$pageId 0 obj")
        val page=input.readLine()
        val size=Regex("/MediaBox \\[0 0 ([0-9]+) ([0-9]+)]").find(page)?.groupValues ?: error("صفحة PDF غير صحيحة")
        val imageId=pageId+1; require(page.contains("/Im0 $imageId 0 R")); input.seek(offsets[imageId]); require(input.readLine()=="$imageId 0 obj")
        val metadata=input.readLine(); require(metadata.contains("/DCTDecode"))
        fun number(key:String)=Regex("/$key ([0-9]+)").find(metadata)?.groupValues?.get(1)?.toInt() ?: error("صورة PDF غير صحيحة")
        val length=number("Length"); require(length in 1..CommerceBackup.MAX_BYTES && length.toLong()<input.length())
        require(input.readLine()=="stream"); val jpeg=ByteArray(length); input.readFully(jpeg)
        require(jpeg.size>=2 && jpeg[0]==(-1).toByte() && jpeg[1]==(-40).toByte()) { "صورة PDF تالفة" }
        return Page(size[1].toInt(),size[2].toInt(),number("Width"),number("Height"),jpeg)
    }
    override fun close() = input.close()
}
