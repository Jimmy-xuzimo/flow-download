package dev.flowdownload

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(kotlinx.coroutines.FlowPreview::class)
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val token = java.util.UUID.randomUUID().toString()
    private var latestStartId = 0
    private val manager get() = (application as FlowApp).manager
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "下载任务", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notification(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        manager.serviceReady(token, true)
        scope.launch {
            manager.tasks.sample(1000).collect { tasks ->
                getSystemService(NotificationManager::class.java).notify(1, notification(tasks))
                if (tasks.none { it.state in TaskManager.WORK_STATES }) {
                    val checkedStartId = latestStartId
                    if (manager.isIdle() && stopSelfResult(checkedStartId)) {
                        stopForeground(if (tasks.any { it.state == TaskState.PAUSED || it.state == TaskState.FAILED }) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        ServiceCompat.startForeground(this, 1, notification(manager.tasks.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        manager.serviceReady(token, true)
        when (intent?.action) {
            "pause" -> manager.pauseAll()
            "resume" -> manager.resumeAll()
        }
        return START_NOT_STICKY
    }
    private fun notification(tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.state in TaskManager.WORK_STATES }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String) = PendingIntent.getForegroundService(this, name.hashCode(), Intent(this, DownloadService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val bytes = active.sumOf { it.downloaded }
        val total = active.sumOf { it.total.coerceAtLeast(0) }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("流速下载 · ${active.size} 个任务")
            .setContentText(if (active.isEmpty()) "任务已暂停或失败，可继续任务" else if (active.any { it.state == TaskState.WAITING_NETWORK }) "等待可用网络" else "${formatBytes(active.sumOf { it.speed })}/s · ${formatBytes(bytes)}")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active.isNotEmpty())
            .setProgress(100, if (total > 0) (bytes.toDouble() / total * 100).toInt().coerceIn(0, 100) else 0, total == 0L)
            .addAction(0, "全部暂停", action("pause"))
            .addAction(0, "继续任务", action("resume")).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.pauseAll("已达到系统后台运行时限，请打开应用后继续")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { manager.serviceReady(token, false); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "downloads"
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
            catch (e: RuntimeException) { (context.applicationContext as FlowApp).manager.report("系统暂不允许启动后台下载，请保持应用在前台后重试") }
        }
    }
}
