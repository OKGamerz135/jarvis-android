package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/** C3a: the generic OpenAI-compatible adapter, against a fake server on this machine. */
class OpenAiCompatibleProviderTest {
    private class Hit(val method: String, val uri: String, val auth: String?, val accept: String?, val body: String)

    /** A tiny HTTP server. [reply] decides the answer for each request path; every request is recorded. */
    private class FakeServer(val reply: (path: String) -> ByteArray) : AutoCloseable {
        val hits = CopyOnWriteArrayList<Hit>()
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
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
                        var accept: String? = null
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            val name = h.substringBefore(":").lowercase()
                            val value = h.substringAfter(":").trim()
                            if (name == "content-length") length = value.toInt()
                            if (name == "authorization") auth = value
                            if (name == "accept") accept = value
                        }
                        val sent = ByteArray(length)
                        var read = 0
                        while (read < length) { val n = input.read(sent, read, length - read); if (n < 0) break; read += n }
                        hits += Hit(first[0], first[1], auth, accept, String(sent, 0, read))
                        socket.getOutputStream().apply { write(reply(first[1])); flush() }
                    } catch (e: Exception) {
                        // a closed or timed-out client is fine for a test server
                    } finally {
                        socket.close()
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = server.close()
    }

    private fun raw(status: Int, body: String = "", headers: List<String> = emptyList(), contentType: String = "application/json"): ByteArray {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Type: $contentType\r\n" + headers.joinToString("") { "$it\r\n" } +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray() + bytes
    }

    private val open = CopyOnWriteArrayList<AutoCloseable>()
    private fun server(reply: (String) -> ByteArray) = FakeServer(reply).also { open += it }
    @After fun closeAll() { open.forEach { it.close() } }

    private val key = "KEY-FAKE-FOR-TESTING-0123456789-abcdef"
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } }

    private fun provider(port: Int, prefix: String = "/v1") =
        OpenAiCompatibleProvider(EndpointProfile.loopbackForTests("Custom One", "http://127.0.0.1:$port$prefix")!!, sink)

    private val request = ChatRequest("m-1", key, "system text", listOf(ChatMessage(ChatRole.USER, "hello there")))
    private val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\ndata: [DONE]\n\n"

    private fun streamAll(p: OpenAiCompatibleProvider, options: CallOptions = CallOptions()) =
        runBlocking { p.stream(request, options).toList() }

    private fun failure(block: () -> Unit): LlmException {
        try { block() } catch (e: LlmException) { return e }
        fail("expected an LlmException"); throw IllegalStateException()
    }

    // ------------------------------------------------------------------ chat (streamed)

    @Test fun aStreamedReplyIsReadAndTheRequestIsBuiltFromTheProfileOnly() {
        val s = server { raw(200, sse, contentType = "text/event-stream") }
        assertEquals(listOf("Hel", "lo"), streamAll(provider(s.port)))
        val hit = s.hits.single()
        assertEquals("POST", hit.method)
        assertEquals("/v1/chat/completions", hit.uri)
        assertEquals("Bearer $key", hit.auth)
        assertEquals("text/event-stream", hit.accept)
        assertTrue(hit.body.contains("\"stream\":true") && hit.body.contains("\"model\":\"m-1\"") && hit.body.contains("hello there"))
        assertFalse("the key must never be in the URL", hit.uri.contains("KEY"))
        assertFalse("the key must never be in the body", hit.body.contains("KEY"))
    }

    @Test fun thePathPrefixIsKeptForChatAndModelList() {
        for ((prefix, chat, models) in listOf(
            Triple("", "/chat/completions", "/models"),
            Triple("/", "/chat/completions", "/models"),
            Triple("/v1", "/v1/chat/completions", "/v1/models"),
            Triple("/v1/", "/v1/chat/completions", "/v1/models"),
            Triple("/api/v1", "/api/v1/chat/completions", "/api/v1/models"),
        )) {
            val s = server { path -> if (path.endsWith("/models")) raw(200, """{"data":[{"id":"m-1"}]}""") else raw(200, sse, contentType = "text/event-stream") }
            val p = provider(s.port, prefix)
            streamAll(p)
            runBlocking { p.checkModel(key, "m-1") }
            assertEquals("for prefix '$prefix'", listOf(chat, models), s.hits.map { it.uri })
        }
    }

    @Test fun theDiagnosticsLogUsesTheProfileLabelAndNeverTheKey() {
        val s = server { raw(200, sse, contentType = "text/event-stream") }
        streamAll(provider(s.port))
        val e = entries.single()
        assertEquals("Custom One", e.provider)
        assertEquals(CallKind.GENERATE_STREAM, e.kind)
        assertTrue(e.ok)
        assertEquals("127.0.0.1:${s.port}/v1/chat/completions", e.endpoint)
        assertFalse(listOf(e.model, e.endpoint, e.message.orEmpty(), e.apiStatus.orEmpty()).any { it.contains("KEY-FAKE") })
    }

    @Test fun noTextIsABlockedReplyNotASuccess() {
        val s = server { raw(200, "data: [DONE]\n\n", contentType = "text/event-stream") }
        val e = failure { streamAll(provider(s.port)) }
        assertTrue(e.error is LlmError.Blocked)
        assertFalse(entries.single().ok)
    }

    @Test fun anErrorObjectMidStreamFailsTheCall() {
        val s = server { raw(200, "data: {\"error\":{\"message\":\"upstream broke\"}}\n\n", contentType = "text/event-stream") }
        assertEquals(LlmError.Other("upstream broke"), failure { streamAll(provider(s.port)) }.error)
    }

    // ------------------------------------------------------------------ errors, and never resending

    @Test fun aRequestIsNeverSentAgainEvenWhenTheCallerAsksForRetries() {
        val s = server { raw(503, """{"error":{"message":"overloaded"}}""") }
        val e = failure { streamAll(provider(s.port), CallOptions(maxRetries = 2, events = object : CallEvents {
            override fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) { fail("must not announce a retry") }
        })) }
        assertTrue(e.error is LlmError.Overloaded)
        assertEquals("exactly one request", 1, s.hits.size)
    }

    @Test fun serverErrorsAreMappedAndAnExhaustedQuotaIsNeverReported() {
        val cases = listOf(
            401 to """{"error":{"message":"bad key"}}""",
            402 to """{"error":{"message":"pay"}}""",
            429 to """{"error":{"message":"daily limit (RPD) per day"}}""",
            500 to """<html>oops</html>""",
        )
        val seen = cases.map { (status, body) ->
            val s = server { raw(status, body) }
            failure { streamAll(provider(s.port)) }.error
        }
        assertEquals(LlmError.InvalidKey, seen[0])
        assertTrue(seen[1] is LlmError.BillingRequired)
        assertTrue(seen[2] is LlmError.Unclear429)
        assertFalse(seen.any { it is LlmError.QuotaExhausted })
        assertTrue(seen[3] is LlmError.NotFromApi)
    }

    @Test fun aKeyEchoedByTheServerIsRedactedFromTheErrorAndTheLog() {
        val s = server { raw(401, """{"error":{"message":"Incorrect API key provided: $key."}}""") }
        val e = failure { streamAll(provider(s.port)) }
        val shown = listOf(e.context?.apiMessage.orEmpty(), e.context?.endpoint.orEmpty(), e.error.toString(), entries.single().message.orEmpty())
        assertTrue(shown.toString(), shown.none { it.contains("KEY-FAKE-FOR-TESTING") })
    }

    @Test fun anUnreachableServerIsOfflineAndTimeoutsAreNotMistakenForIt() {
        val closed = server { raw(200) }.also { it.close() }
        assertEquals(LlmError.Offline, failure { streamAll(provider(closed.port)) }.error)
    }

    // ------------------------------------------------------------------ one-shot generation (diagnostics only)

    @Test fun generateOnceReadsAWholeReplyAndFailsOnNone() {
        val ok = server { raw(200, """{"choices":[{"message":{"content":"whole reply"}}]}""") }
        assertEquals("whole reply", runBlocking { provider(ok.port).generateOnce(request) })
        assertTrue(ok.hits.single().body.contains("\"stream\":false"))
        assertEquals("/v1/chat/completions", ok.hits.single().uri)
        val empty = server { raw(200, """{"choices":[{"message":{"content":""}}]}""") }
        assertTrue(failure { runBlocking { provider(empty.port).generateOnce(request) } }.error is LlmError.Blocked)
        val busy = server { raw(503, """{"error":{"message":"busy"}}""") }
        failure { runBlocking { provider(busy.port).generateOnce(request) } }
        assertEquals(1, busy.hits.size)
    }

    // ------------------------------------------------------------------ the model list

    @Test fun aListedModelIsAccepted() {
        val s = server { raw(200, """{"data":[{"id":"other"},{"id":"m-1"}]}""") }
        val info = runBlocking { provider(s.port).checkModel(key, "m-1") }
        assertEquals("m-1", info.id); assertTrue(info.canGenerate)
        assertEquals("GET", s.hits.single().method); assertEquals("Bearer $key", s.hits.single().auth)
    }

    @Test fun aModelThatIsNotListedIsNotFound() {
        val s = server { raw(200, """{"data":[{"id":"other"}]}""") }
        assertEquals(LlmError.ModelNotFound("m-1"), failure { runBlocking { provider(s.port).checkModel(key, "m-1") } }.error)
    }

    @Test fun noModelListIsUnavailableNotAFailureAndNotAPass() {
        for (status in listOf(404, 405)) {
            val s = server { raw(status, """{"error":{"message":"no such route"}}""") }
            val e = failure { runBlocking { provider(s.port).checkModel(key, "m-1") } }
            assertEquals(LlmError.ModelListUnavailable(status), e.error)
            assertEquals(1, s.hits.size)
        }
        val html = server { raw(200, "<html>welcome</html>", contentType = "text/html") }
        assertEquals(LlmError.ModelListUnavailable(200), failure { runBlocking { provider(html.port).checkModel(key, "m-1") } }.error)
        val noRoute = OpenAiCompatibleProvider(EndpointProfile.parse("L", "https://api.example.test", modelsPath = null)!!)
        assertEquals(LlmError.ModelListUnavailable(null), failure { runBlocking { noRoute.checkModel(key, "m-1") } }.error)
    }

    @Test fun otherModelListStatusesAreRealFailures() {
        for ((status, check) in listOf(
            401 to { e: LlmError -> e == LlmError.InvalidKey },
            403 to { e: LlmError -> e is LlmError.PermissionDenied },
            500 to { e: LlmError -> e is LlmError.InternalError },
        )) {
            val s = server { raw(status, """{"error":{"message":"no"}}""") }
            val e = failure { runBlocking { provider(s.port).checkModel(key, "m-1") } }
            assertTrue("status $status gave ${e.error}", check(e.error))
        }
    }

    // ------------------------------------------------------------------ redirects (C1a) on every request

    @Test fun aRedirectIsRefusedOnEveryKindOfRequestAndNothingIsSentElsewhere() {
        val ops = listOf<Pair<String, (OpenAiCompatibleProvider) -> Unit>>(
            "stream" to { p -> streamAll(p) },
            "checkModel" to { p -> runBlocking { p.checkModel(key, "m-1") } },
            "generateOnce" to { p -> runBlocking { p.generateOnce(request) } },
        )
        for ((name, run) in ops) for (status in listOf(301, 302, 303, 307, 308)) {
            val elsewhere = server { raw(200, "{\"choices\":[{\"message\":{\"content\":\"HIJACKED\"},\"delta\":{\"content\":\"HIJACKED\"}}],\"data\":[{\"id\":\"m-1\"}]}") }
            val a = server { raw(status, headers = listOf("Location: http://127.0.0.1:${elsewhere.port}/v1/chat/completions")) }
            val e = failure { run(provider(a.port)) }
            val label = "$name $status"
            assertEquals("$label: the first server was asked once", 1, a.hits.size)
            assertEquals("$label: nothing was sent to the redirect target", 0, elsewhere.hits.size)
            assertTrue("$label: $e", e.error is LlmError.Other && (e.error as LlmError.Other).message.contains("redirect"))
            assertFalse(e.error.toString().contains("KEY-FAKE"))
        }
    }

    @Test fun aRedirectToARelativeAddressOnTheSameServerIsRefusedToo() {
        val a = server { raw(302, headers = listOf("Location: /other/path")) }
        val e = failure { streamAll(provider(a.port)) }
        assertEquals(1, a.hits.size)
        assertTrue(e.error is LlmError.Other)
    }

    @Test fun everyRecordedEntryOfAFailedCallIsMarkedNotOk() {
        val s = server { raw(401, """{"error":{"message":"bad"}}""") }
        failure { streamAll(provider(s.port)) }
        assertNotNull(entries.single()); assertFalse(entries.single().ok)
        assertEquals(401, entries.single().httpStatus)
    }
}
