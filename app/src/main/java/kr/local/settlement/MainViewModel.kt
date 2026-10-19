package kr.local.settlement

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val recipient: String = "", val title: String = "정산자료", val job: String = "사후정산",
    val attachMB: String = "9", val fileMB: String = "9", val messageMB: String = "13", val pdfMB: String = "9",
    val interval: String = "60", val approved: String = "pdf,xls,xlsx,doc,docx,hwp,hwpx,jpg,jpeg,png"
) {
    fun policy(): Policy {
        fun bytes(s: String): Long = (s.toLongOrNull() ?: error("용량은 정수로 입력하세요.")).also { require(it in 1..500) { "용량은 1~500MB" } } * MB
        val ext = approved.split(',').map { it.trim().lowercase().removePrefix(".") }.filter { it.isNotBlank() }.toSet()
        require(ext.isNotEmpty() && ext.all { it.matches(Regex("[a-z0-9]{1,12}")) }) { "승인 확장자 설정 확인" }
        return Policy(bytes(attachMB), bytes(messageMB), bytes(fileMB), ext)
    }
    fun seconds(): Long = (interval.toLongOrNull() ?: error("발송 간격은 정수로 입력하세요.")).also { require(it in 30..86400) { "간격은 30~86400초" } }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    private val dao = (app as SettlementApp).db.mails()
    private val documents = Documents(app)
    private val prefs = app.getSharedPreferences("settings", 0)
    private val selection = File(app.filesDir, "selection.json")
    private val selectionStore = AtomicFile(selection)
    private val _docs = MutableStateFlow<List<Doc>>(emptyList()); val docs = _docs.asStateFlow()
    private val _busy = MutableStateFlow(false); val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow(""); val message = _message.asStateFlow()
    private val _draft = MutableStateFlow<List<MailRow>>(emptyList()); val draft = _draft.asStateFlow()
    val history = dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var settings = MutableStateFlow(Settings(
        prefs.getString("recipient", "")!!, prefs.getString("title", "정산자료")!!, prefs.getString("job", "사후정산")!!,
        prefs.getString("attachMB", "9")!!, prefs.getString("fileMB", "9")!!, prefs.getString("messageMB", "13")!!,
        prefs.getString("pdfMB", "9")!!, prefs.getString("interval", "60")!!,
        prefs.getString("approved", Settings().approved)!!
    )); private set
    private val _accountEmail = MutableStateFlow("")
    val accountEmail = _accountEmail.asStateFlow()
    private val _connectionOk = MutableStateFlow(false)
    val connectionOk = _connectionOk.asStateFlow()
    private var task: Job? = null
    init { runTask("선택 목록 복원") {
        _accountEmail.value = SecretStore(context).read()?.first.orEmpty()
        if (selection.exists() || File(selection.path + ".bak").exists()) {
            val j = JSONArray(selectionStore.openRead().bufferedReader().use { it.readText() })
            _docs.value = parseDocs(j.toString()).mapIndexed { i, d ->
                d.copy(included = j.getJSONObject(i).optBoolean("included", true), note = j.getJSONObject(i).optString("note"))
            }.filter { File(it.path).exists() }
        }
        // Outbox files without a committed history record are abandoned drafts only.
        val tracked = dao.all().map { it.batch }.toSet()
        File(context.filesDir, "outbox").listFiles()?.filter { it.isDirectory && it.name !in tracked }?.forEach { it.deleteRecursively() }
    } }
    fun runTask(label: String, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true; _message.value = label
        task = viewModelScope.launch(Dispatchers.IO) {
            try { action(); if (_message.value == label) _message.value = "$label 완료" }
            catch (e: CancellationException) { _message.value = "처리를 취소했습니다."; throw e }
            catch (e: Exception) { _message.value = e.message?.take(500) ?: "${e.javaClass.simpleName}: 처리 실패" }
            finally { _busy.value = false }
        }
    }
    fun cancelProcessing() { task?.cancel() }
    private fun persistSelection(snapshot: List<Doc> = _docs.value) {
        val array = JSONArray(docsJson(snapshot))
        snapshot.forEachIndexed { index, d -> array.getJSONObject(index).put("included", d.included).put("note", d.note) }
        val stream = selectionStore.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            selectionStore.finishWrite(stream)
        } catch (e: Exception) { selectionStore.failWrite(stream); throw e }
    }
    private fun invalidateDraft() { _draft.value.forEach { File(it.eml).delete() }; _draft.value = emptyList() }
    private fun add(incoming: List<Doc>, sourceId: String? = null, sourceNote: String = "") {
        var base = _docs.value.map { if (it.id == sourceId) it.copy(included = false, note = sourceNote) else it }
        val hashes = base.map { it.sha256 }.toMutableSet()
        val duplicateFiles = mutableListOf<File>()
        val unique = incoming.filter {
            if (hashes.add(it.sha256)) true else {
                duplicateFiles += File(it.path)
                // If rewriting a PDF leaves identical bytes, keep the retained copy usable.
                if (it.included && sourceId != null) base = base.map { old ->
                    if (old.id == sourceId && old.sha256 == it.sha256) old.copy(included = true, note = "동일 내용: 원본 유지") else old
                }
                false
            }
        }
        val next = base + unique
        try { persistSelection(next) }
        catch (e: Exception) { incoming.forEach { File(it.path).delete() }; throw e }
        _docs.value = next
        duplicateFiles.forEach { it.delete() }
        invalidateDraft()
        _message.value = "${unique.size}개 추가 · 중복 ${duplicateFiles.size}개 제외"
    }
    fun import(uris: List<Uri>) = runTask("파일 가져오기") {
        val gathered = mutableListOf<Doc>()
        try { for ((i, uri) in uris.withIndex()) {
            _message.value = "파일 가져오기 ${i + 1}/${uris.size}"; gathered += documents.import(uri)
        }; add(gathered) } catch (e: Exception) { gathered.forEach { File(it.path).delete() }; throw e }
    }
    fun folder(uri: Uri) = runTask("폴더 가져오기") { add(documents.folder(uri)) }
    fun toggle(id: String) = runTask("선택 변경") {
        _docs.value = _docs.value.map { if (it.id == id) it.copy(included = !it.included) else it }; invalidateDraft(); persistSelection()
    }
    fun excludeUnapprovedSelection() = runTask("미승인 형식 선택 해제") {
        val next = excludeUnapproved(_docs.value, settings.value.policy())
        val count = _docs.value.count { it.included } - next.count { it.included }
        if (count > 0) {
            persistSelection(next)
            _docs.value = next
            invalidateDraft()
        }
        _message.value = "미승인 형식 ${count}개를 선택 해제했습니다. 파일은 보존됩니다."
    }
    fun move(id: String, direction: Int) = runTask("순서 변경") {
        val list = _docs.value.toMutableList(); val old = list.indexOfFirst { it.id == id }; val next = old + direction
        if (old >= 0 && next in list.indices) { val d = list.removeAt(old); list.add(next, d); _docs.value = list; invalidateDraft(); persistSelection() }
    }
    fun sort() = runTask("파일명 정렬") { _docs.value = _docs.value.sortedBy { it.name }; invalidateDraft(); persistSelection() }
    suspend fun zipPreview(doc: Doc): List<String> = withContext(Dispatchers.IO) { documents.zipPreview(doc) }
    fun extract(doc: Doc) = runTask("ZIP 압축 해제") {
        val extracted = documents.extract(doc, settings.value.policy())
        add(extracted, sourceId = doc.id, sourceNote = "해제 원본 보존")
    }
    fun split(doc: Doc) = runTask("PDF 분할: 실제 크기 확인 중") {
        val size = settings.value.pdfMB.toLongOrNull() ?: error("PDF 목표 크기를 입력하세요.")
        require(size in 1..9) { "PDF 목표 크기는 1~9MB" }
        if (doc.size <= size * MB) {
            _message.value = "이미 목표 용량 이하입니다. 분할 없이 원본을 유지합니다."
            return@runTask
        }
        val parts = documents.split(doc, size * MB)
        // A one-page rewrite can equal the original hash; keep its required-action status
        // on the retained original even when duplicate output is discarded.
        val sourceNote = if (parts.size == 1 && !parts.single().included) parts.single().note else "분할 원본 보존"
        add(parts, sourceId = doc.id, sourceNote = sourceNote)
    }
    fun updateSettings(value: Settings) {
        if (_busy.value) return
        settings.value = value
        _draft.value.forEach { File(it.eml).delete() }; _draft.value = emptyList()
        prefs.edit().putString("recipient", value.recipient).putString("title", value.title).putString("job", value.job)
            .putString("attachMB", value.attachMB).putString("fileMB", value.fileMB).putString("messageMB", value.messageMB)
            .putString("pdfMB", value.pdfMB).putString("interval", value.interval).putString("approved", value.approved).apply()
    }
    fun saveAccount(user: String, password: String) = runTask("계정 저장") {
        val address = Mailer.address(user.trim()).address
        require(address.substringAfter('@').equals("naver.com", true)) { "개인 네이버 @naver.com 계정을 사용하세요." }
        require(password.isNotBlank()) { "애플리케이션 비밀번호를 입력하세요." }
        SecretStore(context).save(address, password); invalidateDraft()
        _accountEmail.value = address; _connectionOk.value = false
        _message.value = "Keystore 기반 암호화 저장 완료. SMTP 연결 테스트를 진행하세요."
    }
    fun testAccount() = runTask("SMTP 연결 테스트") {
        _connectionOk.value = false
        _message.value = Mailer.test(context)
        _connectionOk.value = true
    }
    fun prepare(minimize: Boolean) = runTask("메일 구성 및 실제 MIME 크기 검증") {
        invalidateDraft(); val s = settings.value
        _draft.value = Mailer.prepare(context, docs.value, s.policy(), minimize, s.recipient.trim(), s.title, s.job, s.seconds())
    }
    fun approve() = runTask("승인된 작업 등록") {
        val rows = _draft.value; require(rows.isNotEmpty())
        dao.insert(rows); _draft.value = emptyList(); Mailer.enqueue(context, rows.first().batch)
        _message.value = "${rows.size}개 메일 발송 작업이 등록되었습니다."
    }
    fun cancelBatch(batch: String) {
        Mailer.cancel(batch); WorkManager.getInstance(context).cancelAllWorkByTag(batch)
        viewModelScope.launch(Dispatchers.IO) { dao.cancelPending(batch) }
    }
    fun retry(row: MailRow) = runTask("실패/취소 메일 재시도 등록") {
        require(dao.batch(row.batch).none { it.state in listOf("UNCERTAIN", "SENDING", "CONNECTING") }) { "발송 중이거나 접수 여부가 불확실한 메일을 먼저 확인하세요." }
        if (dao.retry(row.id) == 1) Mailer.enqueue(context, row.batch)
    }
    fun resolve(row: MailRow, accepted: Boolean) = runTask("서버 접수 확인 반영") {
        require(dao.get(row.id)?.state == "UNCERTAIN")
        dao.result(row.id, if (accepted) "CONFIRMED" else "FAILED", System.currentTimeMillis(),
            error = if (accepted) "사용자가 서버 접수를 별도 확인함" else "사용자가 미접수를 별도 확인함. 수동 재시도 가능")
    }
    fun recover() = runTask("중단 상태 확인") {
        val active = WorkManager.getInstance(context).getWorkInfosByTag("smtp-all").get().any { !it.state.isFinished }
        require(!active) { "실행 또는 대기 중인 발송 작업이 있습니다. 먼저 취소하세요." }
        dao.recoverConnections(); dao.recoverSending()
    }
    fun export(uri: Uri) = runTask("CSV 내보내기") {
        val rows = dao.all()
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { out ->
            out.write("\uFEFF작업명,파일명,원본경로,파일크기,수신자,제목,발송시각,상태,SMTP접수응답,오류\r\n")
            rows.forEach { r -> parseDocs(r.attachments).forEach { d ->
                out.write(listOf(r.job, d.name, d.origin, d.size.toString(), r.recipient, r.subject,
                    if (r.time == 0L) "" else java.time.Instant.ofEpochMilli(r.time).toString(), r.state, r.response, r.error).joinToString(",") { csvCell(it) } + "\r\n")
            } }
        } ?: error("CSV 출력 파일을 열 수 없습니다.")
    }
    fun clearTemporary() = runTask("선택 및 임시파일 삭제") {
        val rows = dao.all()
        require(rows.none { it.state in listOf("PENDING", "CONNECTING", "SENDING", "UNCERTAIN", "FAILED", "CANCELLED") }) {
            "미완료·재시도 가능 작업이 있어 파일을 보존합니다. MVP는 이력별 본문 삭제를 지원하지 않습니다."
        }
        invalidateDraft(); _docs.value = emptyList(); persistSelection()
        documents.root.deleteRecursively(); documents.root.mkdirs()
        File(context.cacheDir, "pdf-scratch").deleteRecursively()
        File(context.filesDir, "outbox").deleteRecursively()
    }
}
