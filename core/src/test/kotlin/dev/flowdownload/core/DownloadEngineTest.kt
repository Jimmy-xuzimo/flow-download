package dev.flowdownload.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*

class DownloadEngineTest {
    private lateinit var directory: File
    private lateinit var server: MockWebServer
    private val data = ByteArray(512 * 1024 + 13) { ((it * 31 + 17) % 251).toByte() }
    private val requests = CopyOnWriteArrayList<String>()
    private var ranged = true
    private var chunked = false
    private var slow = false
    private var etag = "\"version-1\""
    private var badRange = false
    private var errorCode = 0

    @Before fun setup() {
        directory = Files.createTempDirectory("flow-engine").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/redirect") return MockResponse().setResponseCode(302).setHeader("Location", "/file")
                if (errorCode != 0) return MockResponse().setResponseCode(errorCode)
                val range = request.getHeader("Range")
                if (range != null) requests += range
                val response = MockResponse().setHeader("ETag", etag)
                if (ranged && range != null) {
                    val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                    response.setResponseCode(206).setHeader("Content-Range", "bytes ${if (badRange) start + 1 else start}-$end/${data.size}")
                        .setBody(Buffer().write(data, start, end - start + 1))
                } else if (chunked) response.setChunkedBody(Buffer().write(data), 4096)
                else response.setBody(Buffer().write(data))
                if (slow && range != "bytes=0-0") response.throttleBody(4096, 20, TimeUnit.MILLISECONDS)
                return response
            }
        }
        server.start()
    }
    @After fun teardown() { server.shutdown(); directory.deleteRecursively() }
    private fun spec(connections: Int = 4, path: String = "/file") = DownloadSpec("test", server.url(path).toString(), connections)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
    private fun assertContent(file: File) { assertEquals(data.size.toLong(), file.length()); assertEquals(hash(data), hash(file.readBytes())) }

    @Test fun parallelDownloadMatchesSha256() = runBlocking {
        var plan: Plan? = null
        val result = DownloadEngine(directory).download(spec(), onPlan = { plan = it })
        assertContent(result)
        assertEquals(4, plan!!.slices.size)
        assertEquals(0L, plan!!.slices.first().start)
        assertEquals(data.size - 1L, plan!!.slices.last().end)
        assertEquals(data.size.toLong(), plan!!.slices.sumOf { it.end - it.start + 1 })
        assertEquals(5, requests.size)
    }
    @Test fun nonRangeServerFallsBackToOneConnection() = runBlocking {
        ranged = false
        var plan: Plan? = null
        assertContent(DownloadEngine(directory).download(spec(), onPlan = { plan = it }))
        assertFalse(plan!!.ranged)
        assertEquals(1, plan!!.slices.size)
    }
    @Test fun unknownLengthChunkedBodyIsSaved() = runBlocking {
        ranged = false; chunked = true
        var total = 0L
        assertContent(DownloadEngine(directory).download(spec(), onProgress = { total = it.total }))
        assertEquals(-1L, total)
    }
    @Test fun interruptedSlicesResumeWithoutDuplicatingBytes() = runBlocking {
        slow = true
        val engine = DownloadEngine(directory)
        var plan: Plan? = null
        val worker = launch(start = CoroutineStart.LAZY) download@ {
            engine.download(spec(2), onPlan = { plan = it }, onProgress = { if (it.downloaded > 0) this@download.cancel("pause") })
        }
        worker.start(); worker.join()
        assertTrue(worker.isCancelled)
        val saved = plan!!.slices.indices.sumOf { engine.part("test", it).length() }
        assertTrue(saved > 0 && saved < data.size)
        requests.clear(); slow = false
        assertContent(engine.download(spec(2), plan))
        assertTrue(requests.filter { it != "bytes=0-0" }.any { range ->
            val start = range.removePrefix("bytes=").substringBefore('-').toLong()
            plan!!.slices.none { it.start == start }
        })
    }
    @Test fun changedValidatorNeverMergesOldData() = runBlocking {
        val engine = DownloadEngine(directory)
        val old = Plan(data.size.toLong(), true, "\"old\"", null, listOf(Slice(0, data.size - 1L)))
        engine.part("test", 0).writeBytes(byteArrayOf(1, 2, 3))
        try { engine.download(spec(), old); fail("Expected changed resource") }
        catch (e: DownloadFailure) { assertTrue(e.changed); assertFalse(e.retryable) }
        assertEquals(3L, engine.part("test", 0).length())
    }
    @Test fun resumeWithoutValidatorRestartsSafely() = runBlocking {
        val engine = DownloadEngine(directory)
        val old = Plan(data.size.toLong(), true, null, null, listOf(Slice(0, data.size - 1L)))
        engine.part("test", 0).writeBytes(ByteArray(17) { 99 })
        assertContent(engine.download(spec(), old))
    }
    @Test fun incorrectContentRangeIsRejected() = runBlocking {
        badRange = true
        try { DownloadEngine(directory).download(spec()); fail("Expected malformed range") }
        catch (e: DownloadFailure) { assertFalse(e.retryable) }
        assertFalse(File(directory, "test.ready").exists())
    }
    @Test fun authenticationFailureDoesNotRetry() = runBlocking {
        errorCode = 403
        try { DownloadEngine(directory).download(spec()); fail("Expected denied") }
        catch (e: DownloadFailure) { assertFalse(e.retryable); assertTrue(e.userMessage.contains("403")) }
    }
    @Test fun temporaryServerFailureCanRetry() = runBlocking {
        errorCode = 503
        try { DownloadEngine(directory).download(spec()); fail("Expected unavailable") }
        catch (e: DownloadFailure) { assertTrue(e.retryable) }
    }
    @Test fun expiredLinkIsReported() = runBlocking {
        errorCode = 410
        try { DownloadEngine(directory).download(spec()); fail("Expected expired") }
        catch (e: DownloadFailure) { assertFalse(e.retryable); assertTrue(e.userMessage.contains("过期")) }
    }
    @Test fun redirectsKeepDownloadWorking() = runBlocking { assertContent(DownloadEngine(directory).download(spec(path = "/redirect"))) }
    @Test fun completedSliceIsNotRequestedAgain() = runBlocking {
        val engine = DownloadEngine(directory)
        val plan = Plan(data.size.toLong(), true, etag, null, listOf(Slice(0, data.size - 1L)))
        engine.part("test", 0).writeBytes(data)
        assertContent(engine.download(spec(1), plan))
        assertEquals(listOf("bytes=0-0"), requests.toList())
    }
    @Test fun largeFilePlanUsesLongOffsets() = runBlocking {
        val length = 5L * 1024 * 1024 * 1024 + 19
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(206).setHeader("ETag", etag)
                .setHeader("Content-Range", "bytes 0-0/$length").setBody("x")
        }
        try {
            DownloadEngine(directory).download(spec(16), onPlan = { p ->
                assertEquals(length, p.length)
                assertEquals(length, p.slices.sumOf { it.end - it.start + 1 })
                assertTrue(p.slices.last().start > Int.MAX_VALUE)
                p.slices.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
                throw CancellationException("plan inspected")
            })
            fail("Expected inspection stop")
        } catch (_: CancellationException) { }
    }
    @Test fun globalLimiterAccountsForAllConnections() = runBlocking {
        val limiter = BandwidthLimiter { 64 * 1024L }
        val start = System.nanoTime()
        coroutineScope { repeat(4) { launch { limiter.acquire(4096) } } }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 230)
    }
    @Test fun emptyFileWorks() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("") }
        assertEquals(0L, DownloadEngine(directory).download(spec()).length())
    }
    @Test fun emptyFileWithUnsatisfiableProbeWorks() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */0")
        }
        assertEquals(0L, DownloadEngine(directory).download(spec()).length())
    }
    @Test fun customAuthenticationHeaderIsSent() = runBlocking {
        val headers = CopyOnWriteArrayList<String?>()
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse { headers += request.getHeader("Authorization"); return original.dispatch(request) } }
        assertContent(DownloadEngine(directory).download(spec().copy(headers = mapOf("Authorization" to "Bearer secret"))))
        assertTrue(headers.all { it == "Bearer secret" })
    }
    @Test fun crossOriginRedirectDoesNotLeakCustomCredentials() = runBlocking {
        val redirect = MockWebServer()
        redirect.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302).setHeader("Location", server.url("/file"))
        }
        redirect.start()
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen += request; return original.dispatch(request) }
        }
        try {
            assertContent(DownloadEngine(directory).download(spec().copy(url = redirect.url("/redirect").toString(), headers = mapOf("Authorization" to "secret", "Cookie" to "secret", "X-Api-Key" to "secret"))))
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it.getHeader("Authorization") == null && it.getHeader("Cookie") == null && it.getHeader("X-Api-Key") == null })
        } finally { redirect.shutdown() }
    }
    @Test fun serverLosingRangeSupportCannotCorruptParts() = runBlocking {
        try {
            DownloadEngine(directory).download(spec(), onPlan = { ranged = false })
            fail("Expected range capability change")
        } catch (e: DownloadFailure) { assertTrue(e.changed) }
        assertFalse(File(directory, "test.ready").exists())
    }
    @Test fun changedEtagDuringTransferIsRejected() = runBlocking {
        try {
            DownloadEngine(directory).download(spec(), onPlan = { etag = "\"new\"" })
            fail("Expected validator mismatch")
        } catch (e: DownloadFailure) { assertTrue(e.changed) }
    }
    @Test fun clearRemovesOnlyRequestedTaskFiles() {
        val engine = DownloadEngine(directory)
        engine.part("test", 0).writeText("a"); engine.part("other", 0).writeText("b")
        engine.clear("test")
        assertFalse(engine.part("test", 0).exists()); assertTrue(engine.part("other", 0).exists())
    }
}

class InputRulesTest {
    @Test fun batchesTrimAndDeduplicate() { assertEquals(listOf("https://a.test/a", "http://b.test/b"), InputRules.urls(" https://a.test/a\n\nhttp://b.test/b\nhttps://a.test/a ")) }
    @Test(expected = IllegalArgumentException::class) fun rejectFileUrls() { InputRules.urls("file:///etc/passwd") }
    @Test(expected = IllegalArgumentException::class) fun rejectEmbeddedPasswords() { InputRules.urls("https://user:pass@example.com/file") }
    @Test(expected = IllegalArgumentException::class) fun rejectRangeOverride() { InputRules.headers("Range: bytes=0-1") }
    @Test(expected = IllegalArgumentException::class) fun rejectMalformedHeader() { InputRules.headers("Bad header") }
    @Test fun authorizationValuesMayContainColons() { assertEquals("Bearer a:b", InputRules.headers("Authorization: Bearer a:b")["Authorization"]) }
    @Test fun filenamesCannotEscapeDirectory() { assertEquals("_.._a_b.zip", InputRules.fileName("../..\u002fa:b.zip")); assertEquals("download.bin", InputRules.fileName("..")) }
}
