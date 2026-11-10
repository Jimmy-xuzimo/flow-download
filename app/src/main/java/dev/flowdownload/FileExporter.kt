package dev.flowdownload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*

class FileExporter(private val context: Context, private val dao: TaskDao) {
    suspend fun export(task: DownloadTask, source: File): String = withContext(Dispatchers.IO) {
        if (task.pendingUri.isNotEmpty()) discard(task.pendingUri)
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(task.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val mediaStore = task.directory.isEmpty()
        val target = if (mediaStore) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, task.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/流速下载")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }) ?: throw IOException("无法在下载目录创建文件")
        } else {
            val directory = DocumentFile.fromTreeUri(context, Uri.parse(task.directory)) ?: throw IOException("保存目录不可用，请重新选择")
            if (!directory.canWrite()) throw IOException("保存目录权限已失效，请重新选择")
            var name = task.name
            var count = 1
            while (directory.findFile(name) != null) {
                val dot = task.name.lastIndexOf('.').takeIf { it > 0 } ?: task.name.length
                name = task.name.take(dot) + " (${count++})" + task.name.substring(dot)
            }
            directory.createFile(mime, name)?.uri ?: throw IOException("无法在所选目录创建文件")
        }
        var committed = false
        try {
            dao.pending(task.id, target.toString())
            resolver.openOutputStream(target, "w")?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    output.flush()
                }
            } ?: throw IOException("无法写入目标文件")
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if (mediaStore) resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                dao.get(task.id)?.let { dao.update(it.copy(state = TaskState.COMPLETE, speed = 0, message = "", outputUri = target.toString(), pendingUri = "", downloaded = source.length(), total = source.length())) }
                committed = true
            }
            target.toString()
        } catch (e: Exception) {
            if (!committed) withContext(NonCancellable) { runCatching { discard(target.toString()) }; dao.pending(task.id, "") }
            throw e
        }
    }
    fun discard(uri: String) {
        if (uri.isNotEmpty()) {
            val parsed = Uri.parse(uri)
            if (parsed.authority == MediaStore.AUTHORITY) context.contentResolver.delete(parsed, null, null)
            else if (DocumentFile.fromSingleUri(context, parsed)?.delete() != true) throw IOException("无法删除文件，请通过文件管理器处理")
        }
    }
}
