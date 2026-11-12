package kr.local.settlement

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceDeviceTest {
    @Test fun completedWorkspaceClearsAndHistorySurvivesRestartAndNewWork() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SettlementApp
        val vm = MainViewModel(app)
        suspend fun idle() { withTimeout(15000) { while (vm.busy.value) delay(25) } }
        idle(); vm.newWork(); idle()
        val original = File(app.filesDir, "documents/test-${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs(); writeText("dummy workspace only") }
        val originalHash = hash(original)
        try {
            vm.import(listOf(FileProvider.getUriForFile(app, "${app.packageName}.files", original))); idle()
            assertEquals(1, vm.docs.value.size)
            val doc = vm.docs.value.single()
            val batch = UUID.randomUUID().toString()
            val row = MailRow(UUID.randomUUID().toString(), batch, "테스트", 1, 1,
                "dummy@example.com", "테스트", "dummy@naver.com", docsJson(listOf(doc)), "", 0, state = "SENDING")
            app.getSharedPreferences("settings", 0).edit().putString("submitted_batch", batch)
                .putStringSet("submitted_ids", setOf(doc.id)).commit()
            app.db.mails().insert(listOf(row))
            delay(100); assertEquals(1, vm.docs.value.size)
            app.db.mails().result(row.id, "ACCEPTED", System.currentTimeMillis(), "250 dummy test")
            withTimeout(15000) { while (vm.docs.value.isNotEmpty()) delay(25) }
            idle()
            assertEquals("ACCEPTED", app.db.mails().get(row.id)!!.state)
            val restored = MainViewModel(app)
            withTimeout(15000) { while (restored.busy.value) delay(25) }
            assertTrue(restored.docs.value.isEmpty())
            restored.newWork()
            withTimeout(15000) { while (restored.busy.value) delay(25) }
            assertEquals("ACCEPTED", app.db.mails().get(row.id)!!.state)
            assertEquals(originalHash, hash(original))
        } finally { original.delete() }
    }
}
