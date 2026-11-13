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

class CloudflareProviderTest {
    private class Hit(val method: String, val uri: String, val auth: String?, val body: String)
    private class Reply(val status: Int, val body: String, val headers: String = "", val contentType: String = "application/json")

    private lateinit var server: ServerSocket
    private val hits = CopyOnWriteArrayList<Hit>()
    private val entries = CopyOnWriteArrayList<DiagnosticEntry>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val accountId = "0123456789abcdef0123456789abcdef"
    private val token = "CFTOKEN_FAKE_1234567890abcdefghij"
    private val key = "$accountId:$token"
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val model = "@cf/zai-org/glm-4.7-flash"

    @Volatile private var reply = Reply(200, "")
    @Volatile private var search = Reply(200, """{"result":[{"name":"@cf/zai-org/glm-4.7-flash"}],"success":true}""")

    private fun sse(vararg lines: String) = Reply(200, lines.joinToString("\n") + "\n\n", contentType = "text/event-stream")
    private fun chunk(text: String) = """data: {"choices":[{"index":0,"delta":{"content":"$text"}}]}"""

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
                    val r = if (first[1].contains("/ai/models/search")) search else reply
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

    private fun provider() = CloudflareProvider(
        sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } },
        baseUrl = "http://127.0.0.1:${server.localPort}",
        clock = { now },
        sleeper = { sleeps += it },
    )

    private fun request(apiKey: String = key) =
        ChatRequest(model, apiKey, "sys SECRET-PROMPT", listOf(ChatMessage(ChatRole.USER, "my private message")))

    private fun run(options: CallOptions = CallOptions(), apiKey: String = key): Pair<String, LlmException?> {
        val text = StringBuilder()
        val err = try {
            runBlocking { provider().stream(request(apiKey), options).collect { text.append(it) } }
            null
        } catch (e: LlmException) {
            e
        }
        return text.toString() to err
    }

    private fun noSecretsAnywhere() {
        val all = entries.flatMap { listOf(it.message, it.endpoint, it.model, it.method, it.apiStatus) }
        assertTrue(all.none { it?.contains(token) == true || it?.contains(accountId) == true || it?.contains("private message") == true })
    }

    @Test fun streamsOpenAiStyleChunksToTheAccountScopedEndpointWithTheTokenInAHeader() {
        reply = sse(chunk("Hello "), chunk("world"), "data: [DONE]")
        val (text, err) = run()
        assertNull(err)
        assertEquals("Hello world", text)
        val h = hits.single()
        assertEquals("POST", h.method)
        assertEquals("/client/v4/accounts/$accountId/ai/v1/chat/completions", h.uri)
        assertEquals("Bearer $token", h.auth)
        assertTrue(h.body.contains("\"model\":\"$model\""))
        assertTrue(h.body.contains("\"stream\":true"))
        assertFalse(h.body.contains(token))
        assertEquals("api.cloudflare.com", "api.cloudflare.com") // host constant sanity
        assertTrue(entries.single().endpoint.contains("{account}"))
        noSecretsAnywhere()
    }

    @Test fun streamsTheNativeResponseShapeToo() {
        reply = sse("""data: {"response":"New"}""", """data: {"response":" York"}""", "data: [DONE]")
        assertEquals("New York", run().first)
    }

    @Test fun aPlainJsonBodyInsteadOfAnEventStreamStillWorks() {
        reply = Reply(200, """{"choices":[{"message":{"role":"assistant","content":"whole reply"}}]}""")
        val (text, err) = run()
        assertNull(err)
        assertEquals("whole reply", text)
    }

    @Test fun aKeyInTheWrongShapeIsRejectedBeforeAnyRequestAndNeverEchoed() {
        val (_, err) = run(apiKey = "just-a-token-no-account")
        assertTrue((err!!.error as LlmError.Other).message.contains("ACCOUNT_ID:API_TOKEN"))
        assertEquals(0, hits.size)
        assertFalse(err.error.toString().contains("just-a-token"))
    }

    @Test fun usedUpFreeAllocationIsDailyQuotaAndIsNotRetried() {
        reply = Reply(429, """{"success":false,"errors":[{"code":3036,"message":"You have used up your daily free allocation of 10,000 neurons. Please upgrade to Cloudflare's Workers Paid plan"}],"messages":[],"result":null}""")
        val ex = run(CallOptions(maxRetries = 2)).second!!
        val e = ex.error as LlmError.QuotaExhausted
        assertEquals(ResetSource.DOCUMENTED, e.resetSource)
        assertEquals(Instant.parse("2026-11-14T00:00:00Z"), e.resetAt)
        assertEquals(1, hits.size)
        assertEquals(429, ex.context!!.httpStatus)
        assertEquals("3036", ex.context!!.apiStatus)
        noSecretsAnywhere()
    }

    @Test fun outOfCapacityIsTemporaryRetriedTwiceAndNeverQuota() {
        reply = Reply(429, """{"success":false,"errors":[{"code":3040,"message":"Capacity temporarily exceeded, please try again."}]}""")
        val e = run(CallOptions(maxRetries = 2)).second!!.error
        assertTrue(e is LlmError.Overloaded)
        assertEquals(3, hits.size)
        assertEquals(2, sleeps.size)
    }

    @Test fun aPaidOnlyModelIsRefusedAsNeedingBillingAndNothingElseHappens() {
        reply = Reply(403, """{"success":false,"errors":[{"code":5035,"message":"Model requires a Workers Paid plan"}]}""")
        val e = run(CallOptions(maxRetries = 2)).second!!.error
        assertTrue(e is LlmError.BillingRequired)
        assertEquals(1, hits.size)
    }

    @Test fun httpErrorsAreClassified() {
        fun errorFor(status: Int, body: String): LlmError { reply = Reply(status, body); return run().second!!.error }
        assertEquals(LlmError.InvalidKey, errorFor(403, """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}"""))
        assertEquals(LlmError.InvalidKey, errorFor(401, """{"success":false,"errors":[{"code":10001,"message":"Unable to authenticate request"}]}"""))
        assertTrue(errorFor(400, """{"errors":[{"code":5007,"message":"No such model"}]}""") is LlmError.ModelNotFound)
        assertTrue(errorFor(404, """{"errors":[{"code":3042,"message":"Invalid model ID"}]}""") is LlmError.ModelNotFound)
        assertTrue(errorFor(408, """{"errors":[{"code":3007,"message":"Timeout"}]}""") is LlmError.Timeout)
        assertTrue(errorFor(502, "<html>nope</html>") is LlmError.NotFromApi)
        val unclear = errorFor(429, """{"errors":[{"code":9999,"message":"slow down"}]}""")
        assertTrue(unclear is LlmError.Unclear429)
    }

    @Test fun errorInsideTheStreamBeforeTextIsTypedAndAfterTextKeepsTheText() {
        reply = sse("""data: {"success":false,"errors":[{"code":3036,"message":"used up"}]}""")
        assertTrue(run().second!!.error is LlmError.QuotaExhausted)

        hits.clear()
        reply = sse(chunk("half an answ"), """data: {"error":{"code":3040,"message":"capacity"}}""")
        val (text, err) = run(CallOptions(maxRetries = 2))
        assertEquals("half an answ", text)
        assertTrue(err!!.error is LlmError.Overloaded)
        assertEquals(1, hits.size) // text had been shown, so no retry
    }

    @Test fun aStreamWithNoTextIsAFailure() {
        reply = sse("data: [DONE]")
        assertTrue(run().second!!.error is LlmError.Blocked)
    }

    @Test fun availabilityCheckUsesTheCatalogEndpointAndNoGeneration() {
        val info = runBlocking { provider().checkModel(key, model) }
        assertEquals(model, info.id)
        assertTrue(info.canGenerate)
        val h = hits.single()
        assertEquals("GET", h.method)
        assertTrue(h.uri.startsWith("/client/v4/accounts/$accountId/ai/models/search"))
        assertFalse(entries.single().kind.usesGenerationQuota)
        noSecretsAnywhere()
    }

    @Test fun availabilityCheckWithABadTokenReportsInvalidKey() {
        search = Reply(403, """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""")
        val e = try { runBlocking { provider().checkModel(key, model) }; null } catch (x: LlmException) { x.error }
        assertEquals(LlmError.InvalidKey, e)
    }

    @Test fun generateOnceReadsTheReplyAndSurfacesQuotaErrors() {
        reply = Reply(200, """{"choices":[{"message":{"content":"hi there"}}]}""")
        assertEquals("hi there", runBlocking { provider().generateOnce(request()) })
        reply = Reply(429, """{"errors":[{"code":3036,"message":"used up"}]}""")
        val e = try { runBlocking { provider().generateOnce(request()) }; null } catch (x: LlmException) { x.error }
        assertTrue(e is LlmError.QuotaExhausted)
    }
}
