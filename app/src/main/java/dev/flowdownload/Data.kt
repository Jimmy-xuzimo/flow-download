package dev.flowdownload

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import dev.flowdownload.core.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

enum class TaskState(val label: String) {
    QUEUED("排队中"), RUNNING("下载中"), PAUSED("已暂停"), WAITING_NETWORK("等待网络"),
    RETRYING("等待重试"), EXPORTING("正在保存"), COMPLETE("已完成"), FAILED("下载失败"), CANCELLED("已取消")
}

@Entity(tableName = "downloads")
data class DownloadTask(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val url: String,
    val name: String,
    val connections: Int = 4,
    val directory: String = "",
    val encryptedHeaders: String = "",
    val state: TaskState = TaskState.QUEUED,
    val total: Long = -1,
    val downloaded: Long = 0,
    val speed: Long = 0,
    val plan: String = "",
    val message: String = "",
    val changed: Boolean = false,
    val retries: Int = 0,
    val retryAt: Long = 0,
    val outputUri: String = "",
    val pendingUri: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
class StateConverter {
    @TypeConverter fun encode(state: TaskState): String = state.name
    @TypeConverter fun decode(state: String): TaskState = TaskState.valueOf(state)
}
@Dao
interface TaskDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") fun observe(): Flow<List<DownloadTask>>
    @Query("SELECT * FROM downloads ORDER BY createdAt ASC") suspend fun all(): List<DownloadTask>
    @Query("SELECT * FROM downloads WHERE id=:id") suspend fun get(id: String): DownloadTask?
    @Insert suspend fun insert(tasks: List<DownloadTask>)
    @Update suspend fun update(task: DownloadTask)
    @Query("DELETE FROM downloads WHERE id=:id") suspend fun delete(id: String)
    @Query("UPDATE downloads SET downloaded=:bytes, total=:total, speed=:speed WHERE id=:id")
    suspend fun progress(id: String, bytes: Long, total: Long, speed: Long)
    @Query("UPDATE downloads SET plan=:plan, total=:total WHERE id=:id") suspend fun plan(id: String, plan: String, total: Long)
    @Query("UPDATE downloads SET pendingUri=:uri WHERE id=:id") suspend fun pending(id: String, uri: String)
}
@Database(entities = [DownloadTask::class], version = 1, exportSchema = true)
@TypeConverters(StateConverter::class)
abstract class DownloadDatabase : RoomDatabase() { abstract fun tasks(): TaskDao }

object PlanCodec {
    fun encode(plan: Plan): String = JSONObject().apply {
        put("length", plan.length); put("ranged", plan.ranged)
        put("etag", plan.etag); put("modified", plan.modified)
        put("slices", JSONArray().apply { plan.slices.forEach { put(JSONArray().put(it.start).put(it.end)) } })
    }.toString()
    fun decode(value: String): Plan? = if (value.isBlank()) null else JSONObject(value).let { obj ->
        val slices = obj.getJSONArray("slices")
        Plan(obj.getLong("length"), obj.getBoolean("ranged"), obj.optString("etag").takeIf { it.isNotEmpty() },
            obj.optString("modified").takeIf { it.isNotEmpty() },
            (0 until slices.length()).map { val s = slices.getJSONArray(it); Slice(s.getLong(0), s.getLong(1)) })
    }
}

/** Request headers are encrypted with a non-exportable Android Keystore key. */
class HeaderVault {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("flow-headers", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("flow-headers", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(JSONObject(headers).toString().toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(value: String): Map<String, String> {
        if (value.isEmpty()) return emptyMap()
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
        return json.keys().asSequence().associateWith { json.getString(it) }
    }
}

private val Context.settingsStore by preferencesDataStore("settings")
data class AppSettings(val wifiOnly: Boolean = false, val concurrent: Int = 3, val connections: Int = 4, val limitKiB: Int = 0, val theme: String = "system", val directory: String = "")
class SettingsRepository(private val context: Context) {
    private val wifi = booleanPreferencesKey("wifi")
    private val concurrent = intPreferencesKey("concurrent")
    private val connections = intPreferencesKey("connections")
    private val limit = intPreferencesKey("limit")
    private val theme = stringPreferencesKey("theme")
    private val directory = stringPreferencesKey("directory")
    val flow: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(p[wifi] ?: false, p[concurrent] ?: 3, p[connections] ?: 4, p[limit] ?: 0, p[theme] ?: "system", p[directory] ?: "")
    }
    suspend fun save(s: AppSettings) { context.settingsStore.edit {
        it[wifi] = s.wifiOnly; it[concurrent] = s.concurrent.coerceIn(1, 5); it[connections] = s.connections.coerceIn(1, 16)
        it[limit] = s.limitKiB.coerceIn(0, 102400); it[theme] = s.theme; it[directory] = s.directory
    } }
}
