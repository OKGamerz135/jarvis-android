package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import dev.jarvis.assistant.diag.NoopSink
import dev.jarvis.assistant.diag.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.random.Random

/**
 * Talks to Z.ai's chat API (free GLM Flash models only). Same rules as [GeminiProvider]: every HTTP attempt is logged as
 * metadata only, the key travels only in a header, and retries happen only for "overloaded".
 */
class ZaiProvider(
    private val sink: DiagnosticsSink = NoopSink,
    private val baseUrl: String = "https://api.z.ai",
    private val clock: () -> Instant = { Instant.now() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : LlmProvider, ProviderDiagnostics {

    companion object {
        const val NAME = "Z.ai GLM"
        private const val CHAT_PATH = "/api/paas/v4/chat/completions"
    }

    private val host: String get() = baseUrl.removePrefix("https://").removePrefix("http://")

    private inner class Call(
        val kind: CallKind,
        val method: String,
        model: String,
        path: String,
        apiKey: String,
        private val requestChars: Int?,
        private val attempt: Int,
    ) {
        private val secrets = listOf(apiKey)
        private val started = System.currentTimeMillis()
        val safeModel: String = Redactor.clean(model, secrets, 80)
        val endpoint: String = Redactor.clean("$host$path", secrets, 200)
        val secretsList: List<String> get() = secrets

        fun ok(status: Int, note: String? = null) {
            sink.record(entry(true, status, null, note?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null }))
        }

        fun failed(status: Int?, apiStatus: String?, message: String?, error: LlmError): LlmException {
            val safe = message?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null }
            sink.record(entry(false, status, apiStatus, safe))
            return LlmException(error, FailureContext(NAME, method, safeModel, endpoint, status, apiStatus, safe))
        }

        private fun entry(ok: Boolean, status: Int?, apiStatus: String?, message: String?) = DiagnosticEntry(
            timeMillis = System.currentTimeMillis(),
            kind = kind,
            provider = NAME,
            model = safeModel,
            method = method,
            endpoint = endpoint,
            httpStatus = status,
            apiStatus = apiStatus,
            message = message,
            durationMs = System.currentTimeMillis() - started,
            attempt = attempt,
            ok = ok,
            requestChars = requestChars,
        )
    }

    // ------------------------------------------------------------------ streaming generation

    override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow {
        requireFreeModel(request.model)
        var emitted = false
        var attempt = 0
        while (true) {
            attempt++
            try {
                streamOnce(request, attempt) { chunk ->
                    emitted = true
                    emit(chunk)
                }
                return@flow
            } catch (e: LlmException) {
                val canRetry = !emitted && RetryPolicy.shouldRetry(e.error) && attempt <= options.maxRetries
                if (!canRetry) throw e
                val wait = RetryPolicy.delayBeforeRetry(attempt, random)
                options.events.onRetry(attempt, options.maxRetries, wait)
                sleeper(wait)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamOnce(request: ChatRequest, attempt: Int, onText: suspend (String) -> Unit) {
        val path = CHAT_PATH
        val call = Call(CallKind.GENERATE_STREAM, "POST", request.model, path, request.apiKey, request.sizeInChars(), attempt)
        val conn = open(path, "POST", request.apiKey, 60_000)
        try {
            withDisconnectOnCancel(conn) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(ZaiParsing.buildBody(request, stream = true).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, request.model)

            var gotText = false
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val text = try {
                    ZaiParsing.deltaText(payload)
                } catch (e: LlmException) {
                    throw call.failed(status, null, (e.error as? LlmError.Other)?.message, e.error)
                }
                if (text.isNotEmpty()) {
                    gotText = true
                    onText(text)
                }
            }
            if (!gotText) throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
            call.ok(status, limitsNote(conn))
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
     * Z.ai documents no model-list call, so nothing is sent: this only checks that the model is one of the free ones.
     * The "Test message" button is what proves the key works (it sends one tiny generation).
     */
    override suspend fun checkModel(apiKey: String, model: String): ModelInfo {
        requireFreeModel(model)
        return ModelInfo(model.trim(), null, listOf("chat.completions"))
    }

    private fun requireFreeModel(model: String) {
        if (!ZaiParsing.isFreeModel(model)) {
            throw LlmException(
                LlmError.Unsupported(
                    "\"${Redactor.clean(model, emptyList(), 60)}\" is not one of the free Z.ai models " +
                        "(${ZaiParsing.FREE_MODELS.joinToString()}). JARVIS only calls free models so nothing can be billed.",
                ),
            )
        }
    }

    override suspend fun generateOnce(request: ChatRequest): String = withContext(Dispatchers.IO) {
        requireFreeModel(request.model)
        val path = CHAT_PATH
        val call = Call(CallKind.GENERATE_ONCE, "POST", request.model, path, request.apiKey, request.sizeInChars(), 1)
        val conn = open(path, "POST", request.apiKey, 60_000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(ZaiParsing.buildBody(request, stream = false).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, request.model)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = ZaiParsing.messageText(body)
            if (text.isEmpty()) throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
            call.ok(status, limitsNote(conn))
            text
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, ioFailure(e))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun open(path: String, method: String, apiKey: String, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = readTimeoutMs
        // The key goes in a header, never in the URL.
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("User-Agent", "jarvis-android")
        return conn
    }

    private fun httpFailure(call: Call, conn: HttpURLConnection, status: Int, model: String): LlmException {
        val body = readErrorBody(conn)
        val parsed = ZaiParsing.parseFailure(status, body, conn.getHeaderField("retry-after"), model, clock(), call.secretsList)
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

    /** Z.ai documents no rate-limit headers, so there is nothing to note. */
    @Suppress("UNUSED_PARAMETER")
    private fun limitsNote(conn: HttpURLConnection): String? = null
}
