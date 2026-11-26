package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/*
 * H1a-0 characterization harness. TEST-ONLY. It records what the unchanged HttpURLConnection providers do today.
 * Nothing here says what the code should do. See "H1a-0 Stage Plan, Revision 2".
 *
 * Files for H1a-0: this harness, CharacterizationRetryAfterTest, CharacterizationFollowUpStatusTest,
 * CharacterizationIoFailureTest and CharacterizationBaseline.
 */

/** One request as the fake server saw it. Header values are kept; they are only inspected, never printed. */
internal class CharRequest(
    val method: String,
    val path: String,
    val headers: List<Pair<String, String>>,
    val bodyBytes: Int,
    val bodyHash: String,
)

internal sealed class CharReply {
    class Bytes(val data: ByteArray) : CharReply()

    /** Read the request, then close the connection without writing anything. */
    object Close : CharReply()
}

/** A tiny raw HTTP server on loopback. Its own copy: the one in ProviderRedirectTest is private. */
internal class CharServer(private val reply: (CharRequest) -> CharReply) : AutoCloseable {
    val seen = CopyOnWriteArrayList<CharRequest>()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) { break }
                handle(socket)
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream().buffered()
            fun line(): String? {
                val sb = StringBuilder()
                var any = false
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (any) sb.toString() else null
                    any = true
                    if (c == '\n'.code) break
                    if (c != '\r'.code) sb.append(c.toChar())
                }
                return sb.toString()
            }
            val first = (line() ?: return).split(" ")
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val h = line() ?: break
                if (h.isEmpty()) break
                headers += h.substringBefore(":").lowercase() to h.substringAfter(":").trim()
            }
            val body = ByteArrayOutputStream()
            val chunked = headers.any { it.first == "transfer-encoding" && it.second.contains("chunked", ignoreCase = true) }
            if (chunked) {
                while (true) {
                    val size = (line() ?: break).substringBefore(";").trim().toIntOrNull(16) ?: break
                    if (size == 0) { line(); break }
                    val buf = ByteArray(size)
                    var read = 0
                    while (read < size) { val n = input.read(buf, read, size - read); if (n < 0) break; read += n }
                    body.write(buf, 0, read)
                    line()
                }
            } else {
                val length = headers.lastOrNull { it.first == "content-length" }?.second?.toIntOrNull() ?: 0
                val buf = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n }
                body.write(buf, 0, read)
            }
            val bytes = body.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(8)
            val request = CharRequest(first[0], first.getOrElse(1) { "" }, headers, bytes.size, hash)
            seen += request
            when (val r = reply(request)) {
                is CharReply.Bytes -> socket.getOutputStream().apply { write(r.data); flush() }
                CharReply.Close -> Unit
            }
        } catch (e: Exception) {
            // a client that hangs up early is fine
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    override fun close() = server.close()
}

internal object CharHttp {
    /** A complete HTTP/1.1 reply. [declaredLength] lets a case promise more body than it sends. */
    fun response(
        status: Int,
        headers: List<String> = emptyList(),
        body: String = "",
        contentType: String = "application/json",
        declaredLength: Int? = null,
    ): ByteArray {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Type: $contentType\r\n" + headers.joinToString("") { "$it\r\n" } +
            "Content-Length: ${declaredLength ?: bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray() + bytes
    }
}

/** One provider operation. [run] returns a printable form of a successful result; failures are rendered by the runner. */
internal class CharOp(
    val provider: String,
    val op: String,
    val kind: Kind,
    val markers: List<String>,
    val run: (baseUrl: String, sink: DiagnosticsSink) -> String,
) {
    enum class Kind { STREAM, CHECK, ONCE, QUOTA }
}

