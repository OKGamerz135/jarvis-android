package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * C1a: provider calls carry the API key, so a redirect must never make JARVIS talk to anybody else.
 *
 * Two local servers stand in for the network. Server A is "the provider" and answers every request with a redirect.
 * Server B is "somebody else". After each call we ask: did B receive anything (and with what credential)? Did A
 * receive more than the one request we meant to send? Did the call end as an error and not as a reply?
 *
 * The same scenarios were run against the unchanged Build 47 Phase B code first; see the C1a report for the result.
 */
class ProviderRedirectTest {

    private class Seen(val method: String, val path: String, val headers: Map<String, String>)

    /** A tiny raw HTTP server: records every request and answers it with whatever [reply] returns. */
    private class FakeServer(private val reply: (Seen) -> ByteArray) : AutoCloseable {
        val seen = CopyOnWriteArrayList<Seen>()
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
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
                        val headers = LinkedHashMap<String, String>()
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            headers[h.substringBefore(":").lowercase()] = h.substringAfter(":").trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val sent = ByteArray(length)
                        var read = 0
                        while (read < length) { val n = input.read(sent, read, length - read); if (n < 0) break; read += n }
                        val request = Seen(first[0], first.getOrElse(1) { "" }, headers)
                        seen += request
                        socket.getOutputStream().apply { write(reply(request)); flush() }
                    } catch (e: Exception) {
                        // a client that hangs up early is fine
                    } finally {
                        try { socket.close() } catch (e: Exception) {}
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun raw(status: Int, headers: List<String> = emptyList(), body: String = "", contentType: String = "application/json"): ByteArray {
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status X\r\nContent-Type: $contentType\r\n" + headers.joinToString("") { "$it\r\n" } +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray() + bytes
    }

    // ------------------------------------------------------------------ the operations under test

    private val geminiKey = "AIzaSyFAKEKEYFORTESTING1234567890abcd"
    private val groqKey = "gsk_FAKEKEYFORTESTING1234567890abcdefgh"
    private val zaiKey = "zai-FAKEKEYFORTESTING1234567890abcdefgh"
    private val orKey = "sk-or-v1-FAKEKEYFORTESTING1234567890abcdefgh"
    private val cfToken = "CFTOKEN_FAKE_1234567890abcdefghij"
    private val cfKey = "0123456789abcdef0123456789abcdef:$cfToken"

    /** [markers] are the secret strings that must never reach server B (or any message). */
    private class Op(val name: String, val markers: List<String>, val run: (baseUrl: String, sink: DiagnosticsSink, texts: MutableList<String>) -> Unit)

    private fun req(model: String, key: String) = ChatRequest(model, key, "sys", listOf(ChatMessage(ChatRole.USER, "hi")))

    private fun ops(): List<Op> {
        fun collecting(p: LlmProvider, r: ChatRequest, texts: MutableList<String>) =
            runBlocking { texts += p.stream(r, CallOptions(maxRetries = 2)).toList() }

        val gm = "gemini-test"
        val gq = "llama-test"
        val zm = "glm-4.7-flash"
        val cm = "@cf/zai-org/glm-4.7-flash"
        val om = "vendor/model:free"
        return listOf(
            Op("Gemini stream", listOf(geminiKey)) { b, s, t -> collecting(GeminiProvider(s, b), req(gm, geminiKey), t) },
            Op("Gemini checkModel", listOf(geminiKey)) { b, s, _ -> runBlocking { GeminiProvider(s, b).checkModel(geminiKey, gm) } },
            Op("Gemini generateOnce", listOf(geminiKey)) { b, s, _ -> runBlocking { GeminiProvider(s, b).generateOnce(req(gm, geminiKey)) } },

            Op("Groq stream", listOf(groqKey)) { b, s, t -> collecting(GroqProvider(s, b), req(gq, groqKey), t) },
            Op("Groq checkModel", listOf(groqKey)) { b, s, _ -> runBlocking { GroqProvider(s, b).checkModel(groqKey, gq) } },
            Op("Groq generateOnce", listOf(groqKey)) { b, s, _ -> runBlocking { GroqProvider(s, b).generateOnce(req(gq, groqKey)) } },

            Op("Z.ai stream", listOf(zaiKey)) { b, s, t -> collecting(ZaiProvider(s, b), req(zm, zaiKey), t) },
            Op("Z.ai generateOnce", listOf(zaiKey)) { b, s, _ -> runBlocking { ZaiProvider(s, b).generateOnce(req(zm, zaiKey)) } },

            Op("Cloudflare stream", listOf(cfToken)) { b, s, t -> collecting(CloudflareProvider(s, b), req(cm, cfKey), t) },
            Op("Cloudflare checkModel", listOf(cfToken)) { b, s, _ -> runBlocking { CloudflareProvider(s, b).checkModel(cfKey, cm) } },
            Op("Cloudflare generateOnce", listOf(cfToken)) { b, s, _ -> runBlocking { CloudflareProvider(s, b).generateOnce(req(cm, cfKey)) } },

            Op("OpenRouter stream", listOf(orKey)) { b, s, t -> collecting(OpenRouterProvider(s, b), req(om, orKey), t) },
            Op("OpenRouter stream (auto-free: model list)", listOf(orKey)) { b, s, t ->
                collecting(OpenRouterProvider(s, b), req(OpenRouterParsing.AUTO, orKey), t)
            },
            Op("OpenRouter checkModel", listOf(orKey)) { b, s, _ -> runBlocking { OpenRouterProvider(s, b).checkModel(orKey, om) } },
            Op("OpenRouter generateOnce", listOf(orKey)) { b, s, _ -> runBlocking { OpenRouterProvider(s, b).generateOnce(req(om, orKey)) } },
            Op("OpenRouter freeQuota", listOf(orKey)) { b, s, _ -> runBlocking { OpenRouterProvider(s, b).freeQuota(orKey) } },
        )
    }

    // ------------------------------------------------------------------ the scenarios

    private class Scenario(val name: String, val status: Int, val location: (aPort: Int, bPort: Int) -> String?, val body: String = "", val contentType: String = "application/json")

    /** A body that looks like a valid reply in every provider's format. A redirect must never be read as one. */
    private val looksLikeSuccess =
        """{"choices":[{"message":{"content":"HIJACKED"},"delta":{"content":"HIJACKED"}}],""" +
            """"candidates":[{"content":{"parts":[{"text":"HIJACKED"}]}}],"data":[{"id":"x","active":true}]}"""

    private fun runScenario(sc: Scenario, otherHostMustStayUntouched: Boolean): List<String> {
        val failures = ArrayList<String>()
        lateinit var a: FakeServer
        FakeServer { raw(200, body = "{}") }.use { b ->
            a = FakeServer {
                val headers = sc.location(a.port, b.port)?.let { listOf("Location: $it") } ?: emptyList()
                raw(sc.status, headers, sc.body, sc.contentType)
            }
            a.use { _ ->
                for (op in ops()) {
                    a.seen.clear(); b.seen.clear()
                    val entries = CopyOnWriteArrayList<DiagnosticEntry>()
                    val sink = object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { entries += entry } }
                    val texts = ArrayList<String>()
                    val thrown = try { op.run("http://127.0.0.1:${a.port}", sink, texts); null } catch (t: Throwable) { t }
                    val tag = "[${sc.name} / ${op.name}]"

                    // 1. The other host must not be contacted at all, and so cannot receive a credential.
                    if (otherHostMustStayUntouched && b.seen.isNotEmpty()) {
                        val leaked = b.seen.count { r -> r.headers.values.any { v -> op.markers.any { m -> m in v } } }
                        failures += "$tag the redirect target received ${b.seen.size} request(s); " +
                            "$leaked of them carried the credential header (${b.seen.map { it.method }})"
                    }
                    // 2. No second request, not even to the original host.
                    if (a.seen.size != 1) failures += "$tag the provider host received ${a.seen.size} requests instead of 1"
                    // 3. The call must end as an error, never as a reply.
                    if (thrown == null) {
                        failures += "$tag finished without an error (texts=$texts)"
                    } else if (thrown !is LlmException) {
                        failures += "$tag threw ${thrown.javaClass.simpleName} instead of LlmException"
                    } else {
                        val err = thrown.error
                        val msg = (err as? LlmError.Other)?.message.orEmpty()
                        if (err !is LlmError.Other || !msg.contains("redirect", ignoreCase = true)) {
                            failures += "$tag error was $err, expected a clear redirect refusal"
                        }
                        // 4. The text the user and the diagnostics screen see must not leak anything.
                        val shown = listOfNotNull(msg, thrown.context?.apiMessage, thrown.context?.endpoint) + entries.mapNotNull { it.message }
                        for (s in shown) {
                            for (bad in op.markers + listOf("/stolen", "x=1", "user:pass", "token=")) {
                                if (bad in s) failures += "$tag visible text contains \"$bad\": $s"
                            }
                        }
                    }
                    if (texts.isNotEmpty()) failures += "$tag emitted text from a redirect response: $texts"
                    if (entries.none { !it.ok && it.httpStatus == sc.status }) {
                        failures += "$tag diagnostics did not record a failed ${sc.status} (entries=${entries.map { "${it.ok}/${it.httpStatus}" }})"
                    }
                }
            }
        }
        return failures
    }

