package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.CallKind
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.DiagnosticsSink
import dev.jarvis.assistant.diag.NoopSink
import dev.jarvis.assistant.diag.Redactor
import kotlinx.coroutines.Dispatchers
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
 * Talks to OpenRouter's OpenAI-compatible API, free models only. Same rules as the other adapters: every HTTP attempt is
 * logged as metadata only, the key travels only in a header, and retries happen only for "overloaded".
 *
 * Two things are specific to OpenRouter: it refuses (before any request) a model whose id doesn't end in ":free", and on
 * a 429 it asks GET /api/v1/key how many free-model requests are left today to tell "daily quota used up" from a
 * short-lived limit.
 */
class OpenRouterProvider(
    private val sink: DiagnosticsSink = NoopSink,
    private val baseUrl: String = "https://openrouter.ai",
    private val clock: () -> Instant = { Instant.now() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : LlmProvider, ProviderDiagnostics {

    companion object {
        const val NAME = "OpenRouter"
        private const val CHAT_PATH = "/api/v1/chat/completions"
        private const val MODELS_PATH = "/api/v1/models"
        private const val KEY_PATH = "/api/v1/key"
        private const val AUTO_CACHE_MS = 15 * 60 * 1000L
    }

    private val host: String get() = baseUrl.removePrefix("https://").removePrefix("http://")

    @Volatile private var autoCache: Pair<Long, String>? = null

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

    // ------------------------------------------------------------------ model choice (free only)

    /** Turns the saved model setting into a model id that is certainly free, or throws. */
    private suspend fun resolveModel(model: String, apiKey: String): String {
        val wanted = model.trim()
        if (wanted.isEmpty() || wanted == OpenRouterParsing.AUTO) return autoModel(apiKey)
        if (!OpenRouterParsing.isFreeId(wanted)) {
            throw LlmException(
                LlmError.Other(
                    "\"${Redactor.clean(wanted, listOf(apiKey), 80)}\" is not a free model. JARVIS only uses OpenRouter models whose id " +
                        "ends in \":free\", so it can never spend credits. Pick a :free model or use auto-free.",
                ),
                FailureContext(NAME, "POST", Redactor.clean(wanted, listOf(apiKey), 80), "$host$CHAT_PATH", null, null, null),
            )
        }
        return wanted
    }

    private suspend fun autoModel(apiKey: String): String {
        autoCache?.let { (at, id) -> if (System.currentTimeMillis() - at < AUTO_CACHE_MS) return id }
        val body = fetchModels(apiKey)
        val pick = OpenRouterParsing.pickAuto(OpenRouterParsing.freeModels(body, clock()))
            ?: throw LlmException(
                LlmError.Other("OpenRouter lists no free text model right now (auto-free found nothing). Try again later or type a :free model id."),
                FailureContext(NAME, "GET", OpenRouterParsing.AUTO, "$host$MODELS_PATH", 200, null, null),
            )
        autoCache = System.currentTimeMillis() to pick.id
        return pick.id
    }

    private suspend fun fetchModels(apiKey: String): String = withContext(Dispatchers.IO) {
        val call = Call(CallKind.AVAILABILITY, "GET", OpenRouterParsing.AUTO, MODELS_PATH, apiKey, null, 1)
        val conn = open(MODELS_PATH, "GET", apiKey, 20_000)
        try {
            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, OpenRouterParsing.AUTO, enrich = false)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            call.ok(status)
            text
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, LlmError.Offline)
        } finally {
            conn.disconnect()
        }
    }

    /** What OpenRouter says is left of today's free-model requests. Uses no generation quota. */
    suspend fun freeQuota(apiKey: String): OpenRouterParsing.FreeQuota = withContext(Dispatchers.IO) {
        val call = Call(CallKind.AVAILABILITY, "GET", "key info", KEY_PATH, apiKey, null, 1)
        val conn = open(KEY_PATH, "GET", apiKey, 20_000)
        try {
            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, "key info", enrich = false)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val q = OpenRouterParsing.parseKey(text)
                ?: throw call.failed(status, null, "no free-model counter in the reply", LlmError.Other("OpenRouter's key info had no free-model counter."))
            call.ok(status, "free requests used ${q.used} of ${q.limit}")
            q
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, LlmError.Offline)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ streaming generation

    override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow {
        val model = resolveModel(request.model, request.apiKey)
        val req = request.copy(model = model)
        var emitted = false
        var attempt = 0
        while (true) {
            attempt++
            try {
                streamOnce(req, attempt) { chunk ->
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
        val call = Call(CallKind.GENERATE_STREAM, "POST", request.model, CHAT_PATH, request.apiKey, request.sizeInChars(), attempt)
        val conn = open(CHAT_PATH, "POST", request.apiKey, 90_000)
        try {
            withDisconnectOnCancel(conn) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.outputStream.use { it.write(OpenRouterParsing.buildBody(request, request.model, stream = true).toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status != 200) throw httpFailure(call, conn, status, request.model, enrich = true, apiKey = request.apiKey)

                var gotText = false
                val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    // Lines starting with ":" are keepalive comments (": OPENROUTER PROCESSING"); only "data:" matters.
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    when (val item = OpenRouterParsing.streamItem(payload)) {
                        is OpenRouterParsing.StreamItem.Text -> {
                            gotText = true
                            onText(item.text)
                        }
                        is OpenRouterParsing.StreamItem.Error -> throw inStreamFailure(call, status, item, request)
                        OpenRouterParsing.StreamItem.Nothing -> {}
                    }
                }
                if (!gotText) throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
                call.ok(status, "free model")
            }
        } catch (e: IOException) {
            // A cancelled request closes its own socket; that is not a network failure.
            currentCoroutineContext().ensureActive()
            throw call.failed(null, null, e.javaClass.simpleName, LlmError.Offline)
        } finally {
            conn.disconnect()
        }
    }

    /** An error delivered inside the stream (HTTP status was already 200). */
    private suspend fun inStreamFailure(call: Call, status: Int, item: OpenRouterParsing.StreamItem.Error, request: ChatRequest): LlmException {
        val error = if (OpenRouterParsing.isRateLimit(item.code, item.message)) {
            classifyRateLimit(null, request.apiKey)
        } else {
            OpenRouterParsing.errorFromItem(item, request.model, call.secretsList)
        }
        return call.failed(status, item.code, item.message ?: "error inside the stream", error)
    }

    // ------------------------------------------------------------------ diagnostics calls

    override suspend fun checkModel(apiKey: String, model: String): ModelInfo = withContext(Dispatchers.IO) {
        val wanted = model.trim().ifEmpty { OpenRouterParsing.AUTO }
        val body = fetchModels(apiKey)
        if (wanted == OpenRouterParsing.AUTO) {
            val pick = OpenRouterParsing.pickAuto(OpenRouterParsing.freeModels(body, clock()))
                ?: throw LlmException(LlmError.Other("OpenRouter lists no free text model right now."))
            return@withContext ModelInfo(pick.id, null, listOf("chat.completions"))
        }
        if (!OpenRouterParsing.isFreeId(wanted)) throw resolveFailure(wanted, apiKey)
        when (val l = OpenRouterParsing.lookup(body, wanted, clock())) {
            is OpenRouterParsing.Lookup.Free -> ModelInfo(l.model.id, null, listOf("chat.completions"))
            OpenRouterParsing.Lookup.NotListed -> throw LlmException(LlmError.ModelNotFound(wanted))
            OpenRouterParsing.Lookup.NotFree ->
                throw LlmException(LlmError.Other("\"$wanted\" is not listed as free (a price is not zero). JARVIS will not use it."))
            OpenRouterParsing.Lookup.Expired ->
                throw LlmException(LlmError.Other("\"$wanted\" has passed its expiration date and may be removed."))
        }
    }

    private suspend fun resolveFailure(wanted: String, apiKey: String): LlmException =
        try {
            resolveModel(wanted, apiKey)
            LlmException(LlmError.Other("Unexpected model check result."))
        } catch (e: LlmException) {
            e
        }

    override suspend fun generateOnce(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val model = resolveModel(request.model, request.apiKey)
        val call = Call(CallKind.GENERATE_ONCE, "POST", model, CHAT_PATH, request.apiKey, request.sizeInChars(), 1)
        val conn = open(CHAT_PATH, "POST", request.apiKey, 90_000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(OpenRouterParsing.buildBody(request, model, stream = false).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, model, enrich = true, apiKey = request.apiKey)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            when (val r = OpenRouterParsing.messageResult(body)) {
                is OpenRouterParsing.StreamItem.Text -> {
                    call.ok(status, "free model")
                    r.text
                }
                is OpenRouterParsing.StreamItem.Error -> {
                    val err = if (OpenRouterParsing.isRateLimit(r.code, r.message)) classifyRateLimit(null, request.apiKey)
                    else OpenRouterParsing.errorFromItem(r, model, call.secretsList)
                    throw call.failed(status, r.code, r.message, err)
                }
                OpenRouterParsing.StreamItem.Nothing ->
                    throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
            }
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
        // The key goes in a header, never in the URL.
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("User-Agent", "jarvis-android")
        return conn
    }

    private suspend fun httpFailure(
        call: Call,
        conn: HttpURLConnection,
        status: Int,
        model: String,
        enrich: Boolean,
        apiKey: String? = null,
    ): LlmException {
        val body = readErrorBody(conn)
        val parsed = OpenRouterParsing.parseFailure(
            status, body, conn.getHeaderField("retry-after"), conn.getHeaderField("x-ratelimit-reset"),
            model, clock(), call.secretsList,
        )
        var error = parsed.error
        if (enrich && error is LlmError.Unclear429 && apiKey != null) {
            error = classifyRateLimit(error.retryAfterSeconds, apiKey)
        }
        val message = parsed.apiMessage ?: (error as? LlmError.NotFromApi)?.snippet
        return call.failed(status, parsed.apiStatus, message, error)
    }

    /**
     * A 429 doesn't say which limit it was. OpenRouter's key endpoint does: if no free-model requests are left today,
     * the daily quota is used up (reset: UTC midnight, from the docs); if some are left, it was a short-lived limit.
     * If that lookup itself fails, the error stays "unclear" and nothing is switched.
     */
    private suspend fun classifyRateLimit(retryAfterSeconds: Long?, apiKey: String): LlmError {
        val quota = try { freeQuota(apiKey) } catch (e: LlmException) { null }
        return when {
            quota == null -> LlmError.Unclear429(retryAfterSeconds)
            quota.remaining <= 0 ->
                LlmError.QuotaExhausted(OpenRouterParsing.nextUtcMidnight(clock()), "free-models-per-day", ResetSource.DOCUMENTED)
            retryAfterSeconds != null -> LlmError.RateLimited(retryAfterSeconds)
            else -> LlmError.Unclear429(null)
        }
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