internal object CharOps {
    private val geminiKey = "AIzaSyFAKEKEYFORTESTING1234567890abcd"
    private val groqKey = "gsk_FAKEKEYFORTESTING1234567890abcdefgh"
    private val zaiKey = "zai-FAKEKEYFORTESTING1234567890abcdefgh"
    private val orKey = "sk-or-v1-FAKEKEYFORTESTING1234567890abcdefgh"
    private val cfToken = "CFTOKEN_FAKE_1234567890abcdefghij"
    private val cfKey = "0123456789abcdef0123456789abcdef:$cfToken"
    private val oaiKey = "oai-FAKEKEYFORTESTING1234567890abcdefgh"

    private const val GM = "gemini-test"
    private const val GQ = "llama-test"
    private const val ZM = "glm-4.7-flash"
    private const val CM = "@cf/zai-org/glm-4.7-flash"
    private const val OM = "vendor/model:free"
    private const val XM = "custom-model"

    private fun req(model: String, key: String) = ChatRequest(model, key, "sys", listOf(ChatMessage(ChatRole.USER, "hi")))

    /** Streams run with no app-level retry, so only transport behavior shows. */
    private fun streamText(p: LlmProvider, r: ChatRequest): String =
        runBlocking { p.stream(r, CallOptions(maxRetries = 0)).toList() }.toString()

    private fun oai(baseUrl: String, sink: DiagnosticsSink) =
        OpenAiCompatibleProvider(EndpointProfile.loopbackForTests("Custom One", baseUrl)!!, sink)

    val all: List<CharOp> = listOf(
        CharOp("Gemini", "stream", CharOp.Kind.STREAM, listOf(geminiKey)) { b, s -> streamText(GeminiProvider(s, b), req(GM, geminiKey)) },
        CharOp("Gemini", "checkModel", CharOp.Kind.CHECK, listOf(geminiKey)) { b, s -> runBlocking { GeminiProvider(s, b).checkModel(geminiKey, GM) }.toString() },
        CharOp("Gemini", "generateOnce", CharOp.Kind.ONCE, listOf(geminiKey)) { b, s -> runBlocking { GeminiProvider(s, b).generateOnce(req(GM, geminiKey)) }.toString() },

        CharOp("Groq", "stream", CharOp.Kind.STREAM, listOf(groqKey)) { b, s -> streamText(GroqProvider(s, b), req(GQ, groqKey)) },
        CharOp("Groq", "checkModel", CharOp.Kind.CHECK, listOf(groqKey)) { b, s -> runBlocking { GroqProvider(s, b).checkModel(groqKey, GQ) }.toString() },
        CharOp("Groq", "generateOnce", CharOp.Kind.ONCE, listOf(groqKey)) { b, s -> runBlocking { GroqProvider(s, b).generateOnce(req(GQ, groqKey)) }.toString() },

        CharOp("Z.ai", "stream", CharOp.Kind.STREAM, listOf(zaiKey)) { b, s -> streamText(ZaiProvider(s, b), req(ZM, zaiKey)) },
        CharOp("Z.ai", "checkModel", CharOp.Kind.CHECK, listOf(zaiKey)) { b, s -> runBlocking { ZaiProvider(s, b).checkModel(zaiKey, ZM) }.toString() },
        CharOp("Z.ai", "generateOnce", CharOp.Kind.ONCE, listOf(zaiKey)) { b, s -> runBlocking { ZaiProvider(s, b).generateOnce(req(ZM, zaiKey)) }.toString() },

        CharOp("Cloudflare", "stream", CharOp.Kind.STREAM, listOf(cfToken)) { b, s -> streamText(CloudflareProvider(s, b), req(CM, cfKey)) },
        CharOp("Cloudflare", "checkModel", CharOp.Kind.CHECK, listOf(cfToken)) { b, s -> runBlocking { CloudflareProvider(s, b).checkModel(cfKey, CM) }.toString() },
        CharOp("Cloudflare", "generateOnce", CharOp.Kind.ONCE, listOf(cfToken)) { b, s -> runBlocking { CloudflareProvider(s, b).generateOnce(req(CM, cfKey)) }.toString() },

        CharOp("OpenRouter", "stream", CharOp.Kind.STREAM, listOf(orKey)) { b, s -> streamText(OpenRouterProvider(s, b), req(OM, orKey)) },
        CharOp("OpenRouter", "stream-autofree", CharOp.Kind.STREAM, listOf(orKey)) { b, s -> streamText(OpenRouterProvider(s, b), req(OpenRouterParsing.AUTO, orKey)) },
        CharOp("OpenRouter", "checkModel", CharOp.Kind.CHECK, listOf(orKey)) { b, s -> runBlocking { OpenRouterProvider(s, b).checkModel(orKey, OM) }.toString() },
        CharOp("OpenRouter", "generateOnce", CharOp.Kind.ONCE, listOf(orKey)) { b, s -> runBlocking { OpenRouterProvider(s, b).generateOnce(req(OM, orKey)) }.toString() },
        CharOp("OpenRouter", "freeQuota", CharOp.Kind.QUOTA, listOf(orKey)) { b, s -> runBlocking { OpenRouterProvider(s, b).freeQuota(orKey) }.toString() },

        CharOp("OpenAiCompatible", "stream", CharOp.Kind.STREAM, listOf(oaiKey)) { b, s -> streamText(oai(b, s), req(XM, oaiKey)) },
        CharOp("OpenAiCompatible", "checkModel", CharOp.Kind.CHECK, listOf(oaiKey)) { b, s -> runBlocking { oai(b, s).checkModel(oaiKey, XM) }.toString() },
        CharOp("OpenAiCompatible", "generateOnce", CharOp.Kind.ONCE, listOf(oaiKey)) { b, s -> runBlocking { oai(b, s).generateOnce(req(XM, oaiKey)) }.toString() },
    )
}

