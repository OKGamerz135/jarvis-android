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

class SambaNovaProviderTest {
    private class Hit(val method: String, val uri: String, val auth: String?, val body: String)

    private lateinit var server: ServerSocket
    private val hits = CopyOnWriteArrayList<Hit>()
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val key = "sn-FAKEKEYFORTESTING1234567890abcdefgh"

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

    private fun provider() = SambaNovaProvider(
        sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } },
        baseUrl = "http://127.0.0.1:${server.localPort}",
        sleeper = { sleeps += it },
        clock = { java.time.Instant.parse("2026-11-14T10:00:00Z") },
    )

    private val model = "Meta-Llama-3.3-70B-Instruct"
    private fun request() = ChatRequest(model, key, "sys SECRET-PROMPT", listOf(ChatMessage(ChatRole.USER, "my private message")))

    private fun collect(options: CallOptions): LlmException? = try {
        runBlocking { provider().stream(request(), options).toList() }
        null
    } catch (e: LlmException) {
        e
    }

    @Test
    fun streamsDeltaContentToTheDocumentedEndpointWithBearerKeyAndLogsLimits() {
        contentType = "text/event-stream"
        extraHeaders = "x-ratelimit-limit-requests: 20\r\nx-ratelimit-remaining-requests: 19\r\n" +
            "x-ratelimit-limit-requests-day: 20\r\nx-ratelimit-remaining-requests-day: 7\r\n"
        body = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\", sir.\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val out = runBlocking { provider().stream(request(), CallOptions()).toList() }
        assertEquals("Hello, sir.", out.joinToString(""))
        assertEquals(1, hits.size)
        assertEquals("POST", hits[0].method)
        assertEquals("/v1/chat/completions", hits[0].uri)
        assertEquals("Bearer $key", hits[0].auth)
        assertTrue(hits[0].body.contains("\"stream\":true"))
        assertTrue(hits[0].body.contains("sys SECRET-PROMPT"))
        val note = entries.single().message!!
        assertTrue(note, note.contains("per day 20 (left 7)") && note.contains("per minute 20 (left 19)"))
    }

    @Test
    fun theSliderPromptIsInTheBodySentToSambaNova() {
        contentType = "text/event-stream"
        body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
        val prompt = dev.jarvis.assistant.ai.PromptBuilder.build(dev.jarvis.assistant.settings.JarvisSettings(profanity = 4))
        runBlocking { provider().stream(ChatRequest(model, key, prompt, listOf(ChatMessage(ChatRole.USER, "hi"))), CallOptions()).toList() }
        val sent = org.json.JSONObject(hits[0].body).getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals(prompt, sent)
        assertTrue(sent.contains("level 4 of 4 (Unhinged)"))
    }

    @Test
    fun dailyQuotaIsTypedWithTheReportedResetAndNeverRetried() {
        status = 429
        body = """{"error":{"message":"rate limit","type":"x","code":"insufficient_quota"}}"""
        extraHeaders = "x-ratelimit-remaining-requests-day: 0\r\nx-ratelimit-reset-requests-day: 5400\r\n"
        val e = collect(CallOptions(maxRetries = 2))!!
        val q = e.error as LlmError.QuotaExhausted
        assertEquals(java.time.Instant.parse("2026-11-14T11:30:00Z"), q.resetAt)
        assertEquals(ResetSource.REPORTED, q.resetSource)
        assertEquals(1, hits.size)
        assertEquals(model, e.context!!.model)
        assertTrue(e.context!!.endpoint.endsWith("/v1/chat/completions"))
    }

    @Test
    fun perMinuteLimitUnclear429AndAuthErrorsAreNeverRetried() {
        status = 429
        body = """{"error":{"message":"slow down","type":"x","code":"insufficient_quota"}}"""
        extraHeaders = "x-ratelimit-remaining-requests: 0\r\nx-ratelimit-reset-requests: 9\r\nx-ratelimit-remaining-requests-day: 5\r\n"
        assertEquals(LlmError.RateLimited(9), collect(CallOptions(maxRetries = 2))!!.error)
        assertEquals(1, hits.size)

        hits.clear(); extraHeaders = ""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.Unclear429)
        assertEquals(1, hits.size)

        hits.clear()
        status = 401
        body = """{"error":{"message":"bad key","type":"x","code":"invalid_authentication"}}"""
        assertEquals(LlmError.InvalidKey, collect(CallOptions(maxRetries = 2))!!.error)
        assertEquals(1, hits.size)
    }

    @Test
    fun overloadedIsRetriedWithBackoffUpToTheLimit() {
        status = 503
        body = """{"error":{"message":"maintenance","type":"x","code":"maintenance"}}"""
        val e = collect(CallOptions(maxRetries = 2))!!
        assertTrue(e.error is LlmError.Overloaded)
        assertEquals(3, hits.size)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun keyIsNeverInTheUrlLogsOrErrors() {
        status = 503
        body = """{"error":{"message":"bad $key for me@example.com","type":"x","code":"maintenance"}}"""
        val e = collect(CallOptions())!!
        assertFalse(hits[0].uri.contains("sn-"))
        val everything = entries.joinToString { it.toString() } + e.context + FailureFormatter.details(e.context)
        assertFalse(everything, everything.contains(key))
        assertFalse(everything, everything.contains("me@example.com"))
        assertFalse(everything, everything.contains("SECRET-PROMPT"))
        assertFalse(everything, everything.contains("my private message"))
    }

    @Test
    fun noTextIsReportedAsBlockedNotSuccess() {
        contentType = "text/event-stream"
        body = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: [DONE]\n\n"
        assertTrue(collect(CallOptions())!!.error is LlmError.Blocked)
    }

    @Test
    fun availabilityCheckIsOneGetOfTheModelListAndUsesNoGenerationQuota() {
        body = """{"data":[{"id":"$model"}]}"""
        val info = runBlocking { provider().checkModel(key, model) }
        assertTrue(info.canGenerate)
        assertEquals("GET", hits.single().method)
        assertEquals("/v1/models", hits.single().uri)
        assertFalse(entries.single().kind.usesGenerationQuota)
        body = """{"data":[{"id":"other"}]}"""
        try {
            runBlocking { provider().checkModel(key, model) }
            throw AssertionError("expected exception")
        } catch (e: LlmException) {
            assertTrue(e.error is LlmError.ModelNotFound)
        }
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
        assertEquals(dev.jarvis.assistant.settings.AiProvider.SAMBANOVA.label, SambaNovaProvider.NAME)
        assertTrue(Providers.forProvider(dev.jarvis.assistant.settings.AiProvider.SAMBANOVA) is SambaNovaProvider)
        assertTrue(Providers.diagnosticsFor(dev.jarvis.assistant.settings.AiProvider.SAMBANOVA) is SambaNovaProvider)
        assertEquals("Meta-Llama-3.3-70B-Instruct", dev.jarvis.assistant.settings.AiProvider.SAMBANOVA.defaultModel)
    }
}
