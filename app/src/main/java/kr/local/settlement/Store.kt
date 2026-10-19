package kr.local.settlement

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class SettlementApp : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDb::class.java, "settlement.db").build() }
    override fun onCreate() { super.onCreate(); com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this) }
}

@Entity(tableName = "mails", indices = [Index(value = ["batch", "position"], unique = true)])
data class MailRow(
    @PrimaryKey val id: String,
    val batch: String, val job: String, val position: Int, val total: Int,
    val recipient: String, val subject: String, val sender: String,
    val attachments: String, val eml: String, val messageBytes: Long,
    val state: String = "PENDING", val time: Long = 0,
    val response: String = "", val error: String = "",
    val intervalSeconds: Long = 30, val messageLimit: Long = 13 * MB
)

@Dao
interface MailDao {
    @Query("SELECT * FROM mails ORDER BY batch DESC, position ASC") fun observe(): Flow<List<MailRow>>
    @Query("SELECT * FROM mails ORDER BY batch, position") suspend fun all(): List<MailRow>
    @Query("SELECT * FROM mails WHERE batch=:batch ORDER BY position") suspend fun batch(batch: String): List<MailRow>
    @Query("SELECT * FROM mails WHERE id=:id") suspend fun get(id: String): MailRow?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(rows: List<MailRow>)
    @Query("UPDATE mails SET state=:state, time=:time, response=:response, error=:error WHERE id=:id")
    suspend fun result(id: String, state: String, time: Long, response: String = "", error: String = "")
    @Query("UPDATE mails SET state='CONNECTING', error='' WHERE id=:id AND state='PENDING'") suspend fun claim(id: String): Int
    @Query("UPDATE mails SET state='PENDING', error='' WHERE id=:id AND state IN ('FAILED','CANCELLED')") suspend fun retry(id: String): Int
    @Query("UPDATE mails SET state='FAILED', error='앱 중단: 전송 시작 전 연결 단계에서 중단됨' WHERE state='CONNECTING'") suspend fun recoverConnections()
    @Query("UPDATE mails SET state='UNCERTAIN', error='전송 중 앱 중단. 서버 접수 여부를 확인하세요.' WHERE state='SENDING'") suspend fun recoverSending()
    @Query("UPDATE mails SET state='CANCELLED', error='사용자 취소' WHERE batch=:batch AND state='PENDING'") suspend fun cancelPending(batch: String)
}

@Database(entities = [MailRow::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun mails(): MailDao }

fun docsJson(docs: List<Doc>): String = JSONArray().apply {
    docs.forEach { d -> put(JSONObject().put("id", d.id).put("name", d.name).put("path", d.path)
        .put("size", d.size).put("sha256", d.sha256).put("origin", d.origin)) }
}.toString()
fun parseDocs(value: String): List<Doc> = JSONArray(value).let { a -> (0 until a.length()).map {
    val d = a.getJSONObject(it)
    Doc(d.getString("id"), d.getString("name"), d.getString("path"), d.getLong("size"), d.getString("sha256"), origin = d.optString("origin"))
} }

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("smtp_secret", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("smtp-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("smtp-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(user: String, password: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = c.doFinal(JSONObject().put("user", user).put("password", password).toString().toByteArray())
        check(prefs.edit().putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("value", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun read(): Pair<String, String>? {
        val data = prefs.getString("value", null) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        val json = JSONObject(String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
        return json.getString("user") to json.getString("password")
    }
    fun clear() { prefs.edit().clear().commit() }
}
