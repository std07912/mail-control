package kr.local.settlement

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF006A60))) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}
fun sizeText(bytes: Long): String = String.format(Locale.KOREA, "%.2f MB", bytes.toDouble() / MB)
fun stateText(state: String) = when (state) {
    "PENDING" -> "대기"; "CONNECTING" -> "연결 중"; "SENDING" -> "전송 중"; "ACCEPTED" -> "SMTP 접수 성공"
    "CONFIRMED" -> "사용자 접수 확인"; "FAILED" -> "실패"; "UNCERTAIN" -> "접수 여부 불확실 · 확인 필요"
    "CANCELLED" -> "취소"; else -> state
}
@Composable private fun Field(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled, singleLine = true)
}
@Composable private fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(4) }
    var minimize by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf(false) }
    var pdfWarning by remember { mutableStateOf<Doc?>(null) }
    var zipDoc by remember { mutableStateOf<Doc?>(null) }
    var zipItems by remember { mutableStateOf<List<String>>(emptyList()) }
    var zipError by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf<MailRow?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::folder) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::export) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val policy = runCatching { settings.policy() }.getOrNull()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("정산자료 전송 도우미", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1 파일 선택", "2 파일 처리", "3 메일 구성", "4 자동발송", "5 설정·이력").forEachIndexed { i, title ->
                FilterChip(tab == i, { tab = i }, label = { Text(title) })
            }
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(vm::cancelProcessing) { Text("현재 파일/설정 처리 취소") } }
        when (tab) {
            0, 1 -> {
                Text("${docs.size}개 · ${sizeText(docs.sumOf { it.size })} · 전송 선택 ${docs.count { it.included }}개")
                if (tab == 0) ButtonRow {
                    Button({ files.launch(arrayOf("*/*")) }, enabled = !busy) { Text("파일 추가") }
                    OutlinedButton({ folder.launch(null) }, enabled = !busy) { Text("폴더 선택") }
                    OutlinedButton(vm::sort, enabled = !busy) { Text("파일명 정렬") }
                } else Text("PDF 분할 후 원본은 목록에 보존되고 전송 선택에서 제외됩니다.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(docs, key = { it.id }) { doc ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row {
                                    Checkbox(doc.included, { vm.toggle(doc.id) }, enabled = !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text(doc.name, style = MaterialTheme.typography.titleSmall)
                                        Text("${doc.name.substringAfterLast('.', "확장자 없음").uppercase()} · ${sizeText(doc.size)}")
                                        Text("SHA-256 ${doc.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (doc.note.isNotBlank()) Text(doc.note, style = MaterialTheme.typography.bodySmall)
                                if (doc.included) policy?.problem(doc)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                ButtonRow {
                                    TextButton({ vm.move(doc.id, -1) }, enabled = !busy) { Text("위로") }
                                    TextButton({ vm.move(doc.id, 1) }, enabled = !busy) { Text("아래로") }
                                    TextButton({
                                        runCatching {
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(doc.path))
                                            val type = when (doc.name.substringAfterLast('.').lowercase()) { "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; else -> "*/*" }
                                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "문서 미리보기"))
                                        }.onFailure { zipError = "미리보기 앱이 없거나 문서를 열 수 없습니다." }
                                    }, enabled = !busy) { Text("미리보기") }
                                    if (doc.name.endsWith(".zip", true)) TextButton({
                                        scope.launch {
                                            runCatching { vm.zipPreview(doc) }.onSuccess { zipItems = it; zipDoc = doc }.onFailure { zipError = it.message ?: "ZIP 확인 실패" }
                                        }
                                    }, enabled = !busy) { Text("ZIP 목록·해제") }
                                    if (tab == 1 && doc.name.endsWith(".pdf", true)) TextButton({ pdfWarning = doc }, enabled = !busy) { Text("PDF 분할") }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Field(settings.job, { vm.updateSettings(settings.copy(job = it)) }, "작업명", !busy) }
                    item { Field(settings.recipient, { vm.updateSettings(settings.copy(recipient = it)) }, "수신자 이메일 (한 명)", !busy) }
                    item { Field(settings.title, { vm.updateSettings(settings.copy(title = it)) }, "제목 (분할 순번 자동 추가)", !busy) }
                    item {
                        Row { Checkbox(minimize, { minimize = it }, enabled = !busy); Text("메일 수 줄이기 (큰 파일 우선 근사 알고리즘)") }
                        Button({ vm.prepare(minimize) }, enabled = !busy) { Text("메일 구성·실제 크기 검증") }
                    }
                    item { Text("${draft.size}개 메일 · 첨부 ${settings.attachMB}MB / 메시지 ${settings.messageMB}MB 제한") }
                    items(draft, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text("메일 ${row.position}/${row.total} · ${sizeText(row.messageBytes)}")
                            Text("받는 사람: ${row.recipient}"); Text(row.subject)
                            parseDocs(row.attachments).forEach { Text("• ${it.name} (${sizeText(it.size)})") }
                        } }
                    }
                    item {
                        Button({ confirmation = true }, enabled = draft.isNotEmpty() && !busy) { Text("전체 검토 후 발송 승인") }
                        Text("승인은 현재 미리보기 전체에 적용됩니다. SMTP 접수 성공은 수신기관 수신·다운로드 성공을 의미하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            3 -> {
                val accepted = history.count { it.state in listOf("ACCEPTED", "CONFIRMED") }
                val failed = history.count { it.state == "FAILED" }
                val completed = history.count { it.state in listOf("ACCEPTED", "CONFIRMED", "FAILED", "CANCELLED", "UNCERTAIN") }
                Text("전체 이력 ${history.size}건 · 접수 $accepted · 실패 $failed")
                if (history.isNotEmpty()) LinearProgressIndicator(progress = { completed.toFloat() / history.size }, modifier = Modifier.fillMaxWidth())
                Text("불확실 상태는 서버 접수 여부를 별도 확인하기 전까지 재발송하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(vm::recover, enabled = !busy) { Text("앱 중단 후 남은 상태 확인") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                            Text("${row.job} · ${row.position}/${row.total}"); Text(row.subject)
                            Text(stateText(row.state), color = if (row.state == "UNCERTAIN") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            if (row.error.isNotEmpty()) Text(row.error)
                            if (row.response.isNotEmpty()) Text(row.response, style = MaterialTheme.typography.bodySmall)
                            ButtonRow {
                                if (row.state in listOf("PENDING", "CONNECTING", "SENDING")) TextButton({ vm.cancelBatch(row.batch) }) { Text("이 작업 전체 취소") }
                                if (row.state in listOf("FAILED", "CANCELLED")) TextButton({ vm.retry(row) }, enabled = !busy) { Text("이 메일 재시도") }
                                if (row.state == "UNCERTAIN") TextButton({ resolving = row }, enabled = !busy) { Text("접수 확인 결과 입력") }
                            }
                        } }
                    }
                }
            }
            4 -> {
                var username by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("네이버 계정 최초 설정 / 변경", style = MaterialTheme.typography.titleMedium) }
                    item { Text("네이버 메일에서 IMAP/SMTP 사용을 켜고, 2단계 인증의 애플리케이션 비밀번호를 발급하세요. 저장된 비밀번호는 화면에 다시 표시하지 않습니다.") }
                    item { Field(username, { username = it }, "네이버 이메일 (@naver.com)", !busy) }
                    item { OutlinedTextField(password, { password = it }, label = { Text("애플리케이션 비밀번호") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                    item { ButtonRow {
                        Button({ vm.saveAccount(username, password); password = "" }, enabled = !busy) { Text("보안 저장") }
                        OutlinedButton(vm::testAccount, enabled = !busy) { Text("SMTP 연결 테스트") }
                    } }
                    item { Text("smtp.naver.com · 587 · STARTTLS 필수 · 서버 인증서 검증", style = MaterialTheme.typography.bodySmall) }
                    item { Text("수신기관에서 승인한 기준을 입력하세요. 기본값은 승인된 정책을 의미하지 않습니다.", color = MaterialTheme.colorScheme.error) }
                    item { Field(settings.attachMB, { vm.updateSettings(settings.copy(attachMB = it)) }, "메일당 첨부 원본 합계 제한 (MB)", !busy) }
                    item { Field(settings.fileMB, { vm.updateSettings(settings.copy(fileMB = it)) }, "개별 첨부파일 제한 (MB)", !busy) }
                    item { Field(settings.messageMB, { vm.updateSettings(settings.copy(messageMB = it)) }, "MIME 인코딩 후 전체 메시지 제한 (MB)", !busy) }
                    item { Field(settings.pdfMB, { vm.updateSettings(settings.copy(pdfMB = it)) }, "PDF 분할 목표 (1~9MB)", !busy) }
                    item { Field(settings.interval, { vm.updateSettings(settings.copy(interval = it)) }, "발송 간격 (초, 최소 30)", !busy) }
                    item { Field(settings.approved, { vm.updateSettings(settings.copy(approved = it)) }, "승인 확장자 (쉼표 구분)", !busy) }
                    item { ButtonRow {
                        OutlinedButton({ export.launch("정산자료_발송내역.csv") }, enabled = !busy) { Text("CSV 이력 내보내기") }
                        TextButton(vm::clearTemporary, enabled = !busy) { Text("임시파일 삭제") }
                    } }
                    if (Build.VERSION.SDK_INT >= 33) item { TextButton({ permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("백그라운드 발송 알림 허용") } }
                    item { Text("이전 발송 이력 ${history.size}건", style = MaterialTheme.typography.titleMedium) }
                    items(history, key = { it.id }) { row ->
                        Text("${row.job} · ${row.recipient}\n${row.subject}\n${stateText(row.state)} · ${if (row.time == 0L) "대기" else java.time.Instant.ofEpochMilli(row.time)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text("전체 발송 최종 승인") },
        text = { Text("${draft.size}개 메일을 ${settings.recipient}로 순차 발송합니다. 첨부파일·기관 승인 정책을 모두 확인했습니까? 실제 업무자료 대신 더미 파일과 테스트 계정으로 먼저 검증하세요.") },
        confirmButton = { TextButton({ confirmation = false; vm.approve(); tab = 3 }) { Text("확인한 전체 목록 발송") } },
        dismissButton = { TextButton({ confirmation = false }) { Text("돌아가기") } })
    pdfWarning?.let { doc -> AlertDialog(onDismissRequest = { pdfWarning = null }, title = { Text("PDF 분할 확인") },
        text = { Text("전자서명·인증정보가 손상되거나 분할본에 유지되지 않을 수 있습니다. 원본은 보존합니다. 단일 페이지가 제한을 넘으면 별도 처리 필요로 표시합니다.") },
        confirmButton = { TextButton({ pdfWarning = null; vm.split(doc) }) { Text("분할") } }, dismissButton = { TextButton({ pdfWarning = null }) { Text("취소") } }) }
    zipDoc?.let { doc -> AlertDialog(onDismissRequest = { zipDoc = null }, title = { Text("ZIP 내부 목록 · ${zipItems.size}개") },
        text = { Column { Text("승인받은 문서 처리에 한해 압축을 해제하세요. 미승인 확장자는 전송에서 제외합니다."); LazyColumn(Modifier.heightIn(max = 300.dp)) { items(zipItems) { Text(it, style = MaterialTheme.typography.bodySmall) } } } },
        confirmButton = { TextButton({ zipDoc = null; vm.extract(doc) }) { Text("승인된 문서 로컬 해제") } }, dismissButton = { TextButton({ zipDoc = null }) { Text("취소") } }) }
    if (zipError.isNotEmpty()) AlertDialog(onDismissRequest = { zipError = "" }, title = { Text("확인 필요") }, text = { Text(zipError) }, confirmButton = { TextButton({ zipError = "" }) { Text("닫기") } })
    resolving?.let { row -> AlertDialog(onDismissRequest = { resolving = null }, title = { Text("접수 여부를 별도 확인했습니까?") },
        text = { Text("네이버 발송내역 또는 관리자에게 확인하세요. 확인 전에는 닫기를 선택하세요. 미접수를 확인한 경우에만 실패 상태로 변경하며, 재시도는 별도 버튼으로 수행합니다.") },
        confirmButton = { TextButton({ vm.resolve(row, true); resolving = null }) { Text("접수 확인됨") } },
        dismissButton = { Column { TextButton({ vm.resolve(row, false); resolving = null }) { Text("미접수 확인됨") }; TextButton({ resolving = null }) { Text("닫기") } } }) }
}
