package kr.local.settlement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import java.io.File
import java.util.UUID

class Documents(private val context: Context) {
    val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val pdfTemp = File(context.cacheDir, "pdf-scratch").apply { mkdirs() }
    private val maxImport = 2_000 * MB
    private fun space(bytes: Long) { require(root.usableSpace > bytes + 32 * MB) { "저장공간 부족 (32MB 여유 필요)" } }
    suspend fun import(uri: Uri, displayName: String? = null): Doc {
        var name = displayName ?: "문서"
        if (displayName == null) context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        name = name.substringAfterLast('/').substringAfterLast('\\').replace('\r', '_').replace('\n', '_')
        val target = File(root, UUID.randomUUID().toString())
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024); var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    copied += n; require(copied <= maxImport) { "파일 2GB 안전 한도 초과" }; space(n.toLong()); out.write(buf, 0, n)
                }
            } } ?: error("파일을 열 수 없습니다.")
            return Doc(UUID.randomUUID().toString(), name, target.path, target.length(), hash(target))
        } catch (e: Exception) { target.delete(); throw e }
    }
    suspend fun folder(uri: Uri): List<Doc> {
        val result = mutableListOf<Doc>(); var visited = 0
        suspend fun walk(dir: DocumentFile, depth: Int) {
            require(depth <= 32) { "폴더 깊이 32 초과" }
            for (item in dir.listFiles()) {
                currentCoroutineContext().ensureActive(); require(++visited <= 10000) { "항목 10,000개 안전 한도 초과" }
                if (item.isDirectory) walk(item, depth + 1) else if (item.isFile) result += import(item.uri, item.name)
            }
        }
        try { walk(DocumentFile.fromTreeUri(context, uri) ?: error("폴더 접근 불가"), 0); return result }
        catch (e: Exception) { result.forEach { File(it.path).delete() }; throw e }
    }
    fun zipPreview(doc: Doc): List<String> = ZipFile(doc.path).use { zip ->
        require(!zip.isEncrypted) { "비밀번호 ZIP은 지원하지 않습니다. 승인된 환경에서 해제하세요." }
        val headers = zip.fileHeaders
        require(headers.size <= 5000) { "ZIP 항목 5,000개 초과" }
        var total = 0L
        val names = HashSet<String>()
        headers.map { h ->
            val checkedTarget = safeZipTarget(File(root, "preview"), h.fileName)
            require(names.add(checkedTarget.path)) { "ZIP 내부 중복 경로" }
            val a = h.externalFileAttributes
            require(a.size < 4 || (a[3].toInt() and 0xF0) != 0xA0) { "ZIP 심볼릭 링크 차단" }
            require(h.compressedSize in 0..File(doc.path).length()) { "ZIP 압축 크기 불일치" }
            require(h.uncompressedSize in 0..500 * MB) { "ZIP 개별 파일 500MB 초과" }
            total += h.uncompressedSize
            require(total <= 1500 * MB) { "ZIP 해제 합계 1.5GB 초과" }
            require(h.uncompressedSize <= maxOf(1L, h.compressedSize) * 200) { "ZIP 압축률 안전 한도 초과" }
            "${h.fileName} (${h.uncompressedSize} bytes)"
        }
    }
    suspend fun extract(doc: Doc, policy: Policy): List<Doc> {
        zipPreview(doc)
        val dir = File(root, "zip-${UUID.randomUUID()}").apply { mkdirs() }
        val result = mutableListOf<Doc>()
        try {
            var total = 0L
            ZipFile(doc.path).use { zip -> for (h in zip.fileHeaders) {
                currentCoroutineContext().ensureActive()
                val target = safeZipTarget(dir, h.fileName)
                if (h.isDirectory) { target.mkdirs(); continue }
                target.parentFile!!.mkdirs(); var count = 0L
                zip.getInputStream(h).use { input -> target.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        count += n; total += n
                        require(count <= 500 * MB && total <= 1500 * MB && count <= h.uncompressedSize) { "ZIP 실제 해제 크기 한도 초과" }
                        space(n.toLong()); out.write(buf, 0, n)
                    }
                } }
                require(count == h.uncompressedSize) { "ZIP 크기 불일치" }
                val approved = policy.approved(target.name)
                result += Doc(UUID.randomUUID().toString(), target.name, target.path, target.length(), hash(target),
                    included = approved, note = if (approved) "ZIP: ${h.fileName}" else "승인 형식 아님", origin = "${doc.name}/${h.fileName}")
            } }
            return result
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    suspend fun split(doc: Doc, limit: Long): List<Doc> {
        require(limit in MB..9 * MB)
        // Cleanup can remove this directory while the Documents instance remains alive.
        check(pdfTemp.isDirectory || pdfTemp.mkdirs()) { "PDF 임시 디렉터리 생성 실패" }
        val dir = File(root, "pdf-${UUID.randomUUID()}").apply { mkdirs() }
        val output = mutableListOf<Doc>()
        try {
            PDDocument.load(File(doc.path), MemoryUsageSetting.setupTempFileOnly().setTempDir(pdfTemp)).use { source ->
                require(!source.isEncrypted) { "암호화 PDF는 별도 처리 필요" }
                require(source.numberOfPages > 0) { "페이지가 없는 PDF" }
                require(source.currentAccessPermission.canExtractContent()) { "PDF 추출 권한 없음" }
                var start = 0; var part = 1
                suspend fun make(end: Int, target: File) {
                    currentCoroutineContext().ensureActive(); space(File(doc.path).length().coerceAtMost(100 * MB))
                    PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(pdfTemp)).use { dest ->
                        for (p in start until end) {
                            currentCoroutineContext().ensureActive()
                            val page = source.getPage(p)
                            dest.importPage(page).resources = page.resources
                        }
                        dest.save(target)
                    }
                }
                while (start < source.numberOfPages) {
                    val candidate = File(dir, "candidate.pdf")
                    make(start + 1, candidate)
                    var end = start + 1
                    val oversized = candidate.length() > limit
                    if (!oversized) {
                        // Every chosen range is serialized and measured, never estimated by page count.
                        var low = start + 2; var high = source.numberOfPages
                        val trial = File(dir, "trial.pdf")
                        while (low <= high) {
                            val mid = low + (high - low) / 2
                            make(mid, trial)
                            if (trial.length() <= limit) { trial.copyTo(candidate, overwrite = true); end = mid; low = mid + 1 }
                            else high = mid - 1
                            trial.delete()
                        }
                    }
                    val name = "${doc.name.substringBeforeLast('.')}_${part.toString().padStart(2, '0')}_${start + 1}-${end}p.pdf"
                    val target = File(dir, name); check(candidate.renameTo(target))
                    require(oversized || target.length() <= limit)
                    output += Doc(UUID.randomUUID().toString(), name, target.path, target.length(), hash(target),
                        included = !oversized, note = if (oversized) "단일 페이지 제한 초과: 별도 처리 필요" else "분할됨: 서명·인증정보 보존 보장 안 됨", origin = doc.name)
                    start = end; part++
                }
            }
            return output
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
}
