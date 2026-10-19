package kr.local.settlement

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

const val MB = 1_000_000L

data class Doc(val id: String, val name: String, val path: String, val size: Long, val sha256: String,
    val included: Boolean = true, val note: String = "", val origin: String = name)

data class Policy(val attachmentLimit: Long = 9 * MB, val messageLimit: Long = 13 * MB,
    val fileLimit: Long = 9 * MB,
    val extensions: Set<String> = setOf("pdf", "xls", "xlsx", "doc", "docx", "hwp", "hwpx", "jpg", "jpeg", "png")) {
    init { require(attachmentLimit > 0 && messageLimit > 0 && fileLimit > 0) }
    fun approved(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
    fun problem(doc: Doc): String? = when {
        !approved(doc.name) -> "승인 형식 아님"
        doc.size > fileLimit -> "개별 파일 제한 초과"
        doc.size > attachmentLimit -> "메일 첨부 제한 초과"
        estimatedMessage(listOf(doc)) > messageLimit -> "MIME 메시지 제한 초과"
        else -> null
    }
}

// Base64's 76-column line wrapping, UTF-8 filename headers, and fixed multipart headers.
// Actual saved MIME bytes are checked again before approval and before sending.
fun estimatedMessage(files: List<Doc>): Long = 8192L + files.sumOf {
    val chars = ((it.size + 2) / 3) * 4
    chars + ((chars + 75) / 76) * 2 + 2048 + it.name.toByteArray().size * 8L
}

object Grouping {
    fun pack(files: List<Doc>, policy: Policy, minimize: Boolean): List<List<Doc>> {
        val selected = files.filter { it.included }
        require(selected.all { policy.problem(it) == null }) { "전송 불가 파일을 먼저 처리하거나 제외하세요." }
        val ordered = if (minimize) selected.sortedByDescending { it.size } else selected
        val groups = mutableListOf<MutableList<Doc>>()
        fun fits(group: List<Doc>, doc: Doc): Boolean {
            val next = group + doc
            return next.sumOf { it.size } <= policy.attachmentLimit && estimatedMessage(next) <= policy.messageLimit
        }
        for (file in ordered) {
            val target = if (minimize) groups.firstOrNull { fits(it, file) }
                else groups.lastOrNull()?.takeIf { fits(it, file) }
            if (target != null) target += file else groups += mutableListOf(file)
        }
        return groups
    }
}

fun hash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun safeZipTarget(root: File, entry: String): File {
    require(entry.isNotBlank() && !entry.contains('\\') && !entry.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(entry)) { "잘못된 ZIP 경로" }
    val target = File(root, entry).canonicalFile
    require(target.path.startsWith(root.canonicalPath + File.separator)) { "ZIP 경로 탈출 차단" }
    return target
}

class CountingSink : OutputStream() {
    var count = 0L; private set
    override fun write(b: Int) { count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { count += len }
}

fun csvCell(value: String): String {
    // Spreadsheet formula injection prevention, including leading whitespace.
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}

// Once DATA may have reached SMTP, transport loss must never imply safe automatic retry.
fun deliveryFailureState(sending: Boolean, explicitlyRejected: Boolean, cancelled: Boolean): String =
    if (sending && !explicitlyRejected) "UNCERTAIN" else if (cancelled) "CANCELLED" else "FAILED"
