package kr.local.settlement

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoreTest {
    private fun doc(n: Int, size: Long, name: String = "정산_${n}.pdf") = Doc("$n", name, "/dummy/$n", size, "$n")
    @Test fun handlesMoreThan500FilesWithoutLossAndHonorsBothLimits() {
        val files = (1..550).map { doc(it, 10_000L + it * 137L) }
        val policy = Policy(700_000, 950_000, 700_000)
        for (minimize in listOf(false, true)) {
            val groups = Grouping.pack(files, policy, minimize)
            assertEquals(files.map { it.id }.toSet(), groups.flatten().map { it.id }.toSet())
            assertEquals(files.size, groups.flatten().size)
            groups.forEach { group ->
                assertTrue(group.sumOf { it.size } <= policy.attachmentLimit)
                assertTrue(estimatedMessage(group) <= policy.messageLimit)
            }
            if (!minimize) assertEquals(files, groups.flatten())
        }
    }
    @Test fun ignoresExcludedAndKeepsFilesWhole() {
        val files = listOf(doc(1, 4 * MB), doc(2, 4 * MB), doc(3, MB).copy(included = false))
        val groups = Grouping.pack(files, Policy(), false)
        assertEquals(1, groups.size); assertEquals(files.take(2), groups.single())
    }
    @Test fun encodedMessageLimitCanRequireAdditionalMail() {
        val files = listOf(doc(1, 4 * MB), doc(2, 4 * MB))
        assertEquals(2, Grouping.pack(files, Policy(messageLimit = 6 * MB), false).size)
    }
    @Test fun refusesOversizedSingleFileRatherThanByteSplitting() {
        assertThrows(IllegalArgumentException::class.java) { Grouping.pack(listOf(doc(1, 10 * MB)), Policy(), false) }
    }
    @Test fun refusesUnapprovedFileType() {
        assertNotNull(Policy().problem(doc(1, 100, "실행파일.exe")))
        assertNull(Policy().problem(doc(1, 100, "한글 문서(확인).HWPX")))
    }
    @Test fun firstFitDecreasingCanReduceMailCount() {
        val files = listOf(6, 6, 3, 3).mapIndexed { i, mb -> doc(i, mb * MB) }
        val p = Policy(messageLimit = 20 * MB)
        assertEquals(3, Grouping.pack(files, p, false).size)
        assertEquals(2, Grouping.pack(files, p, true).size)
    }
    @Test fun blocksZipTraversalAndWindowsPaths() {
        val root = File(System.getProperty("java.io.tmpdir"), "settlement-zip-test")
        listOf("../escape.pdf", "/absolute.pdf", "folder/../../escape", "C:/evil.pdf", "..\\evil.pdf", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { safeZipTarget(root, it) }
        }
        assertEquals(File(root, "하위/정산.pdf").canonicalFile, safeZipTarget(root, "하위/정산.pdf"))
    }
    @Test fun checksumDetectsEqualContentWithDifferentNames() {
        val a = File.createTempFile("dummy-a", ".pdf"); val b = File.createTempFile("dummy-b", ".hwp")
        try { a.writeText("더미 문서"); b.writeBytes(a.readBytes()); assertEquals(hash(a), hash(b)); b.appendText("변경"); assertNotEquals(hash(a), hash(b)) }
        finally { a.delete(); b.delete() }
    }
    @Test fun connectionLossAndCancellationAfterDataRemainUncertain() {
        assertEquals("UNCERTAIN", deliveryFailureState(true, false, false))
        assertEquals("UNCERTAIN", deliveryFailureState(true, false, true))
        assertEquals("FAILED", deliveryFailureState(true, true, false))
        assertEquals("CANCELLED", deliveryFailureState(false, false, true))
        assertEquals("FAILED", deliveryFailureState(false, false, false))
    }
    @Test fun csvEscapesQuotesAndPreventsFormulaInjection() {
        assertEquals("\"' =SUM(A1)\"", csvCell(" =SUM(A1)"))
        assertEquals("\"'@test\"", csvCell("@test"))
        assertEquals("\"한글,\"\"문서\"\"\"", csvCell("한글,\"문서\""))
    }
}
