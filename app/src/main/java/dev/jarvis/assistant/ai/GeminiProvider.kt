package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.diag.DiagnosticsSink
import dev.jarvis.assistant.diag.NoopSink
import dev.jarvis.assistant.diag.Redactor
import dev.jarvis.assistant.settings.AiProvider
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
import java.net.URLEncoder
import java.time.Instant
import kotlin.random.Random

/**
 * Talks to the Gemini Developer API (v1beta). Every HTTP attempt is recorded in [sink] as metadata only:
 * never the key, the prompt or the reply.
 */
class GeminiProvider(
    private val sink: DiagnosticsSink = NoopSink,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val clock: () -> Instant = { Instant.now() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : LlmProvider, ProviderDiagnostics {

    companion object {
        const val NAME = "Google Gemini"
    }

    private val host: String get() = baseUrl.removePrefix("https://").removePrefix("http://")

    /** Bookkeeping for one HTTP attempt: knows what to record and how to build the failure. */
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

        fun ok(status: Int) {
            sink.record(entry(true, status, null, null))
        }

        fun failed(status: Int?, apiStatus: String?, message: String?, error: LlmError): LlmException {
            val safeMessage = message?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null }
            sink.record(entry(false, status, apiStatus, safeMessage))
            return LlmException(
                error,
                FailureContext(NAME, method, safeModel, endpoint, status, apiStatus, safeMessage),
            )
        }

        fun secretsList(): List<String> = secrets

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
                // Retry only "Google is overloaded", only before any text was delivered, and never beyond the limit.
                val canRetry = !emitted && RetryPolicy.shouldRetry(e.error) && attempt <= options.maxRetries
                if (!canRetry) throw e
                val wait = RetryPolicy.delayBeforeRetry(attempt, random)
                options.events.onRetry(attempt, options.maxRetries, wait)
                sleeper(wait)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamOnce(request: ChatRequest, attempt: Int, onText: suspend (String) -> Unit) {
        val path = "/v1beta/models/${encode(request.model)}:streamGenerateContent?alt=sse"
        val call = Call(CallKind.GENERATE_STREAM, "POST", request.model, path, request.apiKey, request.sizeInChars(), attempt)
        val conn = open(path, "POST", request.apiKey, 60_000)
        try {
            withDisconnectOnCancel(conn) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(GeminiParsing.buildBody(request).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, status, readErrorBody(conn), request.model)

            var gotText = false
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val text = try {
                    GeminiParsing.textOf(payload)
                } catch (e: LlmException) {
                    throw call.failed(status, null, describeBlock(e.error), e.error)
                }
                if (text.isNotEmpty()) {
                    gotText = true
                    onText(text)
                }
            }
            if (!gotText) {
                throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
            }
            call.ok(status)
            }
        } catch (e: IOException) {
            // A cancelled request closes its own socket; that is not a network failure.
            currentCoroutineContext().ensureActive()
            throw call.failed(null, null, e.javaClass.simpleName, LlmError.Offline)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ diagnostics calls

    override suspend fun checkModel(apiKey: String, model: String): ModelInfo = withContext(Dispatchers.IO) {
        val path = "/v1beta/models/${encode(model)}"
        val call = Call(CallKind.AVAILABILITY, "GET", model, path, apiKey, null, 1)
        val conn = open(path, "GET", apiKey, 20_000)
        try {
            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, status, readErrorBody(conn), model)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val info = GeminiParsing.parseModelInfo(body, model)
            call.ok(status)
            info
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, LlmError.Offline)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun generateOnce(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val path = "/v1beta/models/${encode(request.model)}:generateContent"
        val call = Call(CallKind.GENERATE_ONCE, "POST", request.model, path, request.apiKey, request.sizeInChars(), 1)
        val conn = open(path, "POST", request.apiKey, 60_000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(GeminiParsing.buildBody(request).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, status, readErrorBody(conn), request.model)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = try {
                GeminiParsing.textOf(body)
            } catch (e: LlmException) {
                throw call.failed(status, null, describeBlock(e.error), e.error)
            }
            if (text.isEmpty()) {
                throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
            }
            call.ok(status)
            text
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, LlmError.Offline)
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
        // The key goes in a header, never in the URL, so it can't end up in logs or history.
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.setRequestProperty("User-Agent", "jarvis-android")
        return conn
    }

    private fun httpFailure(call: Call, status: Int, body: String, model: String): LlmException {
        val parsed = GeminiParsing.parseFailure(status, body, model, clock(), call.secretsList())
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

    private fun describeBlock(error: LlmError): String = when (error) {
        is LlmError.Blocked -> "blocked: ${error.reason}"
        is LlmError.Other -> error.message
        else -> "unexpected reply"
    }

    private fun encode(model: String): String = URLEncoder.encode(model, "UTF-8")
}

object Providers {
    private val gemini = GeminiProvider(DiagnosticsLog)
    private val groq = GroqProvider(DiagnosticsLog)
    private val openRouter = OpenRouterProvider(DiagnosticsLog)
    private val cloudflare = CloudflareProvider(DiagnosticsLog)

    fun openRouter(): OpenRouterProvider = openRouter

    fun forProvider(provider: AiProvider): LlmProvider = when (provider) {
        AiProvider.GEMINI -> gemini
        AiProvider.GROQ -> groq
        AiProvider.OPENROUTER -> openRouter
        AiProvider.CLOUDFLARE -> cloudflare
    }

    fun diagnosticsFor(provider: AiProvider): ProviderDiagnostics = when (provider) {
        AiProvider.GEMINI -> gemini
        AiProvider.GROQ -> groq
        AiProvider.OPENROUTER -> openRouter
        AiProvider.CLOUDFLARE -> cloudflare
    }
}