    private fun assertNoFailures(failures: List<String>) {
        assertTrue("${failures.size} problem(s):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private val toOther: (Int, Int) -> String? = { _, b -> "http://127.0.0.1:$b/stolen?x=1" }

    @Test fun everyRedirectStatusToAnotherHostIsRefusedByEveryProviderCall() {
        val failures = ArrayList<String>()
        for (status in listOf(301, 302, 303, 307, 308)) {
            failures += runScenario(Scenario("HTTP $status to another host", status, toOther), true)
        }
        assertNoFailures(failures)
    }

    @Test fun otherThreeHundredResponsesAreAlsoErrorsNotReplies() {
        val failures = ArrayList<String>()
        for (status in listOf(300, 304, 305)) {
            failures += runScenario(Scenario("HTTP $status", status, toOther), true)
        }
        assertNoFailures(failures)
    }

    @Test fun aRedirectWithNoLocationIsStillAnError() {
        assertNoFailures(runScenario(Scenario("302 without Location", 302, { _, _ -> null }), true))
    }

    @Test fun aRedirectBackToTheSameHostIsNotFollowedEither() {
        val failures = ArrayList<String>()
        failures += runScenario(Scenario("302 to another path on the same host", 302, { a, _ -> "http://127.0.0.1:$a/other" }), true)
        failures += runScenario(Scenario("302 relative Location", 302, { _, _ -> "/other" }), true)
        failures += runScenario(Scenario("302 loop (redirects to itself)", 302, { a, _ -> "http://127.0.0.1:$a/" }), true)
        assertNoFailures(failures)
    }

    @Test fun aRedirectWhoseBodyLooksLikeASuccessfulReplyIsNotTreatedAsOne() {
        val failures = ArrayList<String>()
        failures += runScenario(Scenario("302 with a reply-shaped JSON body", 302, toOther, looksLikeSuccess), true)
        failures += runScenario(
            Scenario("307 with a reply-shaped event stream", 307, toOther, "data: $looksLikeSuccess\n\ndata: [DONE]\n\n", "text/event-stream"),
            true,
        )
        assertNoFailures(failures)
    }

    @Test fun redirectTextShowsOnlyAHostNeverTheUserinfoPathQueryOrKey() {
        // The redirect target is hostile: userinfo, a path, and a query that echoes a credential.
        val hostile: (Int, Int) -> String? = { _, b -> "http://user:pass@127.0.0.1:$b/stolen?x=1&token=${geminiKey}" }
        assertNoFailures(runScenario(Scenario("302 to a hostile address", 302, hostile), true))
    }
}
