package kr.local.settlement

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.io.outputstream.SplitOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File
import java.util.UUID

/** Repack approved entries as real UTF-8 ZIP archives. Never change original files. */
class ArchivePacker(private val context: Context) {
    suspend fun repack(source: Doc, limit: Long, split: Boolean, policy: Policy,
                       encoding: String = "AUTO", progress: (String) -> Unit = {}): List<Doc> {
        require(limit in MB..500 * MB) { "ZIP 목표 용량은 1~500MB입니다." }
        val service = Documents(context)
        val extracted = service.extract(source, policy, encoding)
        val root = service.root
        val extractionRoot = extracted.firstOrNull()?.let { File(root, File(it.path).relativeTo(root).path.substringBefore(File.separator)) }
        val dir = File(root,"packed-${UUID.randomUUID()}")
        try {
            check(dir.mkdirs()) { "압축 출력 폴더 생성 실패" }
            val files = extracted.filter { it.included }
            require(files.isNotEmpty()) { "압축할 승인 문서가 없습니다. 승인 형식을 확인하세요." }
            require(root.usableSpace > files.sumOf { it.size } + 32 * MB) { "압축 출력용 저장공간이 부족합니다." }
            val prefix = source.name.substringBeforeLast('.').take(50).ifBlank { "자료" }
            suspend fun write(entries: List<Doc>, target: File, splitLength: Long? = null) {
                currentCoroutineContext().ensureActive()
                val output = if (splitLength == null) target.outputStream() else SplitOutputStream(target,splitLength)
                ZipOutputStream(output, Charsets.UTF_8).use { zip ->
                    for ((index, file) in entries.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        progress("ZIP 압축 ${index+1}/${entries.size}: ${file.name}")
                        check(hash(File(file.path)) == file.sha256) { "압축 전 파일이 변경되었습니다." }
                        val name = file.origin.removePrefix(source.name + "/")
                        safeZipTarget(dir, name)
                        val parameters = ZipParameters().apply {
                            fileNameInZip = name
                            compressionMethod = CompressionMethod.DEFLATE
                        }
                        zip.putNextEntry(parameters)
                        File(file.path).inputStream().buffered().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                require(root.usableSpace > buffer.size + 32 * MB) { "압축 중 저장공간 부족" }
                                zip.write(buffer,0,count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            if (split) {
                write(files, File(dir,"${prefix}_분할.zip"), limit)
            } else {
                var start = 0; var part = 1
                while (start < files.size) {
                    val candidate = File(dir,"candidate.zip")
                    write(listOf(files[start]),candidate)
                    require(candidate.length() <= limit) {
                        "${files[start].name}: 단독 ZIP도 목표 크기를 넘습니다. 파일 원형을 유지하려면 분할 ZIP 방식을 선택하세요."
                    }
                    var end = start + 1; var low = end + 1; var high = files.size
                    val trial = File(dir,"trial.zip")
                    while (low <= high) {
                        val middle = low + (high-low)/2
                        write(files.subList(start,middle),trial)
                        if (trial.length() <= limit) { trial.copyTo(candidate,true);end=middle;low=middle+1 }
                        else high=middle-1
                        trial.delete()
                    }
                    check(candidate.renameTo(File(dir,"${prefix}_${part.toString().padStart(2,'0')}.zip")))
                    start=end;part++
                }
            }
            val outputs = dir.listFiles().orEmpty().filter { it.isFile }.sortedWith(compareBy<File> {
                if (it.extension == "zip") Int.MAX_VALUE else it.extension.removePrefix("z").toIntOrNull() ?: Int.MAX_VALUE-1
            }.thenBy { it.name })
            require(outputs.isNotEmpty() && outputs.all { it.length() in 1..limit }) { "생성 ZIP 실제 크기가 목표를 넘습니다. 더 작은 목표로 다시 시도하세요." }
            return outputs.map { file ->
                Doc(UUID.randomUUID().toString(),file.name,file.path,file.length(),hash(file),
                    note=if(split) "분할 ZIP: 모든 조각을 같은 폴더에 모은 뒤 마지막 .zip 해제" else "독립 ZIP: 이 파일만으로 해제 가능",
                    origin=source.name)
            }
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
        finally { extractionRoot?.deleteRecursively() }
    }
}
