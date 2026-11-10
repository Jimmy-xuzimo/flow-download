package dev.flowdownload

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flowdownload.core.InputRules
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val shared = MutableStateFlow("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) acceptIntent(intent)
        setContent {
            val manager = (application as FlowApp).manager
            val settings by manager.settings.collectAsStateWithLifecycle()
            val text by shared.collectAsStateWithLifecycle()
            FlowTheme(settings.theme) { DownloadApp(manager, text) { shared.value = "" } }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun acceptIntent(intent: Intent) {
        shared.value = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }
    }
}

private val Green = Color(0xFF146C5B)
private val LightColors = lightColorScheme(primary = Green, secondary = Color(0xFF53665E), tertiary = Color(0xFF9B6C32),
    background = Color(0xFFF6F8F5), surface = Color(0xFFFCFDFB), surfaceVariant = Color(0xFFEAF0E9), primaryContainer = Color(0xFFD5EDE1),
    onPrimaryContainer = Color(0xFF103C30), secondaryContainer = Color(0xFFDCECE2), onSecondaryContainer = Color(0xFF173C2E))
private val DarkColors = darkColorScheme(primary = Color(0xFF8FD5B8), secondary = Color(0xFFB5C9BD), background = Color(0xFF111814),
    surface = Color(0xFF18201B), surfaceVariant = Color(0xFF253229), primaryContainer = Color(0xFF254C3D),
    onPrimaryContainer = Color(0xFFB4EFD5), secondaryContainer = Color(0xFF314D3E), onSecondaryContainer = Color(0xFFDCECE2))
