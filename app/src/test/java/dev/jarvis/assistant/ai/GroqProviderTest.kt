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

class GroqProviderTest {
    private class Hit(val method: String, val uri: String, val auth: String?, val body: String)

    private lateinit var server: ServerSocket
    private val hits = CopyOnWriteArrayList<Hit>()
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val key = "gsk_FAKEKEYFORTESTING1234567890abcdefgh"

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

    private fun provider() = GroqProvider(
        sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } },
        baseUrl = "http://127.0.0.1:${server.localPort}",
        sleeper = { sleeps += it },
    )

    private fun request() = ChatRequest("openai/gpt-oss-120b", key, "sys SECRET-PROMPT", listOf(ChatMessage(ChatRole.USER, "my private message")))

    private fun overloaded() {
        status = 503
        body = """{"error":{"message":"Service unavailable","type":"internal_server_error","code":"service_unavailable"}}"""
    }

    private fun collect(options: CallOptions): LlmException? = try {
        runBlocking { provider().stream(request(), options).toList() }
        null
    } catch (e: LlmException) {
        e
    }

    @Test
    fun persistent503MakesThreeRequestsWithBackoff() {
        overloaded()
        val e = collect(CallOptions(maxRetries = 2))!!
        assertTrue(e.error is LlmError.Overloaded)
        assertEquals(3, hits.size)
        assertTrue(sleeps[0] in 2000L..2500L)
        assertTrue(sleeps[1] in 4000L..4500L)
        assertEquals("openai/gpt-oss-120b", e.context!!.model)
        assertTrue(e.context!!.endpoint.endsWith("/openai/v1/chat/completions"))
    }

    @Test
    fun quotaRateLimitAndAuthErrorsAreNeverRetried() {
        status = 429
        body = """{"error":{"message":"on requests per day (RPD): Limit 1000. Please try again in 5m.","type":"requests","code":"rate_limit_exceeded"}}"""
        extraHeaders = "retry-after: 300\r\n"
        val q = collect(CallOptions(maxRetries = 2))!!
        assertTrue(q.error is LlmError.QuotaExhausted)
        assertEquals(1, hits.size)

        hits.clear()
        body = """{"error":{"message":"on tokens per minute (TPM): Limit 8000. Please try again in 2s.","type":"tokens","code":"rate_limit_exceeded"}}"""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.RateLimited)
        assertEquals(1, hits.size)

        hits.clear()
        status = 401
        extraHeaders = ""
        body = """{"error":{"message":"Invalid API Key","type":"invalid_request_error","code":"invalid_api_key"}}"""
        assertEquals(LlmError.InvalidKey, collect(CallOptions(maxRetries = 2))!!.error)
        assertEquals(1, hits.size)
    }

    @Test
    fun keyIsOnlyInTheAuthorizationHeaderAndNeverLogged() {
        overloaded()
        body = """{"error":{"message":"bad $key for me@example.com","type":"x","code":"service_unavailable"}}"""
        val e = collect(CallOptions())!!
        assertEquals("Bearer $key", hits[0].auth)
        assertFalse(hits[0].uri.contains("gsk_"))
        val everything = entries.joinToString { it.toString() } + e.context + FailureFormatter.details(e.context)
        assertFalse(everything, everything.contains(key))
        assertFalse(everything, everything.contains("me@example.com"))
        assertFalse(everything, everything.contains("SECRET-PROMPT"))
        assertFalse(everything, everything.contains("my private message"))
    }

    @Test
    fun availabilityCheckIsOneGetOfTheModelList() {
        body = """{"data":[{"id":"openai/gpt-oss-120b","active":true}]}"""
        val info = runBlocking { provider().checkModel(key, "openai/gpt-oss-120b") }
        assertTrue(info.canGenerate)
        assertEquals(1, hits.size)
        assertEquals("GET", hits[0].method)
        assertTrue(hits[0].uri.endsWith("/openai/v1/models"))
        assertFalse(entries.single().kind.usesGenerationQuota)
        assertEquals(CallKind.AVAILABILITY, entries.single().kind)
    }

    @Test
    fun missingModelIsReportedAsNotFound() {
        body = """{"data":[{"id":"other","active":true}]}"""
        try {
            runBlocking { provider().checkModel(key, "openai/gpt-oss-120b") }
            throw AssertionError("expected exception")
        } catch (e: LlmException) {
            assertTrue(e.error is LlmError.ModelNotFound)
        }
    }

    @Test
    fun streamsDeltaContentOnlyAndLogsLimits() {
        contentType = "text/event-stream"
        extraHeaders = "x-ratelimit-limit-requests: 1000\r\nx-ratelimit-remaining-requests: 999\r\n" +
            "x-ratelimit-limit-tokens: 8000\r\nx-ratelimit-remaining-tokens: 7900\r\n"
        body = "data: {\"choices\":[{\"delta\":{\"reasoning\":\"thinking\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\", sir.\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val out = runBlocking { provider().stream(request(), CallOptions()).toList() }
        assertEquals("Hello, sir.", out.joinToString(""))
        assertTrue(hits[0].body.contains("\"reasoning_effort\":\"low\""))
        val note = entries.single().message!!
        assertTrue(note, note.contains("requests limit 1000") && note.contains("tokens limit 8000"))
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
    fun providerNameMatchesTheSettingsLabel() {
        assertEquals(dev.jarvis.assistant.settings.AiProvider.GROQ.label, GroqProvider.NAME)
    }
}
