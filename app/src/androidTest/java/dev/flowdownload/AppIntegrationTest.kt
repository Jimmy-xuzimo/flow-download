package dev.flowdownload

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ui.activity.application as FlowApp
    private val manager get() = app.manager
    private val payload = ByteArray(2 * 1024 * 1024 + 19) { (it % 251).toByte() }
    private lateinit var server: MockWebServer

    @Before fun prepare() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(payload))
                val (a, b) = range.removePrefix("bytes=").split('-').map(String::toInt)
                val response = MockResponse().setResponseCode(206).setHeader("ETag", "\"fixture-v1\"")
                    .setHeader("Content-Range", "bytes $a-$b/${payload.size}").setBody(Buffer().write(payload, a, b - a + 1))
                if (range != "bytes=0-0") response.throttleBody(4096, 100, TimeUnit.MILLISECONDS)
                return response
            }
        }
        server.start()
        manager.saveSettings(AppSettings())
        runBlocking { manager.dao.all().forEach { manager.remove(it.id, true) } }
        await { manager.dao.all().isEmpty() }
    }
    @After fun cleanup() {
        runBlocking { manager.dao.all().forEach { manager.remove(it.id, true) } }
        await { manager.dao.all().isEmpty() }
        server.shutdown()
    }
    private fun await(block: suspend () -> Boolean) = runBlocking {
        try { withTimeout(120_000) { while (!block()) delay(150) } }
        catch (e: TimeoutCancellationException) {
            val states = manager.dao.all().joinToString { "${it.state}: ${it.downloaded}/${it.total}, ${it.message}" }
            throw AssertionError("Device condition timed out. Tasks: $states", e)
        }
    }
    private fun snapshot(name: String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun invalidInputAndSettingsAreUsable() {
        ui.onNodeWithTag("new-download").performClick()
        ui.onNodeWithText("下载链接").performTextInput("ftp://invalid/file")
        ui.onNodeWithText("开始下载").performClick()
        ui.onNodeWithText("请输入有效的 HTTP/HTTPS 链接；认证请使用请求头").assertExists()
        ui.onNodeWithText("取消", useUnmergedTree = true).performClick()
        ui.onNodeWithText("设置", useUnmergedTree = true).performClick()
        ui.onNodeWithText("偏好设置").assertExists()
        snapshot("settings")
    }
    @Test fun pauseResumeExportAndDeletePreserveFileIntegrity() {
        val fixtureUrl = server.url("/android-fixture.zip").toString()
        ui.activityRule.scenario.onActivity {
            DownloadService.start(it)
            manager.add(listOf(fixtureUrl), "Android 下载验证.zip", 4, "", emptyMap())
        }
        await { manager.dao.all().firstOrNull()?.downloaded?.let { it > 0 } == true }
        val id = runBlocking { manager.dao.all().single().id }
        manager.pause(id)
        await { manager.dao.get(id)?.state.let { it == TaskState.PAUSED || it == TaskState.COMPLETE } }
        val paused = runBlocking { manager.dao.get(id)!! }
        assertEquals("Fixture must remain unfinished long enough to pause", TaskState.PAUSED, paused.state)
        assertTrue(paused.downloaded > 0)
        Thread.sleep(300)
        assertEquals(paused.downloaded, runBlocking { manager.dao.get(id)!!.downloaded })
        snapshot("download-paused")
        ui.activityRule.scenario.onActivity { DownloadService.start(it) }
        manager.resume(id)
        await { manager.dao.get(id)?.state.let { it == TaskState.COMPLETE || it == TaskState.FAILED } }
        val complete = runBlocking { manager.dao.get(id)!! }
        assertEquals(complete.message, TaskState.COMPLETE, complete.state)
        val bytes = app.contentResolver.openInputStream(android.net.Uri.parse(complete.outputUri))!!.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256")
        assertArrayEquals(digest.digest(payload), digest.digest(bytes))
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Android 下载验证.zip").fetchSemanticsNodes().isNotEmpty() }
        snapshot("download-complete")
        manager.remove(id, false)
        await { manager.dao.get(id) == null }
        app.contentResolver.openInputStream(android.net.Uri.parse(complete.outputUri))!!.close()
        app.contentResolver.delete(android.net.Uri.parse(complete.outputUri), null, null)
    }
    @Test fun shareIntentOpensPrefilledDownloadDialog() {
        ui.activityRule.scenario.onActivity { activity ->
            val originalIntent = activity.intent
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,
                Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/shared.zip"))
            // ActivityScenario tracks its launch intent when observing teardown.
            activity.intent = originalIntent
        }
        ui.onNodeWithText("https://example.com/shared.zip").assertExists()
        ui.onNodeWithText("取消", useUnmergedTree = true).performClick()
    }
}
