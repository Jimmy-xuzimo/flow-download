package dev.flowdownload

import android.app.Application
import android.content.Context
import android.net.*
import androidx.room.Room
import dev.flowdownload.core.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class FlowApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { Room.databaseBuilder(this, DownloadDatabase::class.java, "downloads.db").build() }
    val settingsRepository by lazy { SettingsRepository(this) }
    val manager by lazy { TaskManager(this, database.tasks(), settingsRepository, scope) }
}

/** Commands are serialized; pausing always joins disk writers before changing task state. */
class TaskManager(
    private val context: Context,
    val dao: TaskDao,
    private val preferences: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val tasks = dao.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val settings = preferences.flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors = _errors.asSharedFlow()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val running = mutableMapOf<String, Job>()
    private var enabled = false
    private var serviceToken: String? = null
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val vault = HeaderVault()
    private val engine = DownloadEngine(File(context.filesDir, "parts"), BandwidthLimiter { settings.value.limitKiB * 1024L })
    private val exporter = FileExporter(context, dao)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { post {} }
        override fun onLost(n: Network) { post {} }
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { post {} }
    }
    init {
        network.registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            dao.all().filter { it.state in setOf(TaskState.RUNNING, TaskState.EXPORTING) }.forEach {
                dao.update(it.copy(state = TaskState.QUEUED, speed = 0, message = "任务已恢复，等待继续"))
            }
            for (command in commands) {
                try { command(); schedule() }
                catch (e: Exception) { if (e is CancellationException) throw e; _errors.emit(e.message ?: "操作失败") }
            }
        }
        scope.launch { while (isActive) { delay(1000); post {} } }
        scope.launch { settings.collect { post {} } }
    }
    private fun post(block: suspend () -> Unit) { commands.trySend(block) }
    fun serviceReady(token: String, ready: Boolean) = post {
        if (ready) serviceToken = token else if (serviceToken != token) return@post
        enabled = ready
        if (!ready) pauseRunning("后台服务已停止，打开应用后可继续", TaskState.QUEUED)
    }
    suspend fun isIdle(): Boolean {
        val result = CompletableDeferred<Boolean>()
        // A barrier behind all submitted add/resume commands; Room's UI Flow may lag behind.
        post { result.complete(dao.all().none { it.state in WORK_STATES }) }
        return result.await()
    }
    fun report(message: String) { _errors.tryEmit(message) }
    fun add(urls: List<String>, name: String, connections: Int, directory: String, headers: Map<String, String>) = post {
        val encrypted = vault.encrypt(headers)
        dao.insert(urls.map { DownloadTask(url = it, name = InputRules.fileName(if (urls.size == 1 && name.isNotBlank()) name else InputRules.suggestedName(it)),
            connections = connections.coerceIn(1, 16), directory = directory, encryptedHeaders = encrypted) })
    }
    fun pause(id: String) = post { stop(id, TaskState.PAUSED, "") }
    fun resume(id: String, restart: Boolean = false) = post {
        running.remove(id)?.cancelAndJoin()
        dao.get(id)?.let { task ->
            if (task.state == TaskState.COMPLETE) return@let
            if (task.changed && !restart) { report("远端文件已变化，请使用重新下载"); return@let }
            if (restart) engine.clear(id)
            dao.update(task.copy(state = TaskState.QUEUED, speed = 0, retries = 0, retryAt = 0, message = "", changed = false,
                plan = if (restart) "" else task.plan, downloaded = if (restart) 0 else task.downloaded))
        }
    }
    fun cancel(id: String) = post {
        if (dao.get(id)?.state == TaskState.COMPLETE) return@post
        stop(id, TaskState.CANCELLED, "任务已取消")
        if (dao.get(id)?.state == TaskState.COMPLETE) return@post
        engine.clear(id)
        dao.get(id)?.let {
            if (it.pendingUri.isNotEmpty()) exporter.discard(it.pendingUri)
            dao.update(it.copy(pendingUri = "", downloaded = 0, plan = "", retries = 0, retryAt = 0))
        }
    }
    fun pauseAll(message: String = "") = post {
        dao.all().filter { it.state in WORK_STATES }.forEach { stop(it.id, TaskState.PAUSED, message) }
    }
    fun resumeAll() = post {
        dao.all().filter { it.state in setOf(TaskState.PAUSED, TaskState.FAILED, TaskState.CANCELLED) && !it.changed }.forEach {
            dao.update(it.copy(state = TaskState.QUEUED, retries = 0, retryAt = 0, message = ""))
        }
    }
    fun remove(id: String, deleteFile: Boolean) = post {
        running.remove(id)?.cancelAndJoin()
        val task = dao.get(id) ?: return@post
        if (deleteFile && task.outputUri.isNotEmpty()) exporter.discard(task.outputUri)
        if (task.pendingUri.isNotEmpty()) exporter.discard(task.pendingUri)
        engine.clear(id)
        dao.delete(id)
    }
    fun changeDirectory(id: String, uri: String) = post {
        stop(id, TaskState.PAUSED, "保存目录已更新，可继续任务")
        dao.get(id)?.let {
            if (it.state != TaskState.COMPLETE) {
                if (it.pendingUri.isNotEmpty()) runCatching { exporter.discard(it.pendingUri) }.onFailure { report("旧目录中的未完成文件无法清理，请通过文件管理器删除") }
                dao.update(it.copy(directory = uri, pendingUri = ""))
            }
        }
    }
    fun saveSettings(value: AppSettings) = post { preferences.save(value) }
    private suspend fun stop(id: String, state: TaskState, message: String) {
        running.remove(id)?.cancelAndJoin()
        dao.get(id)?.let { if (it.state != TaskState.COMPLETE) dao.update(it.copy(state = state, speed = 0, message = message)) }
    }
    private suspend fun pauseRunning(message: String, state: TaskState) {
        running.keys.toList().forEach { stop(it, state, message) }
    }
    private fun networkAllowed(): Boolean {
        val caps = network.getNetworkCapabilities(network.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (!settings.value.wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }
    private suspend fun schedule() {
        if (!enabled) return
        val available = networkAllowed()
        if (!available) pauseRunning(if (settings.value.wifiOnly) "等待 Wi-Fi 网络" else "网络已断开，恢复连接后自动继续", TaskState.WAITING_NETWORK)
        val queued = dao.all().filter { it.state in setOf(TaskState.QUEUED, TaskState.RETRYING, TaskState.WAITING_NETWORK) }
        if (!available) {
            queued.filter { it.state != TaskState.WAITING_NETWORK }.forEach { dao.update(it.copy(state = TaskState.WAITING_NETWORK, speed = 0)) }
            return
        }
        for (task in queued) {
            if (running.size >= settings.value.concurrent) break
            if (task.id in running || task.retryAt > System.currentTimeMillis()) continue
            dao.update(task.copy(state = TaskState.RUNNING, message = "", speed = 0))
            running[task.id] = scope.launch(Dispatchers.IO) {
                val self = currentCoroutineContext()[Job]
                try {
                    var source = engine.ready(task.id)
                    if (!source.exists()) {
                        val headers = try { vault.decrypt(task.encryptedHeaders) } catch (_: Exception) { throw DownloadFailure("认证信息无法读取，请重新创建任务并填写请求头") }
                        source = engine.download(DownloadSpec(task.id, task.url, task.connections, headers), PlanCodec.decode(task.plan),
                            onPlan = { dao.plan(task.id, PlanCodec.encode(it), it.length) },
                            onProgress = { dao.progress(task.id, it.downloaded, it.total, it.bytesPerSecond) })
                    }
                    currentCoroutineContext().ensureActive()
                    val fresh = dao.get(task.id) ?: return@launch
                    dao.update(fresh.copy(state = TaskState.EXPORTING, speed = 0, message = "正在保存到目标目录"))
                    // Keep the final URI transition and cleanup atomic with respect to pause/cancel.
                    exporter.export(fresh, source)
                    withContext(NonCancellable) {
                        engine.clear(task.id)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val failure = e as? DownloadFailure
                    val disk = e.message?.contains("ENOSPC", ignoreCase = true) == true || e.message?.contains("No space", ignoreCase = true) == true
                    val retryable = !disk && (failure?.retryable ?: (e is IOException && engine.ready(task.id).exists().not()))
                    dao.get(task.id)?.let { current ->
                        val retry = retryable && current.retries < 3
                        dao.update(current.copy(state = if (retry) TaskState.RETRYING else TaskState.FAILED, speed = 0,
                            retries = current.retries + if (retry) 1 else 0,
                            retryAt = if (retry) System.currentTimeMillis() + (2000L shl current.retries) else 0,
                            changed = failure?.changed ?: false,
                            message = if (disk) "存储空间不足，请释放空间后重试" else failure?.userMessage ?: if (engine.ready(task.id).exists()) "保存失败，请检查目录权限和剩余空间后重试" else "连接失败，请检查网络或链接后重试"))
                    }
                } finally { post { if (running[task.id] === self) running.remove(task.id) } }
            }
        }
    }
    companion object {
        val WORK_STATES = setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.WAITING_NETWORK, TaskState.RETRYING, TaskState.EXPORTING)
    }
}
