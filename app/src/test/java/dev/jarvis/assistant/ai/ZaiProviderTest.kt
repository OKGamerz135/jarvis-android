package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

class ZaiProviderTest {
    private class Hit(val method: String, val uri: String, val auth: String?, val body: String)

    private lateinit var server: ServerSocket
    private val hits = CopyOnWriteArrayList<Hit>()
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val key = "zai-FAKEKEYFORTESTING1234567890abcdefgh"

    @Volatile private var status = 200
    @Volatile private var body = ""
    @Volatile private var contentType = "application/json"
    @Volatile private var extraHeaders = ""

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) { break }
                try {
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) {
                            val c = input.read()
                            if (c < 0 || c == '\n'.code) break
                            if (c != '\r'.code) sb.append(c.toChar())
                        }
                        return sb.toString()
                    }
                    val first = line().split(" ")
                    var length = 0
                    var auth: String? = null
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        val name = h.substringBefore(":").lowercase()
                        val value = h.substringAfter(":").trim()
                        if (name == "content-length") length = value.toInt()
                        if (name == "authorization") auth = value
                    }
                    val sent = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(sent, read, length - read); if (n < 0) break; read += n }
                    hits += Hit(first[0], first[1], auth, String(sent, 0, read))
                    val bytes = body.toByteArray()
                    val head = "HTTP/1.1 $status X\r\nContent-Type: $contentType\r\n$extraHeaders" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                } catch (e: Exception) {
                    // a closed or timed-out client is fine for a test server
                } finally {
                    socket.close()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun stop() = server.close()

    private fun provider() = ZaiProvider(
        sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } },
        baseUrl = "http://127.0.0.1:${server.localPort}",
        sleeper = { sleeps += it },
    )

    private val model = "glm-4.7-flash"
    private fun request(m: String = model) = ChatRequest(m, key, "sys SECRET-PROMPT", listOf(ChatMessage(ChatRole.USER, "my private message")))

    private fun collect(options: CallOptions, m: String = model): LlmException? = try {
        runBlocking { provider().stream(request(m), options).toList() }
        null
    } catch (e: LlmException) {
        e
    }

    @Test
    fun streamsDeltaContentToTheDocumentedEndpointWithBearerKey() {
        contentType = "text/event-stream"
        body = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\", sir.\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val out = runBlocking { provider().stream(request(), CallOptions()).toList() }
        assertEquals("Hello, sir.", out.joinToString(""))
        assertEquals(1, hits.size)
        assertEquals("POST", hits[0].method)
        assertEquals("/api/paas/v4/chat/completions", hits[0].uri)
        assertEquals("Bearer $key", hits[0].auth)
        assertTrue(hits[0].body.contains("\"stream\":true"))
        assertTrue(hits[0].body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(hits[0].body.contains("sys SECRET-PROMPT"))
    }

    @Test
    fun theSliderPromptAndReminderReachZai() {
        contentType = "text/event-stream"
        body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
        val s = dev.jarvis.assistant.settings.JarvisSettings(profanity = 4)
        val prompt = PromptBuilder.build(s)
        val msg = "hi\n\n" + PromptBuilder.styleReminder(s)
        runBlocking { provider().stream(ChatRequest(model, key, prompt, listOf(ChatMessage(ChatRole.USER, msg))), CallOptions()).toList() }
        val messages = org.json.JSONObject(hits[0].body).getJSONArray("messages")
        assertEquals(prompt, messages.getJSONObject(0).getString("content"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("level 4 (Unhinged)"))
    }

    @Test
    fun aPaidModelIsRefusedBeforeAnyRequestIsMade() {
        for (paid in listOf("glm-5", "glm-4.7", "")) {
            val e = collect(CallOptions(), paid)!!
            assertTrue(paid, e.error is LlmError.Unsupported)
        }
        assertEquals(0, hits.size)
        try {
            runBlocking { provider().checkModel(key, "glm-5") }
            throw AssertionError("expected exception")
        } catch (e: LlmException) {
            assertTrue(e.error is LlmError.Unsupported)
        }
        try {
            runBlocking { provider().generateOnce(request("glm-5")) }
            throw AssertionError("expected exception")
        } catch (e: LlmException) {
            assertTrue(e.error is LlmError.Unsupported)
        }
        assertEquals(0, hits.size)
    }

    @Test
    fun checkingAFreeModelSendsNothing() {
        val info = runBlocking { provider().checkModel(key, "glm-4.5-flash") }
        assertTrue(info.canGenerate)
        assertEquals(0, hits.size)
    }

    @Test
    fun insufficientBalanceIsReportedAsBillingRequiredAndNeverRetried() {
        status = 429
        body = """{"error":{"code":"1113","message":"Insufficient balance or no resource package."}}"""
        val e = collect(CallOptions(maxRetries = 2))!!
        assertTrue(e.error is LlmError.BillingRequired)
        assertEquals(1, hits.size)
        assertEquals("1113", e.context!!.apiStatus)
        assertEquals(model, e.context!!.model)
        assertTrue(e.context!!.endpoint.endsWith("/api/paas/v4/chat/completions"))
    }

    @Test
    fun rateLimitUnclear429AndAuthErrorsAreNeverRetried() {
        status = 429
        body = """{"error":{"code":"1302","message":"too fast"}}"""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.RateLimited)
        assertEquals(1, hits.size)
        hits.clear()
        body = """{"error":{"code":"7","message":"?"}}"""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.Unclear429)
        assertEquals(1, hits.size)
        hits.clear()
        status = 401
        body = """{"error":{"code":"1000","message":"auth failed"}}"""
        assertEquals(LlmError.InvalidKey, collect(CallOptions(maxRetries = 2))!!.error)
        assertEquals(1, hits.size)
    }

    @Test
    fun overloadedIsRetriedWithBackoffUpToTheLimit() {
        status = 429
        body = """{"error":{"code":"1305","message":"overloaded"}}"""
        val e = collect(CallOptions(maxRetries = 2))!!
        assertTrue(e.error is LlmError.Overloaded)
        assertEquals(3, hits.size)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun keyIsNeverInTheUrlLogsOrErrors() {
        status = 500
        body = """{"error":{"code":"1234","message":"bad $key for me@example.com"}}"""
        val e = collect(CallOptions())!!
        assertFalse(hits[0].uri.contains("zai-"))
        val everything = entries.joinToString { it.toString() } + e.context + FailureFormatter.details(e.context)
        assertFalse(everything, everything.contains(key))
        assertFalse(everything, everything.contains("me@example.com"))
        assertFalse(everything, everything.contains("SECRET-PROMPT"))
        assertFalse(everything, everything.contains("my private message"))
    }

    @Test
    fun noTextIsBlockedNotSuccess() {
        contentType = "text/event-stream"
        body = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: [DONE]\n\n"
        assertTrue(collect(CallOptions())!!.error is LlmError.Blocked)
    }

    @Test
    fun nonStreamedGenerationIsOnePost() {
        body = """{"choices":[{"message":{"content":"ok"}}]}"""
        assertEquals("ok", runBlocking { provider().generateOnce(request()) })
        assertEquals(1, hits.size)
        assertTrue(hits[0].body.contains("\"stream\":false"))
        assertEquals(CallKind.GENERATE_ONCE, entries.single().kind)
    }

    @Test
    fun providerNameMatchesTheSettingsLabelAndIsWiredIn() {
        assertEquals(dev.jarvis.assistant.settings.AiProvider.ZAI.label, ZaiProvider.NAME)
        assertTrue(Providers.forProvider(dev.jarvis.assistant.settings.AiProvider.ZAI) is ZaiProvider)
        assertTrue(Providers.diagnosticsFor(dev.jarvis.assistant.settings.AiProvider.ZAI) is ZaiProvider)
    }
}