@Composable fun FlowTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "未知大小"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var n = bytes.toDouble() / 1024
    var i = 0
    while (n >= 1024 && i < units.lastIndex) { n /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", n, units[i])
}
private fun remaining(task: DownloadTask): String {
    if (task.speed <= 0 || task.total <= 0) return "计算剩余时间…"
    val seconds = ((task.total - task.downloaded) / task.speed).coerceAtLeast(0)
    return when { seconds < 60 -> "约 ${seconds} 秒"; seconds < 3600 -> "约 ${seconds / 60} 分钟"; else -> "约 ${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分钟" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DownloadApp(manager: TaskManager, shared: String = "", clearShared: () -> Unit = {}) {
    val tasks by manager.tasks.collectAsStateWithLifecycle()
    val settings by manager.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("tasks") }
    var newTask by rememberSaveable { mutableStateOf(false) }
    var sharedDraft by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var bulk by remember { mutableStateOf(setOf<String>()) }
    var deleting by remember { mutableStateOf<DownloadTask?>(null) }
    var restart by remember { mutableStateOf<DownloadTask?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun begin() { DownloadService.start(context) }
    fun create() {
        newTask = true
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        if (manager.dao.all().any { it.state in TaskManager.WORK_STATES }) begin()
        manager.errors.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(shared) { if (shared.isNotBlank()) { sharedDraft = shared; newTask = true; clearShared() } }
    LaunchedEffect(tasks) { bulk = bulk.intersect(tasks.map { it.id }.toSet()); if (tasks.none { it.id == selectedId }) selectedId = null }
    val selected = tasks.find { it.id == selectedId }
    val visible = tasks.filter { task ->
        (query.isBlank() || task.name.contains(query, true)) && when (filter) {
            "进行中" -> task.state in TaskManager.WORK_STATES
            "已完成" -> task.state == TaskState.COMPLETE
            "已暂停" -> task.state == TaskState.PAUSED
            "失败" -> task.state == TaskState.FAILED
            else -> true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 840.dp
        BackHandler(enabled = !wide && selected != null && screen == "tasks") { selectedId = null }
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (screen == "tasks" && (wide || selected == null)) ExtendedFloatingActionButton(onClick = { create() }, modifier = Modifier.testTag("new-download"), icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("新建下载") })
            },
            bottomBar = {
                if (!wide && (selected == null || screen == "settings")) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = screen == "tasks", onClick = { screen = "tasks" }, icon = { Icon(Icons.Rounded.Downloading, null) }, label = { Text("下载") })
                    NavigationBarItem(selected = screen == "settings", onClick = { screen = "settings" }, icon = { Icon(Icons.Rounded.Tune, null) }, label = { Text("设置") })
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(24.dp))
                    Icon(Icons.Rounded.South, "流速下载", Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(36.dp))
                    NavigationRailItem(selected = screen == "tasks", onClick = { screen = "tasks" }, icon = { Icon(Icons.Rounded.Downloading, null) }, label = { Text("下载") })
                    Spacer(Modifier.height(12.dp))
                    NavigationRailItem(selected = screen == "settings", onClick = { screen = "settings" }, icon = { Icon(Icons.Rounded.Tune, null) }, label = { Text("设置") })
                }
                if (screen == "settings") {
                    SettingsPane(settings, manager::saveSettings, Modifier.weight(1f))
                } else if (!wide && selected != null) {
                    DetailPane(selected, { selectedId = null }, { manager.pause(it.id) }, { begin(); manager.resume(it.id) },
                        { restart = it }, { manager.cancel(it.id) }, { deleting = it }, manager::changeDirectory, Modifier.weight(1f))
                } else {
                    Column(Modifier.weight(if (wide) 0.55f else 1f).fillMaxHeight()) {
                        Column(Modifier.padding(horizontal = 24.dp).padding(top = 24.dp)) {
                            Text("FLOW / 流速", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("下载管理", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(onClick = { manager.pauseAll() }) { Icon(Icons.Rounded.PauseCircleOutline, "全部暂停") }
                                IconButton(onClick = { begin(); manager.resumeAll() }) { Icon(Icons.Rounded.PlayCircleOutline, "全部继续") }
                            }
                            Text("每一个文件，都稳稳抵达。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(20.dp))
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Stat("当前速度", "${formatBytes(tasks.sumOf { it.speed })}/s")
                                    Stat("进行中", tasks.count { it.state in TaskManager.WORK_STATES }.toString())
                                    Stat("已完成", tasks.count { it.state == TaskState.COMPLETE }.toString())
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("搜索文件") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("全部", "进行中", "已完成", "已暂停", "失败").forEach { title -> FilterChip(selected = filter == title, onClick = { filter = title }, label = { Text(title) }) }
                            }
                            if (bulk.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("已选 ${bulk.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { bulk.forEach(manager::pause); bulk = emptySet() }) { Text("暂停") }
                                TextButton(onClick = { begin(); bulk.forEach { manager.resume(it) }; bulk = emptySet() }) { Text("继续") }
                                TextButton(onClick = { bulk.forEach(manager::cancel); bulk = emptySet() }) { Text("取消") }
                                IconButton(onClick = { bulk = emptySet() }) { Icon(Icons.Rounded.Close, "清空选择") }
                            }
                        }
                        if (visible.isEmpty()) {
                            EmptyState(if (tasks.isEmpty()) "从一个链接开始" else "没有匹配的任务", if (tasks.isEmpty()) "粘贴下载链接，或从其他应用分享至流速下载\n支持批量链接与断点续传" else "试试其他关键词或筛选条件", Modifier.weight(1f))
                        } else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visible, key = { it.id }) { task ->
                                TaskCard(task, selectedId == task.id, task.id in bulk, { selectedId = task.id },
                                    { bulk = if (task.id in bulk) bulk - task.id else bulk + task.id },
                                    { if (task.state in TaskManager.WORK_STATES) manager.pause(task.id) else if (task.changed) restart = task else { begin(); manager.resume(task.id) } })
                            }
                        }
                    }
                    if (wide) {
                        VerticalDivider()
                        if (selected == null) EmptyState("文件的每一步，清晰可见", "选择一个任务，查看下载详情", Modifier.weight(0.45f))
                        else DetailPane(selected, { selectedId = null }, { manager.pause(it.id) }, { begin(); manager.resume(it.id) },
                            { restart = it }, { manager.cancel(it.id) }, { deleting = it }, manager::changeDirectory, Modifier.weight(0.45f))
                    }
                }
            }
        }
    }
    if (newTask) NewTaskDialog(settings, sharedDraft, { newTask = false; sharedDraft = "" }) { urls, name, threads, directory, headers ->
        begin(); manager.add(urls, name, threads, directory, headers); newTask = false; sharedDraft = ""
    }
    deleting?.let { task ->
        var file by remember(task.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除任务记录？") }, text = {
            Column { Text(task.name); if (task.outputUri.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(file, { file = it }); Text("同时删除已下载文件") } else Text("将清理该任务的临时文件。") }
        }, confirmButton = { TextButton(onClick = { manager.remove(task.id, file); deleting = null }) { Text("移除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
    restart?.let { task -> AlertDialog(onDismissRequest = { restart = null }, title = { Text("重新下载？") }, text = { Text("将清理已有下载进度，从头下载 ${task.name}。") },
        confirmButton = { TextButton(onClick = { begin(); manager.resume(task.id, true); restart = null }) { Text("重新下载") } }, dismissButton = { TextButton(onClick = { restart = null }) { Text("取消") } }) }
}

@Composable private fun Stat(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(value, fontSize = 21.sp, fontWeight = FontWeight.SemiBold) }
}
@Composable private fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxHeight().fillMaxWidth()) {
        if (maxHeight < 240.dp) {
            Column(Modifier.padding(start = 24.dp, top = 12.dp).fillMaxWidth(0.55f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (title == "从一个链接开始") "粘贴链接或从其他应用分享" else body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(28.dp)) { Icon(Icons.Rounded.South, null, Modifier.padding(26.dp).size(42.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(24.dp)); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp)); Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
@Composable private fun TaskCard(task: DownloadTask, selected: Boolean, checked: Boolean, onClick: () -> Unit, onCheck: () -> Unit, onToggle: () -> Unit) {
    val active = task.state in TaskManager.WORK_STATES
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onCheck() }, modifier = Modifier.size(36.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(fileIcon(task.name), null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(task.state.label + " · " + formatBytes(task.total), style = MaterialTheme.typography.bodySmall,
                        color = if (task.state == TaskState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (task.state != TaskState.COMPLETE) IconButton(onClick = onToggle) { Icon(if (active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (active) "暂停 ${task.name}" else "继续 ${task.name}") }
                else Icon(Icons.Rounded.CheckCircle, "已完成", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
            }
            if (task.state != TaskState.COMPLETE && task.state != TaskState.CANCELLED) {
                Spacer(Modifier.height(12.dp))
                if (task.total > 0) LinearProgressIndicator(progress = { (task.downloaded.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp))
                else if (active) LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatBytes(task.downloaded), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (task.speed > 0) "${formatBytes(task.speed)}/s" else task.state.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.').lowercase()) {
    "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
    "mp4", "mkv", "webm", "mov" -> Icons.Rounded.Movie
    "mp3", "flac", "wav" -> Icons.Rounded.MusicNote
    "jpg", "png", "webp", "jpeg" -> Icons.Rounded.Image
    "apk" -> Icons.Rounded.Android
    else -> Icons.Rounded.InsertDriveFile
}

@Composable private fun DetailPane(task: DownloadTask, back: () -> Unit, pause: (DownloadTask) -> Unit, resume: (DownloadTask) -> Unit,
    restart: (DownloadTask) -> Unit, cancel: (DownloadTask) -> Unit, delete: (DownloadTask) -> Unit, directory: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }; Text("任务详情", style = MaterialTheme.typography.titleLarge) }
        Icon(fileIcon(task.name), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(task.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(task.state.label, color = if (task.state == TaskState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (task.total > 0) {
            LinearProgressIndicator(progress = { (task.downloaded.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${(task.downloaded.toDouble() / task.total * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.headlineMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Stat("已下载", formatBytes(task.downloaded)); Stat("文件大小", formatBytes(task.total)) }
        if (task.state == TaskState.RUNNING) { Text("${formatBytes(task.speed)}/s · ${remaining(task)}", color = MaterialTheme.colorScheme.primary) }
        if (task.message.isNotBlank()) Surface(color = if (task.state == TaskState.FAILED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) { Text(task.message, Modifier.padding(14.dp)) }
        if (task.state == TaskState.COMPLETE) {
            Button(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.outputUri), context.contentResolver.getType(Uri.parse(task.outputUri)) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: Exception) { (context.applicationContext as FlowApp).manager.report("没有可打开此文件的应用，或文件已被移动") }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("打开文件") }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (task.state in TaskManager.WORK_STATES) pause(task) else if (task.changed) restart(task) else resume(task) }, modifier = Modifier.weight(1f)) {
                Text(if (task.state in TaskManager.WORK_STATES) "暂停下载" else if (task.changed) "重新下载" else if (task.state == TaskState.FAILED) "重试" else "继续下载")
            }
            OutlinedButton(onClick = { cancel(task) }) { Text("取消任务") }
        }
        HorizontalDivider()
        Text("下载链接", style = MaterialTheme.typography.labelLarge)
        // Mask query credentials until the user deliberately copies the original link.
        Text(task.url.substringBefore('?').substringBefore('#'), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(task.url)) }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("复制原始链接") }
        Text("请求连接数：${task.connections} · 服务器不支持分段时使用单连接", style = MaterialTheme.typography.bodySmall)
        Text("保存位置", style = MaterialTheme.typography.labelLarge)
        if (task.state != TaskState.COMPLETE) DirectoryPicker(task.directory) { directory(task.id, it) }
        else Text(if (task.directory.isEmpty()) "下载 / 流速下载" else "自选目录", style = MaterialTheme.typography.bodySmall)
        if (task.state != TaskState.COMPLETE) OutlinedButton(onClick = { restart(task) }, modifier = Modifier.fillMaxWidth()) { Text("从头重新下载") }
        TextButton(onClick = { delete(task) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("移除任务记录") }
    }
}

@Composable private fun DirectoryPicker(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            onChange(uri.toString()); error = ""
        } catch (_: SecurityException) { error = "无法获取目录权限，请选择其他目录" }
    }
    Column {
        OutlinedButton(onClick = { launcher.launch(value.takeIf { it.isNotEmpty() }?.let(Uri::parse)) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (value.isEmpty()) "下载 / 流速下载" else "自选目录 · 点击更换", maxLines = 1)
        }
        if (value.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("恢复默认下载目录") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun NewTaskDialog(settings: AppSettings, initial: String, dismiss: () -> Unit,
    submit: (List<String>, String, Int, String, Map<String, String>) -> Unit) {
    var urls by rememberSaveable { mutableStateOf(initial) }
    var name by rememberSaveable { mutableStateOf("") }
    var headers by rememberSaveable { mutableStateOf("") }
    var threads by rememberSaveable { mutableIntStateOf(settings.connections) }
    var directory by rememberSaveable { mutableStateOf(settings.directory) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Rounded.AddLink, null) }, title = { Text("新建下载") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支持 HTTP / HTTPS，批量链接每行一个。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(urls, { urls = it; error = "" }, label = { Text("下载链接") }, placeholder = { Text("https://example.com/file.zip") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { urls = clipboard.getText()?.text.orEmpty() }) { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("粘贴剪贴板") }
            OutlinedTextField(name, { name = it }, label = { Text("文件名（可选）") }, supportingText = { Text("批量任务使用各自链接中的文件名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DirectoryPicker(directory) { directory = it }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置"); Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
            if (advanced) {
                Stepper("单任务连接数", threads, 1..16) { threads = it }
                OutlinedTextField(headers, { headers = it }, label = { Text("请求头（可选）") }, placeholder = { Text("Referer: https://example.com\nAuthorization: Bearer …") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Text("请求头仅保存在本机并加密存储。", style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = {
        try { submit(InputRules.urls(urls), name, threads, directory, InputRules.headers(headers)) }
        catch (e: IllegalArgumentException) { error = e.message ?: "请检查输入" }
    }) { Text("开始下载") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun Stepper(label: String, value: Int, range: IntRange, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { change(value - 1) }, enabled = value > range.first) { Icon(Icons.Rounded.Remove, "减少$label") }
        Text(value.toString(), fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { change(value + 1) }, enabled = value < range.last) { Icon(Icons.Rounded.Add, "增加$label") }
    }
}

@Composable private fun SettingsPane(settings: AppSettings, save: (AppSettings) -> Unit, modifier: Modifier = Modifier) {
    var limit by remember(settings.limitKiB) { mutableStateOf(settings.limitKiB.takeIf { it > 0 }?.toString().orEmpty()) }
    var error by remember { mutableStateOf("") }
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("偏好设置", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("让下载，按你的习惯运行。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSection("网络与任务") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("仅在 Wi-Fi 下下载"); Text("关闭时允许使用移动流量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(settings.wifiOnly, { save(settings.copy(wifiOnly = it)) })
            }
            Stepper("同时下载任务", settings.concurrent, 1..5) { save(settings.copy(concurrent = it)) }
            Stepper("默认连接数", settings.connections, 1..16) { save(settings.copy(connections = it)) }
            OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(6); error = "" }, label = { Text("全局速度上限（KB/s）") }, supportingText = { Text("留空或 0 表示不限速，最高 102400 KB/s") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { val number = limit.toIntOrNull() ?: 0; if (number > 102400) error = "速度上限不能超过 102400 KB/s" else save(settings.copy(limitKiB = number)) }) { Text("应用限速") }
        }
        SettingSection("文件保存") { DirectoryPicker(settings.directory) { save(settings.copy(directory = it)) }; Text("同名文件自动另存，不覆盖已有文件。", style = MaterialTheme.typography.bodySmall) }
        SettingSection("外观") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, title) -> FilterChip(selected = settings.theme == value, onClick = { save(settings.copy(theme = value)) }, label = { Text(title) }) }
            }
        }
        SettingSection("关于流速下载") {
            Text("版本 0.1.0 · HTTP / HTTPS 文件下载")
            Text("下载记录与请求头保存在本机，无账号、无广告、无分析追踪。", style = MaterialTheme.typography.bodySmall)
            Text("分段提速取决于服务器支持。非分段下载暂停后将从头开始。强行停止应用、系统后台限制或达到运行时限后，请打开应用继续任务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("导出文件时需额外临时空间，请确保设备有充足剩余存储。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}
@Composable private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall); content()
        }
    }
}
