package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import dev.jarvis.assistant.diag.NoopSink
import dev.jarvis.assistant.diag.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Talks to any OpenAI-compatible chat API at the address in [profile]. Same rules as the built-in adapters:
 *  - every HTTP attempt is logged as metadata only, under the profile's label, and nothing secret is ever logged;
 *  - the key travels only in the Authorization header, never in the URL;
 *  - a redirect is never followed on ANY request (chat, model list, one-shot): the one [open] switches it off and the one
 *    [httpFailure] turns every 3xx into an error, so nothing is sent a second time and the key goes nowhere else;
 *  - the request URL is built only from the profile, so it always has the profile's path prefix.
 *
 * Differences that are deliberate:
 *  - A request is NEVER sent again automatically ([CallOptions.maxRetries] is ignored). A custom endpoint may charge for
 *    every request, so resending one could double the cost.
 *  - Chat is streamed (SSE). There is no automatic fallback to a non-streamed request; [generateOnce] exists only for
 *    diagnostics, which ask for it explicitly.
 *
 * Nothing in the app creates this for a custom provider yet: custom providers cannot run until their storage, consent and
 * endpoint validation exist. The factory ([ProviderAdapters]) decides who may have one.
 */
class OpenAiCompatibleProvider(
    private val profile: EndpointProfile,
    private val sink: DiagnosticsSink = NoopSink,
    private val clock: () -> Instant = { Instant.now() },
) : LlmProvider, ProviderDiagnostics {

    private inner class Call(
        val kind: CallKind,
        val method: String,
        model: String,
        private val path: String,
        apiKey: String,
        private val requestChars: Int?,
    ) {
        private val secrets = listOf(apiKey)
        private val started = System.currentTimeMillis()
        val safeModel: String = Redactor.clean(model, secrets, 80)
        val endpoint: String = Redactor.clean(profile.displayBase + path, secrets, 200)
        val secretsList: List<String> get() = secrets

        fun ok(status: Int, note: String? = null) {
            sink.record(entry(true, status, null, note?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null }))
        }

        fun failed(status: Int?, apiStatus: String?, message: String?, error: LlmError): LlmException {
            val safe = message?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null }
            sink.record(entry(false, status, apiStatus, safe))
            return LlmException(error, FailureContext(profile.label, method, safeModel, endpoint, status, apiStatus, safe))
        }

        private fun entry(ok: Boolean, status: Int?, apiStatus: String?, message: String?) = DiagnosticEntry(
            timeMillis = System.currentTimeMillis(),
            kind = kind,
            provider = profile.label,
            model = safeModel,
            method = method,
            endpoint = endpoint,
            httpStatus = status,
            apiStatus = apiStatus,
            message = message,
            durationMs = System.currentTimeMillis() - started,
            attempt = 1,
            ok = ok,
            requestChars = requestChars,
        )
    }

    // ------------------------------------------------------------------ streaming generation

    override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow {
        streamOnce(request) { chunk -> emit(chunk) }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamOnce(request: ChatRequest, onText: suspend (String) -> Unit) {
        val call = Call(CallKind.GENERATE_STREAM, "POST", request.model, profile.chatPath, request.apiKey, request.sizeInChars())
        val conn = open(profile.chatUrl(), "POST", request.apiKey, 60_000)
        try {
            withDisconnectOnCancel(conn) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.outputStream.use { it.write(OpenAiCompatibleParsing.buildBody(request, stream = true).toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status != 200) throw httpFailure(call, conn, status, request.model)

                var gotText = false
                val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readBoundedLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val text = try {
                        OpenAiCompatibleParsing.deltaText(payload)
                    } catch (e: LlmException) {
                        throw call.failed(status, null, (e.error as? LlmError.Other)?.message, e.error)
                    }
                    if (text.isNotEmpty()) {
                        gotText = true
                        onText(text)
                    }
                }
                if (!gotText) throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
                call.ok(status)
            }
        } catch (e: IOException) {
            // A cancelled request closes its own socket; that is not a network failure.
            currentCoroutineContext().ensureActive()
            throw call.failed(null, null, e.javaClass.simpleName, ioFailure(e))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ diagnostics calls

    /**
     * Asks the model list (GET .../models) whether the key can see [model]. No generation.
     *  - 404 or 405, no model list configured, or a 200 that is not a model list: [LlmError.ModelListUnavailable]. The
     *    model could not be checked; that is neither a pass nor a connection failure.
     *  - any other non-200 (401, 403, 5xx, ...) is a real failure, as for every provider.
     */
    override suspend fun checkModel(apiKey: String, model: String): ModelInfo = withContext(Dispatchers.IO) {
        val path = profile.modelsPath
        val url = profile.modelsUrl()
        if (path == null || url == null) {
            throw LlmException(LlmError.ModelListUnavailable(null), FailureContext(profile.label, "GET", Redactor.clean(model, listOf(apiKey), 80), profile.displayBase, null, null, null))
        }
        val call = Call(CallKind.AVAILABILITY, "GET", model, path, apiKey, null)
        val conn = open(url, "GET", apiKey, 20_000)
        try {
            val status = conn.responseCode
            if (status == 404 || status == 405) {
                throw call.failed(status, "model-list-unavailable", "the server has no model list", LlmError.ModelListUnavailable(status))
            }
            if (status != 200) throw httpFailure(call, conn, status, model)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readBounded() }
            val ids = OpenAiCompatibleParsing.modelIds(body)
                ?: throw call.failed(status, "model-list-unavailable", "the reply is not a model list", LlmError.ModelListUnavailable(status))
            if (model !in ids) {
                throw call.failed(status, null, "the model is not in the provider's model list", LlmError.ModelNotFound(model))
            }
            call.ok(status)
            ModelInfo(model, null, listOf("chat.completions"))
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, ioFailure(e))
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun generateOnce(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val call = Call(CallKind.GENERATE_ONCE, "POST", request.model, profile.chatPath, request.apiKey, request.sizeInChars())
        val conn = open(profile.chatUrl(), "POST", request.apiKey, 60_000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(OpenAiCompatibleParsing.buildBody(request, stream = false).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, request.model)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readBounded() }
            val text = OpenAiCompatibleParsing.messageText(body)
            if (text.isEmpty()) throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
            call.ok(status)
            text
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, ioFailure(e))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** The only place a connection is opened, so every request gets the same protections. */
    private fun open(url: String, method: String, apiKey: String, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        // Never follow a redirect: it would send this request, and its key, somewhere nobody chose. See RedirectRefusal.
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = readTimeoutMs
        // The key goes in a header, never in the URL.
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("User-Agent", "jarvis-android")
        return conn
    }

    /** The only place a non-200 answer becomes an error, so a redirect is refused for every kind of request. */
    private fun httpFailure(call: Call, conn: HttpURLConnection, status: Int, model: String): LlmException {
        if (RedirectRefusal.isRedirect(status)) {
            val refusal = RedirectRefusal.error(status, conn.getHeaderField("Location"), call.secretsList)
            return call.failed(status, null, refusal.message, refusal)
        }
        val body = readErrorBody(conn)
        val parsed = OpenAiCompatibleParsing.parseFailure(status, body, conn.getHeaderField("retry-after"), model, call.secretsList)
        val message = parsed.apiMessage ?: (parsed.error as? LlmError.NotFromApi)?.snippet
        return call.failed(status, parsed.apiStatus, message, parsed.error)
    }

    private fun readErrorBody(conn: HttpURLConnection): String {
        val stream = conn.errorStream ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(20_000)
            val n = reader.read(buffer)
            if (n > 0) String(buffer, 0, n) else ""
        }
    }
}
