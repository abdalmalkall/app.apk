package com.naveenapps.expensemanager.commerce
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34],qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommerceRegressionUiTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 @Test fun safetyPanelInvokesRealSelectedFilenameCallbacks() {
  val filename="before-restore-123-example.json"; var downloaded=""; var restored=""
  compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SettingsPage(Snapshot(settings=mapOf("currency" to "JOD")),false,{},{},{},{},{},listOf(filename),{ restored=it },{ downloaded=it }) } } }
  compose.onNodeWithText("تنزيل نسخة الأمان").performScrollTo().performClick(); assertEquals(filename,downloaded)
  compose.onNodeWithText("استعادة نسخة الأمان").performScrollTo().performClick(); assertEquals(filename,restored)
 }
 @Test fun firstBarcodeDownloadCreatesDocumentAndLaunchesPicker() {
  val context:Context=ApplicationProvider.getApplicationContext(); val store=CommerceRegistry.get(context)
  compose.setContent { CommerceApp() }
  compose.waitUntil(15000) { compose.onAllNodesWithText("نظرة واضحة على أعمالك").fetchSemanticsNodes().isNotEmpty() }
  compose.onNodeWithContentDescription("القائمة").performClick(); compose.onAllNodesWithText("المنتجات").filter(hasClickAction()).onFirst().performClick()
  compose.onNodeWithText("رمل 1").performClick()
  compose.onNodeWithText("تنزيل PDF").performScrollTo().performClick()
  compose.waitUntil(15000) { store.snapshot().products.first {it.id=="sand-1"}.barcode=="SAND-1" }
  compose.waitForIdle()
  compose.waitUntil(15000) { File(context.cacheDir,"commerce-documents").listFiles().orEmpty().any { it.name.startsWith("barcode-sand-1-") && it.name.endsWith(".pdf") } }; compose.waitUntil(15000) { Shadows.shadowOf(compose.activity).nextStartedActivity != null }
  assertTrue(File(context.cacheDir,"commerce-documents").listFiles().orEmpty().any { it.name.startsWith("barcode-sand-1-") && it.name.endsWith(".pdf") })
 }
}
