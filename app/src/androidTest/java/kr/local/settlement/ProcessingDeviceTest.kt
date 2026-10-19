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
        val expected = listOf(700_000, 1_400_000, 100)
        var parts = emptyList<Doc>()
        try {
            PDDocument().use { document ->
                expected.forEach { bytes ->
                    val page = PDPage(); document.addPage(page)
                    val stream = document.document.createCOSStream()
                    stream.createOutputStream().use { out ->
                        // Valid PDF comment content, uncompressed so sizes are intentionally uneven.
                        val line = ("%" + "x".repeat(98) + "\n").toByteArray()
                        repeat(bytes / line.size) { out.write(line) }
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
