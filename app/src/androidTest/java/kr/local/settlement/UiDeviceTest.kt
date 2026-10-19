package kr.local.settlement

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun bulkDeselectPreservesFilesAndCapturesRedesignedScreens() {
        val context = ui.activity
        val vm = ViewModelProvider(ui.activity)[MainViewModel::class.java]
        ui.waitUntil(15000) { !vm.busy.value }
        val fixture = File(context.filesDir, "documents/ui-${UUID.randomUUID()}").apply { mkdirs() }
        val first = File(fixture, "정산증빙.pdf")
        val second = File(fixture, "재료비명세.pdf")
        for (f in listOf(first, second)) PDDocument().use { pdf -> pdf.addPage(PDPage()); pdf.save(f) }
        // Make the PDFs different by adding an extra page to the second one.
        PDDocument.load(second).use { pdf -> pdf.addPage(PDPage()); pdf.save(File(fixture, "temp.pdf")) }
        File(fixture, "temp.pdf").copyTo(second, overwrite = true); File(fixture, "temp.pdf").delete()
        val third = File(fixture, "작업사진.png").apply {
            writeBytes(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9S0AAAAASUVORK5CYII=", android.util.Base64.DEFAULT))
        }
        val blocked = File(fixture, "첨부안내.txt").apply { writeText("실제 업무자료가 아닌 UI 테스트용 더미입니다.") }
        val originals = listOf(first, second, third, blocked)
        val hashes = originals.associate { it.path to hash(it) }
        val uris = originals.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        ui.runOnIdle { vm.import(uris) }
        ui.waitUntil(20000) { !vm.busy.value && vm.docs.value.size >= 4 }
        ui.onNodeWithText("파일").performClick()
        ui.onNodeWithText("미승인 형식 선택 해제 (1)").performScrollTo().performClick()
        ui.waitUntil(15000) { !vm.busy.value && vm.docs.value.none { it.name.endsWith(".txt") && it.included } }
        assertEquals(3, vm.docs.value.count { it.included })
        assertEquals(4, vm.docs.value.size)
        vm.docs.value.forEach { assertTrue(File(it.path).exists()) }
        originals.forEach { assertEquals(hashes[it.path], hash(it)) }
        ui.onNodeWithText("파일 선택").performScrollTo()
        screenshot("01-files")
        ui.onNodeWithText("처리").performClick(); screenshot("02-processing")
        ui.onNodeWithText("구성").performClick(); screenshot("03-compose")
        ui.onNodeWithText("발송").performClick(); screenshot("04-sending")
        ui.onNodeWithText("설정").performClick(); screenshot("05-settings")
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val dir = File(ui.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
