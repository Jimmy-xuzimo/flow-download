package dev.flowdownload.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class DownloadSpec(val id: String, val url: String, val connections: Int = 4, val headers: Map<String, String> = emptyMap())
data class Slice(val start: Long, val end: Long)
data class Plan(val length: Long, val ranged: Boolean, val etag: String?, val modified: String?, val slices: List<Slice>) {
    val validator: String? get() = etag?.takeUnless { it.startsWith("W/") } ?: modified
}
data class Progress(val downloaded: Long, val total: Long, val bytesPerSecond: Long)
class DownloadFailure(val userMessage: String, val retryable: Boolean = false, val changed: Boolean = false) : IOException(userMessage)

object InputRules {
    private val reserved = setOf("range", "if-range", "accept-encoding", "host", "content-length", "connection")
    fun urls(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.also {
        require(it.isNotEmpty()) { "请输入下载链接" }
        require(it.size <= 100) { "一次最多导入 100 个链接" }
        require(it.all { url -> url.toHttpUrlOrNull()?.let { u -> u.username.isEmpty() && u.password.isEmpty() } == true }) { "请输入有效的 HTTP/HTTPS 链接；认证请使用请求头" }
    }.distinct()
    fun headers(text: String): Map<String, String> = text.lines().filter { it.isNotBlank() }.associate { line ->
        val colon = line.indexOf(':')
        require(colon > 0) { "请求头格式应为 名称: 值" }
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        require(name.lowercase() !in reserved) { "不能手动设置 $name" }
        Headers.Builder().add(name, value).build()
        name to value
    }
    fun fileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.take(120).ifEmpty { "download.bin" }
    }
    fun suggestedName(url: String): String = fileName(url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty())
}

/** One shared limiter for all tasks and connections. No throttle when limit is zero. */
class BandwidthLimiter(private val limit: () -> Long) {
    private val mutex = Mutex()
    private var next = 0L
    suspend fun acquire(bytes: Int) = mutex.withLock {
        val rate = limit()
        if (rate <= 0) { next = 0; return@withLock }
        val now = System.nanoTime()
        val begin = maxOf(now, next)
        val duration = (bytes * 1_000_000_000L / rate).coerceAtLeast(1)
        next = begin + duration
        val wait = (next - now) / 1_000_000
        try { if (wait > 0) delay(wait) } finally { if (!currentCoroutineContext().isActive) next = System.nanoTime() }
    }
}

