package dev.jarvis.assistant.ai

import com.sun.net.httpserver.HttpServer
import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class GeminiProviderTest {
    private class Hit(val method: String, val uri: String, val keyHeader: String?)

    private lateinit var server: HttpServer
    private val hits = CopyOnWriteArrayList<Hit>()
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val key = "AIzaSyFAKEKEYFORTESTING1234567890abcd"

    @Volatile private var status = 200
    @Volatile private var body = ""
    @Volatile private var contentType = "application/json"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits += Hit(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("x-goog-api-key"))
            ex.requestBody.readBytes()
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun provider() = GeminiProvider(
        sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } },
        baseUrl = "http://127.0.0.1:${server.address.port}",
        sleeper = { sleeps += it },
    )

    private fun request() = ChatRequest("gemini-test", key, "sys prompt SECRET-PROMPT", listOf(ChatMessage(ChatRole.USER, "my private message")))

    private fun unavailable() {
        status = 503
        body = """{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}"""
    }

    private fun collect(options: CallOptions): LlmException? = try {
        runBlocking { provider().stream(request(), options).toList() }
        null
    } catch (e: LlmException) {
        e
    }

    @Test
    fun persistent503MakesExactlyThreeRequestsWithBackoff() {
        unavailable()
        val e = collect(CallOptions(maxRetries = 2))!!
        assertTrue(e.error is LlmError.Overloaded)
        assertEquals(3, hits.size)
        assertEquals(2, sleeps.size)
        assertTrue(sleeps[0] in 2000L..2500L)
        assertTrue(sleeps[1] in 4000L..4500L)
        assertEquals(listOf(1, 2, 3), entries.map { it.attempt })
        assertEquals("UNAVAILABLE", e.context!!.apiStatus)
        assertTrue(e.context!!.endpoint.contains("gemini-test:streamGenerateContent"))
        assertEquals("gemini-test", e.context!!.model)
    }

    @Test
    fun noRetriesByDefault() {
        unavailable()
        collect(CallOptions())
        assertEquals(1, hits.size)
    }

    @Test
    fun otherErrorsAreNeverRetried() {
        status = 429
        body = """{"error":{"code":429,"message":"q","status":"RESOURCE_EXHAUSTED","details":[{"@type":"x.QuotaFailure","violations":[{"quotaId":"RequestsPerDay-FreeTier"}]}]}}"""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.QuotaExhausted)
        assertEquals(1, hits.size)

        hits.clear()
        status = 404
        body = """{"error":{"code":404,"message":"not found","status":"NOT_FOUND"}}"""
        assertTrue(collect(CallOptions(maxRetries = 2))!!.error is LlmError.ModelNotFound)
        assertEquals(1, hits.size)

        hits.clear()
        status = 403
        body = """{"error":{"code":403,"message":"Method doesn't allow unregistered callers","status":"PERMISSION_DENIED"}}"""
        collect(CallOptions(maxRetries = 2))
        assertEquals(1, hits.size)
    }

    @Test
    fun nonGoogleHtml503IsNotRetriedAndNotBlamedOnGoogle() {
        status = 503
        contentType = "text/html"
        body = "<html><body>Blocked by network</body></html>"
        val e = collect(CallOptions(maxRetries = 2))!!
        assertTrue(e.error is LlmError.NotFromApi)
        assertEquals(1, hits.size)
    }

    @Test
    fun availabilityCheckIsOneGetAndNeverGeneration() {
        status = 200
        body = """{"name":"models/gemini-test","displayName":"Test","supportedGenerationMethods":["generateContent","countTokens"]}"""
        val info = runBlocking { provider().checkModel(key, "gemini-test") }
        assertTrue(info.canGenerate)
        assertEquals(1, hits.size)
        assertEquals("GET", hits[0].method)
        assertFalse(hits[0].uri.contains("generateContent"))
        assertEquals(CallKind.AVAILABILITY, entries.single().kind)
        assertFalse(entries.single().kind.usesGenerationQuota)
    }

    @Test
    fun keyIsOnlyInTheHeaderAndNeverLogged() {
        unavailable()
        body = """{"error":{"code":503,"message":"overloaded for key $key, user me@example.com","status":"UNAVAILABLE"}}"""
        val e = collect(CallOptions())!!
        assertEquals(key, hits[0].keyHeader)
        assertFalse(hits[0].uri.contains("AIza"))
        val everything = entries.joinToString { it.toString() } + e.context.toString() + FailureFormatter.details(e.context)
        assertFalse(everything, everything.contains(key))
        assertFalse(everything, everything.contains("me@example.com"))
        assertFalse(everything, everything.contains("SECRET-PROMPT"))
        assertFalse(everything, everything.contains("my private message"))
        assertEquals(request().sizeInChars(), entries.single().requestChars)
    }

    @Test
    fun streamsAndJoinsSseText() {
        status = 200
        contentType = "text/event-stream"
        body = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}\n\n" +
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\", sir.\"}]}}]}\n\n"
        val out = runBlocking { provider().stream(request(), CallOptions()).toList() }
        assertEquals("Hello, sir.", out.joinToString(""))
        assertTrue(entries.single().ok)
    }

    @Test
    fun nonStreamedGenerationIsOnePost() {
        status = 200
        body = """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""
        assertEquals("ok", runBlocking { provider().generateOnce(request()) })
        assertEquals(1, hits.size)
        assertTrue(hits[0].uri.contains(":generateContent"))
        assertEquals(CallKind.GENERATE_ONCE, entries.single().kind)
    }

    @Test
    fun connectionRefusedIsOffline() {
        server.stop(0)
        try {
            runBlocking { provider().checkModel(key, "m") }
            fail("expected exception")
        } catch (e: LlmException) {
            assertEquals(LlmError.Offline, e.error)
        }
        // restart so @After's stop is harmless
    }
}
