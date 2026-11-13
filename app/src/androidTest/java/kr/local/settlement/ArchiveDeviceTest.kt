package kr.local.settlement

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.cacheDir,"zip-test-${UUID.randomUUID()}").apply { mkdirs() }
    private fun fixture(entries: Map<String,ByteArray>, charset: Charset = Charsets.UTF_8): Doc {
        val file = File(folder,"원본-${UUID.randomUUID()}.zip")
        ZipOutputStream(file.outputStream(),charset).use { zip ->
            entries.forEach { (name,data) ->
                zip.putNextEntry(ZipParameters().apply { fileNameInZip=name })
                zip.write(data);zip.closeEntry()
            }
        }
        return Doc(UUID.randomUUID().toString(),file.name,file.path,file.length(),hash(file))
    }
    @Test fun utf8AndCp949KoreanNamesSurvivePreviewAndExtraction() = runBlocking {
        try {
            for (charset in listOf(Charsets.UTF_8,Charset.forName("MS949"))) {
                val name="하위 폴더/정산증빙(확인)_①.xlsx"
                val doc=fixture(mapOf(name to byteArrayOf(1,2,3,4)),charset)
                val service=Documents(context)
                assertTrue(service.zipPreview(doc).single().startsWith(name))
                val result=service.extract(doc,Policy())
                try {
                    assertEquals("정산증빙(확인)_①.xlsx",result.single().name)
                    assertTrue(result.single().path.endsWith(name))
                    assertArrayEquals(byteArrayOf(1,2,3,4),File(result.single().path).readBytes())
                    assertEquals(doc.sha256,hash(File(doc.path)))
                } finally { result.forEach { File(it.path).delete() } }
                assertTrue(service.zipPreview(doc,"CP949").single().startsWith(name)) // UTF-8 flags still win.
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun independentZipsRespectSizeAndKeepApprovedEntriesAndHashes() = runBlocking {
        val random=java.util.Random(53)
        val expected=(1..4).associate { "하위/증빙_${it}.pdf" to ByteArray(800000).also(random::nextBytes) }
        val doc=fixture(expected + ("제외.exe" to byteArrayOf(1,2,3)))
        var outputs=emptyList<Doc>()
        try {
            outputs=ArchivePacker(context).repack(doc,MB,false,Policy())
            assertEquals(4,outputs.size)
            val actual=linkedMapOf<String,ByteArray>()
            outputs.forEach { part ->
                assertTrue(part.size <= MB)
                ZipFile(part.path).use { zip ->
                    assertFalse(zip.isSplitArchive)
                    zip.fileHeaders.forEach { entry -> actual[entry.fileName]=zip.getInputStream(entry).use { it.readBytes() } }
                }
            }
            assertEquals(expected.keys,actual.keys)
            expected.forEach { (name,data) -> assertArrayEquals(data,actual[name]) }
            assertEquals(doc.sha256,hash(File(doc.path)))
        } finally { outputs.forEach { File(it.path).delete() };folder.deleteRecursively() }
    }
    @Test fun splitZipReassemblesLargeSingleFileWithoutRenamingContents() = runBlocking {
        val bytes=ByteArray(2400000).also { java.util.Random(31).nextBytes(it) }
        val name="자료/대용량증빙.hwp"
        val doc=fixture(mapOf(name to bytes))
        var outputs=emptyList<Doc>()
        try {
            outputs=ArchivePacker(context).repack(doc,MB,true,Policy())
            assertTrue(outputs.size >= 3)
            assertTrue(outputs.all { it.size <= MB })
            assertEquals("zip",File(outputs.last().path).extension)
            ZipFile(outputs.last().path).use { zip ->
                assertTrue(zip.isSplitArchive)
                assertEquals(name,zip.fileHeaders.single().fileName)
                assertArrayEquals(bytes,zip.getInputStream(zip.fileHeaders.single()).use { it.readBytes() })
            }
            assertEquals(doc.sha256,hash(File(doc.path)))
            try { ArchivePacker(context).repack(doc,MB,false,Policy());fail("Independent ZIP must refuse an oversized whole file") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("분할 ZIP")) }
        } finally { outputs.forEach { File(it.path).delete() };folder.deleteRecursively() }
    }
    @Test fun cancellingCompressionRemovesPartialOutputsAndPreservesSource() = runBlocking {
        val doc=fixture(mapOf("더미.pdf" to ByteArray(2200000).also { java.util.Random(9).nextBytes(it) }))
        val root=Documents(context).root
        val before=root.listFiles().orEmpty().filter { it.name.startsWith("packed-") }.map { it.name }.toSet()
        val job=Job()
        try {
            try {
                withContext(job) { ArchivePacker(context).repack(doc,MB,true,Policy()) { job.cancel() } }
                fail("Cancellation expected")
            } catch (_: CancellationException) { }
            assertEquals(before,root.listFiles().orEmpty().filter { it.name.startsWith("packed-") }.map { it.name }.toSet())
            assertEquals(doc.sha256,hash(File(doc.path)))
        } finally { folder.deleteRecursively() }
    }
}
