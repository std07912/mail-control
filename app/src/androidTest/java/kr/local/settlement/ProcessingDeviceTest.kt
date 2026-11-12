package kr.local.settlement

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ProcessingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unequalPageSizesAreMeasuredAndOversizedPageIsFlagged() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "dummy-${UUID.randomUUID()}.pdf")
        val expected = listOf(900_000, 3_000_000, 100)
        val random = java.util.Random(2026L)
        var parts = emptyList<Doc>()
        try {
            PDDocument().use { document ->
                expected.forEach { bytes ->
                    val page = PDPage(); document.addPage(page)
                    val stream = document.document.createCOSStream()
                    stream.createOutputStream().use { out ->
                        // importPage compresses content. Random printable comments keep the
                        // second page oversized even after reserialization/compression.
                        val line = ByteArray(100)
                        repeat(bytes / line.size) {
                            line[0] = '%'.code.toByte()
                            for (i in 1 until 99) line[i] = (33 + random.nextInt(94)).toByte()
                            line[99] = '\n'.code.toByte()
                            out.write(line)
                        }
                    }
                    page.cosObject.setItem(COSName.CONTENTS, stream)
                }
                document.save(source)
            }
            val originalHash = hash(source)
            val doc = Doc("dummy", "더미_정산.pdf", source.path, source.length(), originalHash)
            parts = Documents(context).split(doc, MB)
            assertEquals(3, parts.size)
            assertTrue(parts[0].included)
            assertFalse(parts[1].included)
            assertTrue(parts[1].note.contains("단일 페이지"))
            assertTrue(parts[2].included)
            assertEquals(originalHash, hash(source))
            parts.filter { it.included }.forEach { assertTrue(File(it.path).length() <= MB) }
            parts.forEach { PDDocument.load(File(it.path)).use { pdf -> assertEquals(1, pdf.numberOfPages) } }
            assertTrue(parts[0].name.endsWith("1-1p.pdf"))
            assertTrue(parts[2].name.endsWith("3-3p.pdf"))
        } finally { source.delete(); parts.forEach { File(it.path).delete() } }
    }
    @Test fun scannedPdfOver10MbSplitsAndPreservesPages() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "scanned-${UUID.randomUUID()}.pdf")
        var parts = emptyList<Doc>()
        try {
            PDDocument().use { pdf ->
                val random = java.util.Random(71)
                repeat(4) {
                    val bitmap = android.graphics.Bitmap.createBitmap(1536, 2048, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        val pixels = IntArray(1536 * 2048) { 0xff000000.toInt() or random.nextInt(0x1000000) }
                        bitmap.setPixels(pixels, 0, 1536, 0, 0, 1536, 2048)
                        val bytes = java.io.ByteArrayOutputStream().also {
                            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it))
                        }.toByteArray()
                        val page = PDPage(); pdf.addPage(page)
                        val image = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(pdf, bytes, "scan")
                        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(pdf, page).use { out ->
                            out.drawImage(image, 0f, 0f, 500f, 700f)
                        }
                    } finally { bitmap.recycle() }
                }
                pdf.save(source)
            }
            assertTrue("Fixture must exceed 10MB", source.length() > 10 * MB)
            val original = hash(source)
            var updates = 0
            parts = Documents(context).split(Doc("scan", "스캔더미.pdf", source.path, source.length(), original), 9 * MB) { updates++ }
            assertTrue(parts.size > 1)
            assertTrue(updates > 0)
            assertTrue(parts.all { it.included && it.size <= 9 * MB })
            var pages = 0
            parts.forEach { part ->
                PDDocument.load(File(part.path)).use { pages += it.numberOfPages }
            }
            assertEquals(4, pages)
            assertEquals(original, hash(source))
        } finally { source.delete(); parts.forEach { File(it.path).delete() } }
    }
    @Test fun normalizedDuplicateZipPathsAreRejected() {
        val zip = File(context.cacheDir, "dummy-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { out ->
                listOf("nested/../same.pdf", "same.pdf").forEach { name ->
                    out.putNextEntry(ZipEntry(name)); out.write("dummy".toByteArray()); out.closeEntry()
                }
            }
            val doc = Doc("zip-duplicate", "더미.zip", zip.path, zip.length(), hash(zip))
            assertThrows(IllegalArgumentException::class.java) { Documents(context).zipPreview(doc) }
        } finally { zip.delete() }
    }
    @Test fun zipTraversalIsRejectedBeforeExtraction() {
        val zip = File(context.cacheDir, "dummy-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("../../escape.pdf")); it.write("dummy".toByteArray()); it.closeEntry()
            }
            val doc = Doc("zip-dummy", "더미.zip", zip.path, zip.length(), hash(zip))
            assertThrows(IllegalArgumentException::class.java) { Documents(context).zipPreview(doc) }
        } finally { zip.delete() }
    }
}
