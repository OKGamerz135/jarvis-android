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

class MistralProviderTest {
    private class Hit(val method: String, val uri: String, val auth: String?, val body: String)

    private lateinit var server: ServerSocket
    private val hits = CopyOnWriteArrayList<Hit>()
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val key = "mistral-FAKEKEYFORTESTING1234567890abcdefgh"

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

    private fun provider() = MistralProvider(
        sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } },
        baseUrl = "http://127.0.0.1:${server.localPort}",
        sleeper = { sleeps += it },
    )

    private val model = "mistral-small-latest"
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
        body = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n" +
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
    }

    @Test
    fun theSliderPromptAndReminderReachMistral() {
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
    fun theMemoryBlockInTheSystemPromptIsSentUnchanged() {
        contentType = "text/event-stream"
        body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
        val system = "SYSTEM\n\nThings you remember about the user:\n- Favourite colour is blue\n\nMemory status: SAVED"
        runBlocking { provider().stream(ChatRequest(model, key, system, listOf(ChatMessage(ChatRole.USER, "hi"))), CallOptions()).toList() }
        assertEquals(system, org.json.JSONObject(hits[0].body).getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun checkModelUsesTheModelListAndFindsTheModel() {
        body = """{"object":"list","data":[{"id":"mistral-small-latest"},{"id":"open-mistral-nemo"}]}"""
        val info = runBlocking { provider().checkModel(key, "mistral-small-latest") }
        assertTrue(info.canGenerate)
        assertEquals("GET", hits[0].method)
        assertEquals("/v1/models", hits[0].uri)
        assertEquals("Bearer $key", hits[0].auth)
    }

    @Test
    fun aMissingModelIsReported() {
        body = """{"object":"list","data":[{"id":"open-mistral-nemo"}]}"""
        try {
            runBlocking { provider().checkModel(key, "nope") }
            throw AssertionError("expected exception")
        } catch (e: LlmException) {
            assertTrue(e.error is LlmError.ModelNotFound)
        }
    }

    @Test
    fun rateLimitUnclear429AndAuthErrorsAreNeverRetried() {
        status = 429
        body = """{"object":"error","message":"Rate limit exceeded","type":"rate_limited"}"""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.RateLimited)
        assertEquals(1, hits.size)
        hits.clear()
        body = """{"message":"???"}"""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.Unclear429)
        assertEquals(1, hits.size)
        hits.clear()
        status = 401
        body = """{"message":"Unauthorized","request_id":"x"}"""
        assertEquals(LlmError.InvalidKey, collect(CallOptions(maxRetries = 2))!!.error)
        assertEquals(1, hits.size)
    }

    @Test
    fun capacityIs429ButTreatedAsOverloadAndRetried() {
        status = 429
        body = """{"object":"error","message":"Service tier capacity exceeded for this model.","type":"service_tier_capacity_exceeded"}"""
        val e = collect(CallOptions(maxRetries = 2))!!
        assertTrue(e.error is LlmError.Overloaded)
        assertEquals(3, hits.size)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun keyIsNeverInTheUrlLogsOrErrors() {
        status = 500
        body = """{"message":"bad $key for me@example.com","type":"internal"}"""
        val e = collect(CallOptions())!!
        assertFalse(hits[0].uri.contains("mistral-FAKE"))
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
        val p = dev.jarvis.assistant.settings.AiProvider.MISTRAL
        assertEquals(p.label, MistralProvider.NAME)
        assertTrue(Providers.forProvider(p) is MistralProvider)
        assertTrue(Providers.diagnosticsFor(p) is MistralProvider)
    }

    @Test
    fun settingsKnowTheMistralModelAndEveryProviderStillResolves() {
        val s = dev.jarvis.assistant.settings.JarvisSettings()
        assertEquals("mistral-small-latest", s.modelOf(dev.jarvis.assistant.settings.AiProvider.MISTRAL))
        assertEquals("x", s.withModel(dev.jarvis.assistant.settings.AiProvider.MISTRAL, "x").modelOf(dev.jarvis.assistant.settings.AiProvider.MISTRAL))
        for (p in dev.jarvis.assistant.settings.AiProvider.entries) {
            assertTrue(p.name, Providers.forProvider(p) != null)
            assertTrue(p.name, dev.jarvis.assistant.chat.ChatLimits.historyBudgetChars(p) > 0)
        }
    }
}
