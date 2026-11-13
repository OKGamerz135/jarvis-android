package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class OpenRouterProviderTest {
    private class Hit(val method: String, val uri: String, val auth: String?, val body: String)
    private class Reply(val status: Int, val body: String, val headers: String = "", val contentType: String = "application/json")

    private lateinit var server: ServerSocket
    private val hits = CopyOnWriteArrayList<Hit>()
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val key = "sk-or-v1-FAKEKEYFORTESTING1234567890abcdefgh"
    private val now = Instant.parse("2026-11-13T10:00:00Z")

    @Volatile private var chat = Reply(200, "")
    @Volatile private var keyInfo = Reply(200, keyJson(used = 3, limit = 50, remaining = 47))
    @Volatile private var models = Reply(200, "{\"data\":[]}")

    private fun keyJson(used: Int, limit: Int, remaining: Int) =
        """{"data":{"label":"x","is_free_tier":true,"free_model_daily_requests":{"used":$used,"limit":$limit,"remaining":$remaining}}}"""

    private fun sse(vararg lines: String) = Reply(200, lines.joinToString("\n") + "\n\n", contentType = "text/event-stream")
    private fun chunk(text: String) = """data: {"id":"g","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":null}]}"""

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
                    val r = when {
                        first[1].startsWith("/api/v1/key") -> keyInfo
                        first[1].startsWith("/api/v1/models") -> models
                        else -> chat
                    }
                    val bytes = r.body.toByteArray()
                    val head = "HTTP/1.1 ${r.status} X\r\nContent-Type: ${r.contentType}\r\n${r.headers}" +
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

    @After fun stop() = server.close()

    private fun provider() = OpenRouterProvider(
        sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } },
        baseUrl = "http://127.0.0.1:${server.localPort}",
        clock = { now },
        sleeper = { sleeps += it },
    )

    private fun request(model: String = "vendor/some-model:free") =
        ChatRequest(model, key, "sys SECRET-PROMPT", listOf(ChatMessage(ChatRole.USER, "my private message")))

    private val chatHits get() = hits.filter { it.uri.startsWith("/api/v1/chat") }

    /** Returns the text received and the error, if any. */
    private fun run(p: OpenRouterProvider = provider(), req: ChatRequest = request(), options: CallOptions = CallOptions()): Pair<String, LlmException?> {
        val text = StringBuilder()
        val err = try {
            runBlocking { p.stream(req, options).collect { text.append(it) } }
            null
        } catch (e: LlmException) {
            e
        }
        return text.toString() to err
    }

    @Test fun streamsTextSkippingKeepalivesAndTheUsageChunk() {
        chat = sse(
            ": OPENROUTER PROCESSING", "", chunk("Hello "), ": OPENROUTER PROCESSING", chunk("world"),
            """data: {"id":"g","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":3}}""",
            "data: [DONE]",
        )
        val (text, err) = run()
        assertNull(err)
        assertEquals("Hello world", text)
        val h = chatHits.single()
        assertEquals("POST", h.method)
        assertEquals("Bearer $key", h.auth)
        assertTrue(h.body.contains("\"model\":\"vendor/some-model:free\""))
        assertTrue(h.body.contains("\"stream\":true"))
        assertTrue(h.body.contains("my private message"))
        assertFalse(h.body.contains(key)) // the key is only ever in the header
        assertFalse(h.uri.contains(key))
        assertTrue(entries.all { e -> listOf(e.message, e.endpoint, e.model, e.method).none { it?.contains(key) == true || it?.contains("private message") == true } })
        assertEquals(1, entries.count { it.ok })
    }

    @Test fun aModelThatIsNotFreeIsRefusedWithoutAnyRequest() {
        val (_, err) = run(req = request("openai/gpt-4o"))
        assertTrue(err!!.error is LlmError.Other)
        assertTrue((err.error as LlmError.Other).message.contains("not a free model"))
        assertEquals(0, hits.size)
        assertFalse(err.error.toString().contains(key))
    }

    @Test fun autoFreePicksTheBiggestContextFreeModelAndCachesIt() {
        models = Reply(
            200,
            """{"data":[
              {"id":"paid/big","context_length":1000000,"pricing":{"prompt":"0.000001","completion":"0.000002"}},
              {"id":"vendor/small:free","context_length":8000,"pricing":{"prompt":"0","completion":"0"}},
              {"id":"vendor/large:free","context_length":128000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]}},
              {"id":"vendor/image:free","context_length":999999,"pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["image"]}},
              {"id":"vendor/old:free","context_length":500000,"pricing":{"prompt":"0","completion":"0"},"expiration_date":"2026-11-01"},
              {"id":"vendor/priced:free","context_length":900000,"pricing":{"prompt":"0.1","completion":"0"}}
            ]}""",
        )
        chat = sse(chunk("ok"), "data: [DONE]")
        val p = provider()
        assertEquals("ok", run(p, request(OpenRouterParsing.AUTO)).first)
        assertEquals("ok", run(p, request("")).first)
        assertTrue(chatHits.all { it.body.contains("\"model\":\"vendor/large:free\"") })
        assertEquals(1, hits.count { it.uri.startsWith("/api/v1/models") }) // cached for the second call
    }

    @Test fun autoFreeWithNoFreeModelsSaysSoAndSendsNothing() {
        models = Reply(200, """{"data":[{"id":"paid/x","pricing":{"prompt":"1","completion":"1"}}]}""")
        val (_, err) = run(req = request(OpenRouterParsing.AUTO))
        assertTrue((err!!.error as LlmError.Other).message.contains("no free text model"))
        assertEquals(0, chatHits.size)
    }

    @Test fun errorInsideTheStreamBeforeAnyTextIsTypedAndRetriedOnlyWhenOverloaded() {
        chat = sse(""": OPENROUTER PROCESSING""", """data: {"id":"c","error":{"code":"server_error","message":"Provider disconnected unexpectedly"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""")
        val (text, err) = run(options = CallOptions(maxRetries = 2))
        assertEquals("", text)
        assertTrue(err!!.error is LlmError.Overloaded)
        assertEquals(3, chatHits.size) // 1 + 2 retries, because nothing had been shown yet
        assertEquals(2, sleeps.size)
        assertEquals("server_error", err.context!!.apiStatus)
    }

    @Test fun errorInsideTheStreamAfterTextKeepsTheTextAndIsNotRetried() {
        chat = sse(chunk("half an answ"), """data: {"id":"c","error":{"code":502,"message":"upstream died"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""")
        val (text, err) = run(options = CallOptions(maxRetries = 2))
        assertEquals("half an answ", text)
        assertTrue(err!!.error is LlmError.Overloaded)
        assertEquals(1, chatHits.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun finishReasonErrorWithoutAnErrorObjectIsStillAFailure() {
        chat = sse("""data: {"id":"c","choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""", "data: [DONE]")
        val (_, err) = run()
        assertTrue(err!!.error is LlmError.Other)
    }

    @Test fun aStreamWithNoTextIsAFailureNotASilentSuccess() {
        chat = sse("data: [DONE]")
        val (_, err) = run()
        assertTrue(err!!.error is LlmError.Blocked)
    }

    @Test fun rateLimitInsideTheStreamWithZeroFreeRequestsLeftIsDailyQuota() {
        keyInfo = Reply(200, keyJson(50, 50, 0))
        chat = sse("""data: {"id":"c","error":{"code":429,"message":"Rate limit exceeded"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""")
        val e = run().second!!.error as LlmError.QuotaExhausted
        assertEquals(ResetSource.DOCUMENTED, e.resetSource)
        assertEquals(Instant.parse("2026-11-14T00:00:00Z"), e.resetAt)
    }

    @Test fun http429WithNoFreeRequestsLeftIsDailyQuotaResettingAtUtcMidnight() {
        keyInfo = Reply(200, keyJson(50, 50, 0))
        chat = Reply(429, """{"error":{"code":429,"message":"Rate limit exceeded","metadata":{"error_type":"rate_limit_exceeded"}}}""")
        val ex = run().second!!
        val e = ex.error as LlmError.QuotaExhausted
        assertEquals(ResetSource.DOCUMENTED, e.resetSource)
        assertEquals(Instant.parse("2026-11-14T00:00:00Z"), e.resetAt)
        assertEquals("free-models-per-day", e.quotaId)
        assertEquals(429, ex.context!!.httpStatus)
        assertEquals(1, chatHits.size) // no automatic retry for a quota error
    }

    @Test fun http429WithRequestsLeftIsAShortLimitAndNeverQuota() {
        chat = Reply(429, """{"error":{"code":429,"message":"Rate limit exceeded"}}""", headers = "Retry-After: 7\r\n")
        val e = run().second!!.error
        assertEquals(LlmError.RateLimited(7), e)
    }

    @Test fun http429WithRequestsLeftAndNoHintIsUnclearNotGuessed() {
        chat = Reply(429, """{"error":{"code":429,"message":"Provider returned error"}}""")
        assertEquals(LlmError.Unclear429(null), run().second!!.error)
    }

    @Test fun http429WhenTheQuotaLookupFailsStaysUnclearAndNothingIsSwitched() {
        keyInfo = Reply(500, "oops")
        chat = Reply(429, """{"error":{"code":429,"message":"Rate limit exceeded"}}""")
        assertTrue(run().second!!.error is LlmError.Unclear429)
    }

    @Test fun rateLimitResetHeaderWithinTwoMinutesBecomesTheRetryTime() {
        val reset = now.toEpochMilli() + 30_000
        chat = Reply(429, """{"error":{"code":429,"message":"Rate limit exceeded"}}""", headers = "X-RateLimit-Reset: $reset\r\n")
        assertEquals(LlmError.RateLimited(30), run().second!!.error)
    }

    @Test fun httpErrorsAreClassified() {
        fun errorFor(status: Int, body: String) = run { chat = Reply(status, body); run().second!!.error }
        assertEquals(LlmError.InvalidKey, errorFor(401, """{"error":{"code":401,"message":"No auth credentials found"}}"""))
        assertTrue(errorFor(402, """{"error":{"code":402,"message":"Insufficient credits"}}""") is LlmError.BillingRequired)
        assertTrue(errorFor(403, """{"error":{"code":403,"message":"flagged"}}""") is LlmError.PermissionDenied)
        assertTrue(errorFor(404, """{"error":{"code":404,"message":"No endpoints found matching your data policy"}}""") is LlmError.ModelNotFound)
        assertTrue(errorFor(408, """{"error":{"code":408,"message":"timed out"}}""") is LlmError.Timeout)
        assertTrue(errorFor(502, """{"error":{"code":502,"message":"model down"}}""") is LlmError.ServerTrouble)
        assertTrue(errorFor(503, """{"error":{"code":503,"message":"no provider"}}""") is LlmError.Overloaded)
        assertTrue(errorFor(502, "<html>bad gateway</html>") is LlmError.NotFromApi)
    }

    @Test fun overloadedHttpIsRetriedTwiceThenReported() {
        chat = Reply(503, """{"error":{"code":503,"message":"No available model provider"}}""")
        val e = run(options = CallOptions(maxRetries = 2)).second!!
        assertTrue(e.error is LlmError.Overloaded)
        assertEquals(3, chatHits.size)
    }

    @Test fun freeQuotaIsReadFromTheKeyEndpointWithoutAnyGeneration() {
        val q = runBlocking { provider().freeQuota(key) }
        assertEquals(OpenRouterParsing.FreeQuota(3, 50, 47), q)
        assertEquals(0, chatHits.size)
        assertEquals("Bearer $key", hits.single().auth)
        assertFalse(entries.single().kind.usesGenerationQuota)
    }

    @Test fun checkModelAcceptsAListedFreeModelAndRejectsPaidOrMissingOnes() {
        models = Reply(
            200,
            """{"data":[{"id":"a/b:free","context_length":1,"pricing":{"prompt":"0","completion":"0"}},
                        {"id":"c/d:free","pricing":{"prompt":"0.5","completion":"0"}}]}""",
        )
        val p = provider()
        assertEquals("a/b:free", runBlocking { p.checkModel(key, "a/b:free") }.id)
        fun errorOf(model: String) = try { runBlocking { p.checkModel(key, model) }; null } catch (e: LlmException) { e.error }
        assertTrue(errorOf("c/d:free") is LlmError.Other)
        assertTrue(errorOf("z/z:free") is LlmError.ModelNotFound)
        assertTrue(errorOf("openai/gpt-4o") is LlmError.Other)
        assertEquals(0, chatHits.size)
    }

    @Test fun generateOnceReadsTheReplyAndSurfacesErrorsInsideA200Body() {
        chat = Reply(200, """{"choices":[{"message":{"role":"assistant","content":"hi there"}}]}""")
        assertEquals("hi there", runBlocking { provider().generateOnce(request()) })
        chat = Reply(200, """{"error":{"code":"server_error","message":"provider died"}}""")
        val e = try { runBlocking { provider().generateOnce(request()) }; null } catch (x: LlmException) { x.error }
        assertTrue(e is LlmError.Overloaded)
    }
}