/** A case is an id, a chunk name (for grouping the observation dump) and the code that produces its recorded line. */
internal class CharCase(val id: String, val chunk: String, val run: () -> String) {
    override fun toString() = id
}

internal object CharCases {
    /** A real provider call against a scripted loopback server. [reply] gets the ports of server A (the provider) and B (anyone else). */
    fun wire(group: String, op: CharOp, scenario: String, showOther: Boolean = false, reply: (aPort: Int, bPort: Int) -> CharReply): CharCase =
        CharCase("$group|${op.provider}|${op.op}|$scenario", "$group-${op.provider}") { CharWire.observe(op, showOther, reply) }
}

internal object CharWire {
    private fun scrub(text: String, markers: List<String>): String {
        var s = text
        for (m in markers) s = s.replace(m, "<KEY>")
        s = s.replace(Regex("(127\\.0\\.0\\.1|localhost):\\d+"), "HOST:PORT")
        return s.replace("\r", "\\r").replace("\n", "\\n").replace("|", "\u00a6")
    }

    private fun cap(text: String, max: Int = 200) = if (text.length > max) text.take(max) + "..." else text

    fun observe(op: CharOp, showOther: Boolean, reply: (Int, Int) -> CharReply): String {
        val b = CharServer { CharReply.Bytes(CharHttp.response(200, body = "{}")) }
        lateinit var a: CharServer
        a = CharServer { reply(a.port, b.port) }
        val entries = CopyOnWriteArrayList<DiagnosticEntry>()
        val sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } }
        try {
            val outcome = try {
                "Ok(" + cap(op.run("http://127.0.0.1:${a.port}", sink)) + ")"
            } catch (e: LlmException) {
                val c = e.context
                "LlmException(" + cap(e.error.toString()) + ", ctx=" +
                    (c?.let { "${it.provider} ${it.method} ${it.model} ${it.endpoint} ${it.httpStatus} ${it.apiStatus} ${cap(it.apiMessage.toString(), 100)}" }) + ")"
            } catch (t: Throwable) {
                "Thrown(" + t.javaClass.name + ": " + cap(t.message.toString()) + ")"
            }
            val requests = a.seen.joinToString("; ") { r ->
                val cred = when {
                    r.headers.any { h -> op.markers.any { m -> m in h.second } } -> "H"
                    op.markers.any { m -> m in r.path } -> "U"
                    else -> "N"
                }
                "${r.method} ${r.path} b=${r.bodyBytes}#${r.bodyHash} c=$cred"
            }
            val diag = entries.joinToString("; ") { d ->
                "${d.kind.name}/${d.method} ${d.endpoint} s=${d.httpStatus} a=${d.apiStatus} ok=${d.ok} at=${d.attempt} rc=${d.requestChars} m=${cap(d.message.toString(), 80)}"
            }
            val other = if (showOther) " other=${b.seen.size}" else ""
            return scrub("reqs=${a.seen.size}$other [$requests] diag=${entries.size} [$diag] outcome=$outcome", op.markers)
        } finally {
            a.close(); b.close()
        }
    }
}