/** HTTP engine independent of Android. Each slice has a durable, independently resumable file. */
class DownloadEngine(
    private val directory: File,
    private val limiter: BandwidthLimiter = BandwidthLimiter { 0L },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    init { directory.mkdirs() }
    fun part(id: String, index: Int) = File(directory, "$id.$index.part")
    fun ready(id: String) = File(directory, "$id.ready")
    fun clear(id: String) { directory.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() } }

    suspend fun download(
        spec: DownloadSpec,
        previous: Plan? = null,
        onPlan: suspend (Plan) -> Unit = {},
        onProgress: suspend (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(spec.id.matches(Regex("[a-zA-Z0-9-]+")))
        InputRules.urls(spec.url)
        val probed = probe(spec)
        if (previous != null && hasPartial(spec.id, previous)) {
            if (previous.length != probed.length ||
                (previous.etag != null && previous.etag != probed.etag) ||
                (previous.modified != null && previous.modified != probed.modified)) {
                throw DownloadFailure("远端文件已变化，请选择重新下载", changed = true)
            }
        }
        val canResume = previous != null && previous.ranged && probed.ranged &&
            previous.validator != null && previous.validator == probed.validator && previous.length == probed.length
        val plan = if (canResume) previous!! else {
            clear(spec.id)
            val count = if (probed.ranged && probed.length > 0) minOf(spec.connections.coerceIn(1, 16).toLong(), probed.length).toInt() else 1
            val slices = if (probed.length >= 0) (0 until count).map { i ->
                // Divide before multiplying so large files never overflow.
                val chunk = probed.length / count
                Slice(i * chunk, if (i == count - 1) probed.length - 1 else (i + 1) * chunk - 1)
            } else listOf(Slice(0, -1))
            probed.copy(slices = slices)
        }
        plan.slices.forEachIndexed { index, s ->
            val file = part(spec.id, index)
            if (s.end >= 0 && file.length() > s.end - s.start + 1) throw DownloadFailure("临时文件大小异常，请重新下载", changed = true)
        }
        onPlan(plan) // persist metadata before writing bytes
        val downloaded = AtomicLong(plan.slices.indices.sumOf { part(spec.id, it).length() })
        onProgress(Progress(downloaded.get(), plan.length, 0))
        coroutineScope {
            val reporter = launch {
                var last = downloaded.get()
                var time = System.nanoTime()
                while (isActive) {
                    delay(500)
                    val now = System.nanoTime()
                    val current = downloaded.get()
                    onProgress(Progress(current, plan.length, ((current - last) * 1_000_000_000.0 / (now - time)).toLong()))
                    last = current; time = now
                }
            }
            try {
                plan.slices.mapIndexed { index, slice -> async { transfer(spec, plan, index, slice, downloaded) } }.awaitAll()
            } finally { reporter.cancelAndJoin() }
        }
        onProgress(Progress(downloaded.get(), plan.length, 0))
        val output = File(directory, "${spec.id}.assembling")
        output.outputStream().use { out ->
            plan.slices.indices.forEach { index ->
                part(spec.id, index).inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
            out.fd.sync()
        }
        if (plan.length >= 0 && output.length() != plan.length) throw DownloadFailure("文件长度校验失败")
        val destination = ready(spec.id)
        check(output.renameTo(destination)) { "无法完成临时文件写入" }
        destination
    }

    private fun hasPartial(id: String, plan: Plan) = plan.slices.indices.any { part(id, it).length() > 0 }
    private fun request(spec: DownloadSpec): Request.Builder = Request.Builder().url(spec.url).apply {
        spec.headers.forEach { (key, value) -> header(key, value) }
        header("Accept-Encoding", "identity")
    }
    private fun verifyStatus(response: Response) {
        if (!response.isSuccessful) throw DownloadFailure(when (response.code) {
            401, 403 -> "访问被拒绝（${response.code}），请检查链接或认证请求头"
            404, 410 -> "链接不存在或已过期（${response.code}）"
            416 -> "服务器不接受当前分段，请重新下载"
            else -> "服务器返回 HTTP ${response.code}"
        }, response.code == 408 || response.code == 429 || response.code >= 500)
        if (response.header("Content-Encoding")?.let { it != "identity" } == true) throw DownloadFailure("服务器返回了压缩内容，无法安全保存原文件")
    }
    private suspend fun probe(spec: DownloadSpec): Plan = withCall(request(spec).header("Range", "bytes=0-0").build()) { response ->
        if (response.code == 416 && response.header("Content-Range") == "bytes */0") {
            return@withCall Plan(0, false, response.header("ETag"), response.header("Last-Modified"), emptyList())
        }
        verifyStatus(response)
        val ranged = response.code == 206
        val length = if (ranged) {
            val match = RANGE.matchEntire(response.header("Content-Range").orEmpty())
                ?: throw DownloadFailure("服务器分段响应格式错误")
            if (match.groupValues[1] != "0" || match.groupValues[2] != "0") throw DownloadFailure("服务器返回了错误的探测分段")
            match.groupValues[3].toLongOrNull()?.takeIf { it > 0 } ?: throw DownloadFailure("服务器文件大小无效")
        } else response.body?.contentLength() ?: -1
        Plan(length, ranged, response.header("ETag"), response.header("Last-Modified"), emptyList())
    }
    private suspend fun transfer(spec: DownloadSpec, plan: Plan, index: Int, slice: Slice, count: AtomicLong) {
        val file = part(spec.id, index)
        val existing = file.length()
        val expected = if (slice.end >= 0) slice.end - slice.start + 1 else if (plan.length == 0L) 0L else -1L
        if (expected >= 0 && existing == expected) { if (!file.exists()) file.createNewFile(); return }
        val builder = request(spec)
        if (plan.ranged) {
            builder.header("Range", "bytes=${slice.start + existing}-${slice.end}")
            plan.validator?.let { builder.header("If-Range", it) }
        }
        withCall(builder.build()) { response ->
            verifyStatus(response)
            if (plan.ranged) {
                if (response.code != 206) throw DownloadFailure("服务器分段能力或文件已变化，请重新下载", changed = true)
                val range = RANGE.matchEntire(response.header("Content-Range").orEmpty())
                if (range == null || range.groupValues[1].toLongOrNull() != slice.start + existing ||
                    range.groupValues[2].toLongOrNull() != slice.end || range.groupValues[3].toLongOrNull() != plan.length) {
                    throw DownloadFailure("服务器返回了错误的分段范围")
                }
            } else if (response.code != 200) throw DownloadFailure("服务器返回了意外的下载响应")
            if ((plan.etag != null && response.header("ETag") != null && plan.etag != response.header("ETag")) ||
                (plan.modified != null && response.header("Last-Modified") != null && plan.modified != response.header("Last-Modified")))
                throw DownloadFailure("远端文件已变化，请重新下载", changed = true)
            val body = response.body ?: throw DownloadFailure("服务器没有返回文件内容")
            RandomAccessFile(file, "rw").use { output ->
                output.seek(existing)
                var received = existing
                val buffer = ByteArray(32 * 1024)
                try {
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (expected >= 0 && received + n > expected) throw DownloadFailure("分段数据超出声明长度")
                            limiter.acquire(n)
                            output.write(buffer, 0, n)
                            received += n; count.addAndGet(n.toLong())
                        }
                    }
                } finally { output.fd.sync() }
                if (expected >= 0 && received != expected) throw DownloadFailure("连接提前断开，将自动重试", retryable = true)
            }
        }
    }
    private suspend fun <T> withCall(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        var redirected = request
        repeat(11) { attempt ->
            val call = client.newCall(redirected)
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { call.execute().use {
                if (it.code !in setOf(301, 302, 303, 307, 308)) return@coroutineScope block(it)
                if (attempt == 10) throw DownloadFailure("链接重定向次数过多")
                val target = redirected.url.resolve(it.header("Location").orEmpty()) ?: throw DownloadFailure("重定向地址无效")
                if (redirected.url.isHttps && !target.isHttps) throw DownloadFailure("服务器尝试将安全链接降级为 HTTP")
                val sameOrigin = target.scheme == redirected.url.scheme && target.host == redirected.url.host && target.port == redirected.url.port
                redirected = redirected.newBuilder().url(target).apply {
                    if (!sameOrigin) {
                        // Only protocol headers survive a cross-origin redirect. Never leak user cookies or tokens.
                        val keep = setOf("range", "if-range", "accept-encoding")
                        redirected.headers.names().filter { name -> name.lowercase() !in keep }.forEach { name -> removeHeader(name) }
                    }
                }.build()
            } } finally { watcher.cancelAndJoin() }
        }
        throw DownloadFailure("链接重定向次数过多")
    }
    companion object { private val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)") }
}
