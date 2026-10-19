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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettlementTheme { App() } }
    }
}
fun sizeText(bytes: Long): String = String.format(Locale.KOREA, "%.2f MB", bytes.toDouble() / MB)
fun stateText(state: String) = when (state) {
    "PENDING" -> "대기"; "CONNECTING" -> "연결 중"; "SENDING" -> "전송 중"; "ACCEPTED" -> "SMTP 접수 성공"
    "CONFIRMED" -> "접수 확인 완료"; "FAILED" -> "실패"; "UNCERTAIN" -> "접수 확인 필요"
    "CANCELLED" -> "취소"; else -> state
}
@Composable private fun Field(
    value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true,
    modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        singleLine = true, shape = RoundedCornerShape(13.dp), keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
@Composable private fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable private fun App(vm: MainViewModel = viewModel()) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accountEmail by vm.accountEmail.collectAsStateWithLifecycle()
    val connectionOk by vm.connectionOk.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(4) }
    var initialNavigationDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (!busy && !initialNavigationDone) { initialNavigationDone = true; if (accountEmail.isNotEmpty()) tab = 0 }
    }
    var minimize by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf(false) }
    var pdfWarning by remember { mutableStateOf<Doc?>(null) }
    var zipDoc by remember { mutableStateOf<Doc?>(null) }
    var zipItems by remember { mutableStateOf<List<String>>(emptyList()) }
    var problem by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf<MailRow?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::folder) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::export) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val policy = runCatching { settings.policy() }.getOrNull()
    val labels = listOf("파일", "처리", "구성", "발송", "설정")
    val icons = listOf(Icons.Outlined.List, Icons.Outlined.Build, Icons.Outlined.Email, Icons.Outlined.Send, Icons.Outlined.Settings)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Outlined.Email, null, Modifier.padding(11.dp).size(23.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("정산자료 전송 도우미", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("자료 정리부터 메일 발송까지", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icons[i], null, Modifier.size(23.dp)) }, label = { Text(label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (message.isNotEmpty()) {
                Surface(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (busy) Icons.Outlined.Refresh else Icons.Outlined.Info, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (busy) TextButton(vm::cancelProcessing, contentPadding = PaddingValues(6.dp)) { Text("취소") }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0, 1 -> FilesScreen(docs, policy, tab == 1, busy,
                    onAdd = { files.launch(arrayOf("*/*")) }, onFolder = { folder.launch(null) },
                    onSort = vm::sort, onExclude = vm::excludeUnapprovedSelection, onToggle = vm::toggle,
                    onMove = vm::move, onSplit = { pdfWarning = it }, onNext = { tab = if (tab == 0) 1 else 2 },
                    onPreview = { doc ->
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(doc.path))
                            val type = when (doc.name.substringAfterLast('.').lowercase()) {
                                "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; else -> "*/*"
                            }
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "문서 미리보기"))
                        }.onFailure { problem = "미리보기 앱이 없거나 문서를 열 수 없습니다." }
                    }, onZip = { doc ->
                        scope.launch {
                            try { zipItems = vm.zipPreview(doc); zipDoc = doc }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { problem = e.message ?: "ZIP 확인 실패" }
                        }
                    })
                2 -> ComposeScreen(settings, draft, busy, minimize, { minimize = it }, vm::updateSettings,
                    { vm.prepare(minimize) }, { confirmation = true })
                3 -> SendingScreen(history, busy, vm::recover, vm::cancelBatch, vm::retry, { resolving = it })
                4 -> SettingsScreen(settings, accountEmail, connectionOk, history, busy, vm::updateSettings,
                    vm::saveAccount, vm::testAccount, { export.launch("정산자료_발송내역.csv") }, vm::clearTemporary,
                    { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
        }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text("발송 전 마지막 확인") },
        text = { Text("${draft.size}개 메일을 ${settings.recipient}로 순차 발송합니다. 수신자, 첨부파일과 기관 승인 정책을 모두 확인했습니까?") },
        confirmButton = { TextButton({ confirmation = false; vm.approve(); tab = 3 }) { Text("확인한 목록 발송") } },
        dismissButton = { TextButton({ confirmation = false }) { Text("다시 검토") } })
    pdfWarning?.let { doc -> AlertDialog(onDismissRequest = { pdfWarning = null }, title = { Text("PDF 분할 확인") },
        text = { Text("전자서명·인증정보가 분할본에 유지되지 않을 수 있습니다. 원본은 보존합니다. 단일 페이지가 제한을 넘으면 별도 처리 필요로 표시합니다.") },
        confirmButton = { TextButton({ pdfWarning = null; vm.split(doc) }) { Text("분할하기") } }, dismissButton = { TextButton({ pdfWarning = null }) { Text("취소") } }) }
    zipDoc?.let { doc -> AlertDialog(onDismissRequest = { zipDoc = null }, title = { Text("ZIP 내부 문서 ${zipItems.size}개") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("승인받은 문서에 한해 해제하세요. 미승인 형식은 전송에서 제외합니다.")
            LazyColumn(Modifier.heightIn(max = 300.dp)) { items(zipItems) { Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall) } }
        } },
        confirmButton = { TextButton({ zipDoc = null; vm.extract(doc) }) { Text("승인된 문서 해제") } }, dismissButton = { TextButton({ zipDoc = null }) { Text("취소") } }) }
    if (problem.isNotEmpty()) AlertDialog(onDismissRequest = { problem = "" }, title = { Text("확인해 주세요") }, text = { Text(problem) },
        confirmButton = { TextButton({ problem = "" }) { Text("닫기") } })
    resolving?.let { row -> AlertDialog(onDismissRequest = { resolving = null }, title = { Text("서버 접수 여부 확인") },
        text = { Text("서버 또는 관리자에게 접수 여부를 별도 확인한 경우에만 선택하세요. 미접수로 변경한 뒤 재시도는 별도 버튼으로 진행합니다. 확인 전에는 닫기를 선택하세요.") },
        confirmButton = { TextButton({ vm.resolve(row, true); resolving = null }) { Text("접수 확인됨") } },
        dismissButton = { Column {
            TextButton({ vm.resolve(row, false); resolving = null }) { Text("미접수 확인됨") }
            TextButton({ resolving = null }) { Text("닫기") }
        } }) }
}

@Composable private fun FilesScreen(
    docs: List<Doc>, policy: Policy?, processing: Boolean, busy: Boolean,
    onAdd: () -> Unit, onFolder: () -> Unit, onSort: () -> Unit, onExclude: () -> Unit,
    onToggle: (String) -> Unit, onMove: (String, Int) -> Unit, onSplit: (Doc) -> Unit,
    onNext: () -> Unit, onPreview: (Doc) -> Unit, onZip: (Doc) -> Unit
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val unsupported = if (policy == null) emptyList() else docs.filter { !policy.approved(it.name) }
    val selectedUnsupported = unsupported.count { it.included }
    val visible = docs.filter { filter == 0 || (filter == 1 && it.included) || (filter == 2 && !it.included) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item { PageHeading(if (processing) "파일 처리" else "파일 선택", if (processing) "크기와 형식을 확인하고 전송할 자료를 준비하세요." else "필요한 문서를 모으고 전송할 파일을 선택하세요.") }
        item { FileOverview(docs, policy) }
        if (!processing) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onAdd, Modifier.weight(1f).height(50.dp), enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("파일 추가")
                }
                OutlinedButton(onFolder, Modifier.weight(1f).height(50.dp), enabled = !busy, shape = RoundedCornerShape(14.dp)) { Text("폴더 선택") }
            }
        }
        if (unsupported.isNotEmpty()) item {
            Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .4f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("미승인 형식 ${unsupported.size}개", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("전송 선택만 해제하며 파일은 삭제하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onExclude, Modifier.fillMaxWidth(), enabled = !busy && policy != null && selectedUnsupported > 0,
                        shape = RoundedCornerShape(12.dp)) { Text(if (selectedUnsupported > 0) "미승인 형식 선택 해제 ($selectedUnsupported)" else "미승인 형식 선택 해제 완료") }
                }
            }
        }
        if (policy == null) item { Hint("설정의 용량·승인 확장자를 확인해 주세요.", warning = true) }
        if (processing) item { Hint("큰 PDF는 페이지 순서대로 분할합니다. 분할한 원본은 보존되며 선택에서 제외됩니다.") }
        if (docs.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("전체 ${docs.size}", "선택 ${docs.count { it.included }}", "제외 ${docs.count { !it.included }}").forEachIndexed { i, label ->
                        FilterChip(filter == i, { filter = i }, label = { Text(label) })
                    }
                }
                IconButton(onSort, enabled = !busy) { Icon(Icons.Outlined.List, "파일명 정렬") }
            }
        }
        if (docs.isEmpty()) item { EmptyState("아직 추가한 파일이 없어요", "파일 또는 폴더를 선택하면\n문서 목록을 한눈에 확인할 수 있습니다.") }
        else if (visible.isEmpty()) item { EmptyState("이 목록에는 파일이 없어요", "다른 필터에서 파일을 확인하세요.") }
        items(visible, key = { it.id }) { doc ->
            DocumentCard(doc, policy, processing, busy, onToggle, onMove, onSplit, onPreview, onZip)
        }
        if (docs.isNotEmpty()) item { PrimaryAction(if (processing) "메일 구성으로 이동" else "파일 처리로 이동", !busy, Icons.Outlined.ArrowForward, onNext) }
    }
}

@Composable private fun DocumentCard(
    doc: Doc, policy: Policy?, processing: Boolean, busy: Boolean,
    onToggle: (String) -> Unit, onMove: (String, Int) -> Unit,
    onSplit: (Doc) -> Unit, onPreview: (Doc) -> Unit, onZip: (Doc) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var details by rememberSaveable(doc.id) { mutableStateOf(false) }
    val problem = policy?.problem(doc)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = if (doc.included) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(doc.name.substringAfterLast('.', "FILE").uppercase(Locale.ROOT).take(5), Modifier.padding(horizontal = 10.dp, vertical = 17.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(doc.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(sizeText(doc.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(doc.included, { onToggle(doc.id) }, enabled = !busy)
            }
            StatePill(if (!doc.included) "선택 제외" else problem ?: "전송 준비", warning = doc.included && problem != null, neutral = !doc.included)
            if (doc.note.isNotEmpty()) Text(doc.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (details) Text("출처: ${doc.origin}\nSHA-256\n${doc.sha256}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onPreview(doc) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("미리보기") }
                Spacer(Modifier.weight(1f))
                if (doc.name.endsWith(".zip", true)) FilledTonalButton({ onZip(doc) }, enabled = !busy, shape = RoundedCornerShape(10.dp)) { Text("ZIP 해제") }
                if (processing && doc.name.endsWith(".pdf", true)) FilledTonalButton({ onSplit(doc) }, enabled = !busy, shape = RoundedCornerShape(10.dp)) { Text("PDF 분할") }
                Box {
                    IconButton({ menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "파일 추가 작업") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("위로 이동") }, onClick = { menu = false; onMove(doc.id, -1) })
                        DropdownMenuItem(text = { Text("아래로 이동") }, onClick = { menu = false; onMove(doc.id, 1) })
                        DropdownMenuItem(text = { Text(if (details) "상세정보 접기" else "상세정보 보기") }, onClick = { menu = false; details = !details })
                    }
                }
            }
        }
    }
}

@Composable private fun ComposeScreen(
    settings: Settings, draft: List<MailRow>, busy: Boolean, minimize: Boolean,
    onMinimize: (Boolean) -> Unit, onSettings: (Settings) -> Unit, onPrepare: () -> Unit, onApprove: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("메일 구성", "수신자와 첨부 목록을 확인하고 발송을 준비하세요.") }
        item { SectionCard("받는 사람과 제목", "메일마다 순번을 자동으로 붙입니다.", Icons.Outlined.Email) {
            Field(settings.job, { onSettings(settings.copy(job = it)) }, "작업명", !busy)
            Field(settings.recipient, { onSettings(settings.copy(recipient = it)) }, "수신자 이메일", !busy, keyboard = KeyboardType.Email)
            Field(settings.title, { onSettings(settings.copy(title = it)) }, "메일 제목", !busy)
        } }
        item { SectionCard("첨부파일 묶음", "첨부 합계 ${settings.attachMB}MB · 전체 메시지 ${settings.messageMB}MB") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("메일 수 줄이기", style = MaterialTheme.typography.titleSmall)
                    Text("파일 순서가 바뀔 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(minimize, onMinimize, enabled = !busy)
            }
            PrimaryAction("첨부 목록 구성하기", !busy, Icons.Outlined.Refresh, onPrepare)
        } }
        if (draft.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("발송 미리보기", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                StatePill("총 ${draft.size}개 메일")
            }
        }
        items(draft, key = { it.id }) { row ->
            SectionCard("메일 ${row.position} / ${row.total}", sizeText(row.messageBytes), Icons.Outlined.Email) {
                Text(row.subject, fontWeight = FontWeight.SemiBold)
                Text(row.recipient, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                parseDocs(row.attachments).forEach { doc ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.primary)
                        Text(doc.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(sizeText(doc.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (draft.isEmpty()) item { Hint("목록 구성 후 각 메일의 실제 크기와 첨부를 확인할 수 있습니다. 이 단계에서는 발송하지 않습니다.") }
        else item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryAction("전체 검토 후 발송 승인", !busy, Icons.Outlined.Send, onApprove)
                Hint("SMTP 접수 성공은 수신기관의 최종 수신·다운로드 완료를 의미하지 않습니다.")
            }
        }
    }
}

@Composable private fun SendingScreen(
    history: List<MailRow>, busy: Boolean, onRecover: () -> Unit,
    onCancel: (String) -> Unit, onRetry: (MailRow) -> Unit, onResolve: (MailRow) -> Unit
) {
    val groups = history.groupBy { it.batch }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("발송 현황", "작업별 진행 상황과 접수 결과를 확인하세요.") }
        if (history.isEmpty()) item { EmptyState("아직 발송한 메일이 없어요", "메일 구성에서 목록을 검토하고\n최종 승인하면 이곳에 표시됩니다.", Icons.Outlined.Send) }
        else item { Hint("접수 여부가 불확실한 메일은 서버 또는 관리자에게 확인한 뒤 처리하세요.") }
        groups.forEach { (_, rows) ->
            val completed = rows.count { it.state in listOf("ACCEPTED", "CONFIRMED", "FAILED", "CANCELLED", "UNCERTAIN") }
            val accepted = rows.count { it.state in listOf("ACCEPTED", "CONFIRMED") }
            val failed = rows.count { it.state == "FAILED" }
            item(key = "batch-${rows.first().batch}") { SectionCard(rows.first().job, "전체 ${rows.size}건 · 접수 $accepted · 실패 $failed", Icons.Outlined.Send) {
                LinearProgressIndicator(progress = { completed.toFloat() / rows.size }, modifier = Modifier.fillMaxWidth(), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                Text("$completed / ${rows.size}건 처리", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            items(rows, key = { it.id }) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("메일 ${row.position}/${row.total}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            StatePill(stateText(row.state), warning = row.state in listOf("FAILED", "UNCERTAIN"), neutral = row.state in listOf("PENDING", "CANCELLED"))
                        }
                        Text(row.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(row.recipient, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.error.isNotEmpty()) Text(row.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (row.response.isNotEmpty()) Text(row.response, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (row.state) {
                            "PENDING", "CONNECTING", "SENDING" -> OutlinedButton({ onCancel(row.batch) }) { Text("이 작업 전체 취소") }
                            "FAILED", "CANCELLED" -> FilledTonalButton({ onRetry(row) }, enabled = !busy) { Text("이 메일만 재시도") }
                            "UNCERTAIN" -> FilledTonalButton({ onResolve(row) }, enabled = !busy) { Text("접수 확인 결과 입력") }
                        }
                    }
                }
            }
        }
        item { TextButton(onRecover, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("앱 중단 후 남은 상태 확인") } }
    }
}

@Composable private fun SettingsScreen(
    settings: Settings, accountEmail: String, connectionOk: Boolean, history: List<MailRow>, busy: Boolean,
    onSettings: (Settings) -> Unit, onSave: (String, String) -> Unit, onTest: () -> Unit,
    onExport: () -> Unit, onClear: () -> Unit, onPermission: () -> Unit
) {
    var username by rememberSaveable(accountEmail) { mutableStateOf(accountEmail) }
    // Never put passwords in saved instance state.
    var password by remember { mutableStateOf("") }
    var editAccount by rememberSaveable(accountEmail) { mutableStateOf(accountEmail.isEmpty()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("설정 및 이력", "계정을 연결하고 기관에서 승인한 기준을 설정하세요.") }
        item { SectionCard("네이버 메일 계정", if (accountEmail.isEmpty()) "처음 한 번만 연결하면 됩니다." else "보안 저장된 계정을 사용합니다.", Icons.Outlined.AccountCircle) {
            if (accountEmail.isNotEmpty()) {
                Text(accountEmail, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                StatePill(if (connectionOk) "최근 SMTP 연결 성공" else "계정 저장됨")
                Text("비밀번호를 다시 입력하지 않아도 됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editAccount) {
                Field(username, { username = it }, "네이버 이메일", !busy, keyboard = KeyboardType.Email)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("애플리케이션 비밀번호") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, shape = RoundedCornerShape(13.dp))
                Text("네이버 2단계 인증에서 발급한 앱 비밀번호를 사용하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryAction("계정 보안 저장", !busy && username.isNotBlank() && password.isNotBlank(), Icons.Outlined.Done) {
                    onSave(username, password); password = ""
                }
            }
            ButtonRow {
                FilledTonalButton(onTest, enabled = !busy && accountEmail.isNotEmpty(), shape = RoundedCornerShape(12.dp)) { Text("SMTP 연결 테스트") }
                if (accountEmail.isNotEmpty()) TextButton({ editAccount = !editAccount; password = "" }, enabled = !busy) { Text(if (editAccount) "입력 접기" else "계정 변경") }
            }
            Hint("네이버 메일의 IMAP/SMTP 사용과 2단계 인증을 켜주세요. 연결 테스트는 메일을 보내지 않습니다.")
        } }
        item { SectionCard("첨부 및 PDF 기준", "수신기관에서 승인한 값을 입력하세요.", Icons.Outlined.Build) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(settings.attachMB, { onSettings(settings.copy(attachMB = it)) }, "메일 합계 MB", !busy, Modifier.weight(1f), KeyboardType.Number)
                Field(settings.fileMB, { onSettings(settings.copy(fileMB = it)) }, "파일당 MB", !busy, Modifier.weight(1f), KeyboardType.Number)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(settings.pdfMB, { onSettings(settings.copy(pdfMB = it)) }, "PDF 목표 MB", !busy, Modifier.weight(1f), KeyboardType.Number)
                Field(settings.interval, { onSettings(settings.copy(interval = it)) }, "발송 간격 초", !busy, Modifier.weight(1f), KeyboardType.Number)
            }
            Text("PDF 1~9MB · 발송 간격 30초 이상", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ advanced = !advanced }, contentPadding = PaddingValues(0.dp)) { Text(if (advanced) "세부 기준 접기" else "승인 형식·메시지 제한 설정") }
            if (advanced) {
                Field(settings.messageMB, { onSettings(settings.copy(messageMB = it)) }, "인코딩 후 전체 메시지 제한 MB", !busy, keyboard = KeyboardType.Number)
                OutlinedTextField(settings.approved, { onSettings(settings.copy(approved = it)) }, Modifier.fillMaxWidth(), label = { Text("승인 확장자 (쉼표 구분)") }, enabled = !busy, minLines = 2, maxLines = 4, shape = RoundedCornerShape(13.dp))
                Hint("기관에서 메일 전체 크기를 제한한다면 인코딩 후 메시지 제한도 그 기준에 맞추세요. 기본값은 기관 승인값이 아닙니다.")
            }
        } }
        item { SectionCard("이력과 저장공간", "발송내역은 CSV 파일로 저장할 수 있습니다.", Icons.Outlined.List) {
            OutlinedButton(onExport, Modifier.fillMaxWidth().heightIn(min = 46.dp), enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text("CSV 발송내역 내보내기") }
            TextButton({ clearConfirmation = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("완료된 작업 임시파일 삭제") }
            if (Build.VERSION.SDK_INT >= 33) TextButton(onPermission, modifier = Modifier.fillMaxWidth()) { Text("백그라운드 발송 알림 허용") }
        } }
        if (history.isNotEmpty()) item { Text("최근 발송 내역", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(history.take(20), key = { it.id }) { row ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(row.subject, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(row.recipient, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatePill(stateText(row.state), warning = row.state in listOf("FAILED", "UNCERTAIN"), neutral = row.state == "PENDING")
                }
            }
        }
    }
    if (clearConfirmation) AlertDialog(onDismissRequest = { clearConfirmation = false }, title = { Text("임시파일을 삭제할까요?") },
        text = { Text("선택 목록과 완료 작업의 앱 내부 복사본을 정리합니다. 원본과 발송내역은 보존합니다. 미완료 작업이 있으면 삭제하지 않습니다.") },
        confirmButton = { TextButton({ clearConfirmation = false; onClear() }) { Text("정리하기") } },
        dismissButton = { TextButton({ clearConfirmation = false }) { Text("취소") } })
}