internal object CharRunner {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    /** The recorded line for a case. A case that cannot finish in 20 s is recorded as such; the harness never waits longer. */
    fun observe(case: CharCase): String {
        val future = pool.submit<String> { case.run() }
        return try {
            future.get(20, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            "HARNESS-TIMEOUT-20s"
        } catch (e: java.util.concurrent.ExecutionException) {
            "HARNESS-ERROR " + e.cause?.javaClass?.name + ": " + e.cause?.message
        }
    }
}

/** Compares a fresh observation with the recorded baseline. It records; it does not say what the code should do. */
internal object CharCheck {
    fun assertRecorded(case: CharCase) {
        val expected = CharacterizationBaseline.expected(case.id)
        if (expected == null) fail("no recorded baseline for ${case.id}")
        assertEquals("recorded behavior of ${case.id}", expected, CharRunner.observe(case))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Self-checks of the harness itself (4). They test the fake server, not the providers.

class CharacterizationHarnessSelfCheck {
    @Test fun theServerRecordsMethodPathAndBodyOfEachRequest() {
        CharServer { CharReply.Bytes(CharHttp.response(200, body = "ok")) }.use { s ->
            val c = URL("http://127.0.0.1:${s.port}/p?q=1").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write("abc".toByteArray()) }
            assertEquals(200, c.responseCode)
            assertEquals("ok", c.inputStream.readBytes().toString(Charsets.UTF_8))
            assertEquals(1, s.seen.size)
            assertEquals("POST", s.seen[0].method)
            assertEquals("/p?q=1", s.seen[0].path)
            assertEquals(3, s.seen[0].bodyBytes)
        }
    }

    @Test fun twoServersAreIndependent() {
        CharServer { CharReply.Bytes(CharHttp.response(200)) }.use { a ->
            CharServer { CharReply.Bytes(CharHttp.response(200)) }.use { b ->
                assertTrue(a.port != b.port)
                val c = URL("http://127.0.0.1:${a.port}/").openConnection() as HttpURLConnection
                assertEquals(200, c.responseCode)
                assertEquals(1, a.seen.size)
                assertEquals(0, b.seen.size)
            }
        }
    }

    @Test fun closingWithoutAReplyGivesTheClientAnIoFailure() {
        CharServer { CharReply.Close }.use { s ->
            val c = URL("http://127.0.0.1:${s.port}/").openConnection() as HttpURLConnection
            var failed: IOException? = null
            try { c.responseCode } catch (e: IOException) { failed = e }
            assertTrue("expected an IOException", failed != null)
            assertTrue(s.seen.isNotEmpty())
        }
    }

    @Test fun repeatedHeaderLinesAreWrittenOutVerbatim() {
        val bytes = CharHttp.response(503, listOf("Retry-After: 0", "Retry-After: 7"), "x")
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertEquals(2, Regex("Retry-After: ").findAll(text).count())
        assertTrue(text.startsWith("HTTP/1.1 503 X\r\n"))
        assertTrue(text.contains("Content-Length: 1\r\n"))
    }
}
