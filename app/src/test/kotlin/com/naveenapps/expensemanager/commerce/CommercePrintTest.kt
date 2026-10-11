package com.naveenapps.expensemanager.commerce
import android.app.Application
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,manifest=Config.NONE,sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommercePrintTest {
 @Test fun requestedRangesAreClippedDeduplicatedAndSorted() {
  assertEquals(listOf(1,2,3),CommercePrintAdapter.indices(5,arrayOf(PageRange(2,3),PageRange(1,2))))
  assertEquals(listOf(0,1),CommercePrintAdapter.indices(2,arrayOf(PageRange.ALL_PAGES)))
  assertEquals(emptyList<Int>(),CommercePrintAdapter.indices(2,arrayOf(PageRange(5,6))))
 }
 @Test fun pdfSubsetContainsOnlyTheRequestedPage() {
  val context:Context=ApplicationProvider.getApplicationContext()
  val source=CommerceDocuments.pdf(context,"print-source",List(150) { "سطر عربي $it" })
  val output=File(source.parentFile,"print-subset.pdf")
  CommercePdfFile(source).use { assertTrue(it.pageCount>1) }
  assertEquals(listOf(1),CommercePrintAdapter.selectPages(output,source,arrayOf(PageRange(1,1)),CancellationSignal()))
  CommercePdfFile(output).use { subset ->
   assertEquals(1,subset.pageCount)
   CommercePdfFile(source).use { original -> assertArrayEquals(original.page(1).jpeg,subset.page(0).jpeg) }
  }
  val dir=File(System.getProperty("user.dir"),"build/commerce-verification").apply { mkdirs() }
  output.copyTo(File(dir,"selected-print-page.pdf"),overwrite=true)
 }
}
