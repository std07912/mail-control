package kr.local.settlement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.sun.mail.smtp.SMTPTransport
import com.sun.mail.smtp.SMTPSendFailedException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Date
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Session
import javax.mail.Message
import javax.mail.internet.*

object Mailer {
    private val active = ConcurrentHashMap<String, SMTPTransport>()
    fun session(): Session = Session.getInstance(Properties().apply {
        setProperty("mail.smtp.host", "smtp.naver.com")
        setProperty("mail.smtp.port", "587")
        setProperty("mail.smtp.auth", "true")
        setProperty("mail.smtp.starttls.enable", "true")
        setProperty("mail.smtp.starttls.required", "true")
        setProperty("mail.smtp.ssl.checkserveridentity", "true")
        setProperty("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
        setProperty("mail.smtp.connectiontimeout", "20000")
        setProperty("mail.smtp.timeout", "30000")
        setProperty("mail.smtp.writetimeout", "30000")
        setProperty("mail.smtp.sendpartial", "false")
        setProperty("mail.mime.encodefilename", "true")
        setProperty("mail.mime.splitlongparameters", "true")
    }).also { it.debug = false }
    fun address(value: String): InternetAddress {
        require(!value.contains('\r') && !value.contains('\n')) { "이메일 줄바꿈 금지" }
        val a = InternetAddress(value, true); a.validate()
        require(a.address == value.trim() && value.contains('@')) { "수신자 한 명의 이메일 주소를 입력하세요." }
        return a
    }
    fun cancel(batch: String) { active.remove(batch)?.let { t -> Thread { runCatching { t.close() } }.start() } }
    fun cleanError(e: Throwable, password: String): String {
        // No raw exception body or authentication challenge is stored.
        val code = (e as? SMTPSendFailedException)?.returnCode
        return when {
            e is javax.mail.AuthenticationFailedException -> "SMTP 인증 거부: 2단계 인증·애플리케이션 비밀번호·IMAP/SMTP 설정 확인"
            code != null -> "SMTP 응답 코드 $code (${e.javaClass.simpleName})"
            e is java.net.UnknownHostException -> "SMTP 호스트를 찾을 수 없음"
            e is javax.net.ssl.SSLException -> "TLS 인증서 또는 보안 연결 오류"
            else -> "SMTP/네트워크 오류 (${e.javaClass.simpleName.take(80)})"
        }.replace(password.ifEmpty { "\u0000" }, "[삭제]")
    }
    suspend fun test(context: Context): String = withContext(Dispatchers.IO) {
        val (user, password) = SecretStore(context).read() ?: error("계정을 먼저 저장하세요.")
        val transport = session().getTransport("smtp") as SMTPTransport
        try {
            transport.connect("smtp.naver.com", 587, user, password)
            "SMTP 인증 및 STARTTLS 연결 성공 (메일 전송 없음)"
        } catch (e: Exception) { error(cleanError(e, password)) }
        finally { runCatching { transport.close() } }
    }
    suspend fun prepare(context: Context, docs: List<Doc>, policy: Policy, minimize: Boolean,
        recipient: String, title: String, job: String, interval: Long): List<MailRow> = withContext(Dispatchers.IO) {
        address(recipient)
        require(title.isNotBlank() && title.length <= 150 && !title.contains('\r') && !title.contains('\n')) { "제목을 확인하세요 (150자 이하)." }
        val user = SecretStore(context).read()?.first ?: error("설정에서 네이버 계정을 먼저 저장하세요.")
        val groups = Grouping.pack(docs, policy, minimize); require(groups.isNotEmpty()) { "전송할 파일이 없습니다." }
        val batch = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val dir = File(context.filesDir, "outbox/$batch").apply { mkdirs() }
        try {
            groups.mapIndexed { index, files ->
                currentCoroutineContext().ensureActive()
                files.forEach { require(File(it.path).length() == it.size && hash(File(it.path)) == it.sha256) { "파일 변경 감지: ${it.name}" } }
                val subject = "$title (${index + 1}/${groups.size})"
                val id = UUID.randomUUID().toString()
                val message = MimeMessage(session()).apply {
                    setFrom(address(user)); setRecipient(Message.RecipientType.TO, address(recipient))
                    setSubject(subject, "UTF-8"); sentDate = Date()
                    val multipart = MimeMultipart("mixed")
                    multipart.addBodyPart(MimeBodyPart().apply { setText("${job}\n정산자료 ${index + 1}/${groups.size}\n첨부 문서를 확인해 주세요.", "UTF-8") })
                    files.forEach { doc -> multipart.addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(object : FileDataSource(File(doc.path)) {
                            override fun getContentType(): String = when (doc.name.substringAfterLast('.').lowercase()) {
                                "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
                                else -> "application/octet-stream"
                            }
                        })
                        fileName = MimeUtility.encodeText(doc.name, "UTF-8", null)
                        disposition = "attachment"
                    }) }
                    setContent(multipart); saveChanges()
                    // Fixed Message-ID survives retry; SMTP does not guarantee server deduplication.
                    setHeader("Message-ID", "<$id@settlement.local>")
                }
                val target = File(dir, "$id.eml")
                target.outputStream().buffered().use { message.writeTo(it) }
                require(target.length() <= policy.messageLimit) { "실제 MIME 메시지 ${target.length()} bytes: 승인 제한 초과. 첨부 기준을 낮추세요." }
                MailRow(id, batch, job, index + 1, groups.size, recipient, subject, user, docsJson(files),
                    target.path, target.length(), intervalSeconds = interval.coerceAtLeast(10), messageLimit = policy.messageLimit)
            }
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    fun enqueue(context: Context, batch: String) {
        val work = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf("batch" to batch))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(batch).addTag("smtp-all").build()
        // Per-batch durable chains avoid cancelling unrelated batches; a process mutex serializes SMTP. No automatic SMTP retry.
        WorkManager.getInstance(context).enqueueUniqueWork("smtp-$batch", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
    suspend fun send(context: Context, row: MailRow, dao: MailDao) = coroutineScope {
        val (user, password) = SecretStore(context).read() ?: error("계정 설정 없음")
        require(user == row.sender) { "작성 당시 발신 계정과 현재 계정이 다릅니다." }
        val eml = File(row.eml)
        require(eml.exists() && eml.length() == row.messageBytes && eml.length() <= row.messageLimit) { "MIME 파일 누락 또는 크기 불일치" }
        val transport = session().getTransport("smtp") as SMTPTransport
        active[row.batch] = transport
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { transport.close() } }
        }
        var sending = false
        try {
            transport.connect("smtp.naver.com", 587, user, password)
            ensureActive()
            val message = eml.inputStream().use { MimeMessage(session(), it) }
            dao.result(row.id, "SENDING", System.currentTimeMillis())
            sending = true
            ensureActive()
            transport.sendMessage(message, arrayOf(address(row.recipient)))
            val response = transport.lastServerResponse.orEmpty().replace(password, "[삭제]").take(500)
            withContext(NonCancellable) { dao.result(row.id, "ACCEPTED", System.currentTimeMillis(), response = response) }
        } catch (e: Exception) {
            val rejected = (e as? SMTPSendFailedException)?.let { it.returnCode in 400..599 && it.validSentAddresses.isNullOrEmpty() } == true
            val state = deliveryFailureState(sending, rejected, e is CancellationException)
            withContext(NonCancellable) { dao.result(row.id, state, System.currentTimeMillis(), error = cleanError(e, password)) }
            if (e is CancellationException) throw e
            if (state == "UNCERTAIN") error("접수 여부 불확실: 발송을 중단했습니다.")
        } finally { active.remove(row.batch, transport); closer.cancelAndJoin() }
    }
}

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { private val serial = Mutex() }
    private fun foreground(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sending", "정산자료 발송", NotificationManager.IMPORTANCE_LOW))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, "sending")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("정산자료 전송 도우미")
            .setContentText(text).setOngoing(true).addAction(android.R.drawable.ic_delete, "취소", cancel).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id.hashCode(), notification)
    }
    override suspend fun doWork(): Result {
        try {
            setForeground(foreground("발송 대기"))
            return serial.withLock { perform() }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                inputData.getString("batch")?.let { batch ->
                    (applicationContext as SettlementApp).db.mails().cancelPending(batch)
                }
            }
            throw e
        }
    }
    private suspend fun perform(): Result = withContext(Dispatchers.IO) {
        val batch = inputData.getString("batch") ?: return@withContext Result.failure()
        val dao = (applicationContext as SettlementApp).db.mails()
        try {
            setForeground(foreground("발송 준비"))
            dao.recoverConnections(); dao.recoverSending()
            val rows = dao.batch(batch)
            if (rows.any { it.state == "UNCERTAIN" || it.state == "SENDING" }) return@withContext Result.failure()
            for (row in rows) {
                ensureActive()
                if (dao.claim(row.id) != 1) continue
                try {
                    // Always wait between attempts, including retries and different batches.
                    val last = dao.all().maxOfOrNull { it.time } ?: 0L
                    delay((last + row.intervalSeconds * 1000 - System.currentTimeMillis()).coerceAtLeast(0))
                    setForeground(foreground("${row.position}/${row.total} 발송 중"))
                    Mailer.send(applicationContext, row, dao)
                    setProgress(workDataOf("position" to row.position, "total" to row.total))
                    if (dao.get(row.id)?.state == "FAILED") break // Authentication/rate errors stop the batch.
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        if (dao.get(row.id)?.state == "CONNECTING") dao.result(row.id, "CANCELLED", System.currentTimeMillis(), error = "사용자 취소")
                    }
                    throw e
                } catch (e: Exception) {
                    if (dao.get(row.id)?.state == "CONNECTING") dao.result(row.id, "FAILED", System.currentTimeMillis(), error = "발송 준비 오류 (${e.javaClass.simpleName})")
                    break
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.cancelPending(batch) }; throw e
        } catch (e: Exception) { Result.failure() }
    }
}
