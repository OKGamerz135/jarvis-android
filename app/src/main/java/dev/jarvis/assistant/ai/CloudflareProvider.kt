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
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.random.Random

/**
 * Talks to Cloudflare Workers AI through its OpenAI-compatible chat endpoint. The saved key is "ACCOUNT_ID:API_TOKEN".
 * Same rules as the other adapters: every HTTP attempt is logged as metadata only, secrets travel only in a header
 * (the account ID is part of the URL path, so it is replaced by {account} in every log line), and retries happen only
 * for "overloaded". On the free Workers plan, a used-up allowance means failed requests, never charges.
 */
class CloudflareProvider(
    private val sink: DiagnosticsSink = NoopSink,
    private val baseUrl: String = "https://api.cloudflare.com",
    private val clock: () -> Instant = { Instant.now() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : LlmProvider, ProviderDiagnostics {

    companion object {
        const val NAME = "Cloudflare Workers AI"
    }

    private val host: String get() = baseUrl.removePrefix("https://").removePrefix("http://")

    private fun chatPath(c: CloudflareParsing.Credentials) = "/client/v4/accounts/${c.accountId}/ai/v1/chat/completions"
    private fun searchPath(c: CloudflareParsing.Credentials) = "/client/v4/accounts/${c.accountId}/ai/models/search"

    private fun credentialsOrThrow(apiKey: String, model: String): CloudflareParsing.Credentials =
        CloudflareParsing.credentials(apiKey) ?: throw LlmException(
            LlmError.Other(CloudflareParsing.BAD_KEY_MESSAGE),
            FailureContext(NAME, "POST", Redactor.clean(model, emptyList(), 80), "$host/client/v4/accounts/{account}/ai/v1/chat/completions", null, null, null),
        )

    private inner class Call(
        val kind: CallKind,
        val method: String,
        model: String,
        private val path: String,
        val creds: CloudflareParsing.Credentials,
        private val requestChars: Int?,
        private val attempt: Int,
    ) {
        val secretsList = listOf(creds.token, creds.accountId)
        private val started = System.currentTimeMillis()
        val safeModel: String = Redactor.clean(model, secretsList, 80)
        val endpoint: String = Redactor.clean("$host$path".replace(creds.accountId, "{account}"), secretsList, 200)

        fun ok(status: Int, note: String? = null) {
            sink.record(entry(true, status, null, note?.let { Redactor.clean(it, secretsList, 300) }?.ifEmpty { null }))
        }

        fun failed(status: Int?, apiStatus: String?, message: String?, error: LlmError): LlmException {
            val safe = message?.let { Redactor.clean(it, secretsList, 300) }?.ifEmpty { null }
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

    /** See [OpenRouterProvider]: failures are thrown on the collector's side so earlier text is never lost. */
    private sealed interface Ev {
        class Text(val text: String) : Ev
        class Fail(val error: LlmException) : Ev
    }

    override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow<Ev> {
        try {
            val creds = credentialsOrThrow(request.apiKey, request.model)
            var emitted = false
            var attempt = 0
            while (true) {
                attempt++
                try {
                    streamOnce(request, creds, attempt) { chunk ->
                        emitted = true
                        emit(Ev.Text(chunk))
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
        } catch (e: LlmException) {
            emit(Ev.Fail(e))
        }
    }.flowOn(Dispatchers.IO).transform { ev ->
        when (ev) {
            is Ev.Text -> emit(ev.text)
            is Ev.Fail -> throw ev.error
        }
    }

    private suspend fun streamOnce(request: ChatRequest, creds: CloudflareParsing.Credentials, attempt: Int, onText: suspend (String) -> Unit) {
        val path = chatPath(creds)
        val call = Call(CallKind.GENERATE_STREAM, "POST", request.model, path, creds, request.sizeInChars(), attempt)
        val conn = open(path, "POST", creds.token, 90_000)
        try {
            withDisconnectOnCancel(conn) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.outputStream.use { it.write(CloudflareParsing.buildBody(request, stream = true).toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status != 200) throw httpFailure(call, conn, status, request.model)

                var gotText = false
                val plain = StringBuilder() // a JSON body instead of an event stream (streaming not honoured)
                val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) {
                        if (!line.startsWith(":") && line.isNotBlank() && plain.length < 200_000) plain.append(line).append('\n')
                        continue
                    }
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    when (val item = CloudflareParsing.streamItem(payload)) {
                        is CloudflareParsing.Item.Text -> {
                            gotText = true
                            onText(item.text)
                        }
                        is CloudflareParsing.Item.Error -> throw itemFailure(call, status, item, request.model)
                        CloudflareParsing.Item.Nothing -> {}
                    }
                }
                if (!gotText && plain.isNotBlank()) {
                    when (val item = CloudflareParsing.messageResult(plain.toString())) {
                        is CloudflareParsing.Item.Text -> {
                            gotText = true
                            onText(item.text)
                        }
                        is CloudflareParsing.Item.Error -> throw itemFailure(call, status, item, request.model)
                        CloudflareParsing.Item.Nothing -> {}
                    }
                }
                if (!gotText) throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
                call.ok(status, "free allowance")
            }
        } catch (e: IOException) {
            // A cancelled request closes its own socket; that is not a network failure.
            currentCoroutineContext().ensureActive()
            throw call.failed(null, null, e.javaClass.simpleName, ioFailure(e))
        } finally {
            conn.disconnect()
        }
    }

    private fun itemFailure(call: Call, status: Int, item: CloudflareParsing.Item.Error, model: String): LlmException =
        call.failed(status, item.code, item.message ?: "error inside the reply", CloudflareParsing.classify(null, item.code, item.message, model, clock(), call.secretsList))

    // ------------------------------------------------------------------ diagnostics calls

    /**
     * Asks Cloudflare's model search (no generation, no neurons used) whether this account and token work. The reply
     * format of that endpoint isn't documented, so a model missing from the first page isn't an error here; the
     * generation test is what proves a model works (and that it is free).
     */
    override suspend fun checkModel(apiKey: String, model: String): ModelInfo = withContext(Dispatchers.IO) {
        val creds = credentialsOrThrow(apiKey, model)
        val path = searchPath(creds)
        val call = Call(CallKind.AVAILABILITY, "GET", model, path, creds, null, 1)
        val conn = open(path, "GET", creds.token, 20_000)
        try {
            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, model)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val names = CloudflareParsing.modelNames(body)
            val seen = model in names
            call.ok(status, if (seen) "model listed" else "token accepted; model not on the first page of the catalog")
            ModelInfo(model, null, listOf("chat.completions"))
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, ioFailure(e))
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun generateOnce(request: ChatRequest): String = withContext(Dispatchers.IO) {
        val creds = credentialsOrThrow(request.apiKey, request.model)
        val path = chatPath(creds)
        val call = Call(CallKind.GENERATE_ONCE, "POST", request.model, path, creds, request.sizeInChars(), 1)
        val conn = open(path, "POST", creds.token, 90_000)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(CloudflareParsing.buildBody(request, stream = false).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) throw httpFailure(call, conn, status, request.model)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            when (val r = CloudflareParsing.messageResult(body)) {
                is CloudflareParsing.Item.Text -> {
                    call.ok(status, "free allowance")
                    r.text
                }
                is CloudflareParsing.Item.Error -> throw itemFailure(call, status, r, request.model)
                CloudflareParsing.Item.Nothing ->
                    throw call.failed(status, null, "no text returned", LlmError.Blocked("the model returned no text"))
            }
        } catch (e: IOException) {
            throw call.failed(null, null, e.javaClass.simpleName, ioFailure(e))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun open(path: String, method: String, token: String, readTimeoutMs: Int): HttpURLConnection {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = readTimeoutMs
        // The token goes in a header, never in the URL.
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", "jarvis-android")
        return conn
    }

    private fun httpFailure(call: Call, conn: HttpURLConnection, status: Int, model: String): LlmException {
        val body = readErrorBody(conn)
        val parsed = CloudflareParsing.parseFailure(status, body, model, clock(), call.secretsList)
        var error = parsed.error
        if (error is LlmError.Unclear429) {
            error = LlmError.Unclear429(conn.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.let { Math.ceil(it).toLong() })
        }
        val message = parsed.apiMessage ?: (error as? LlmError.NotFromApi)?.snippet
        return call.failed(status, parsed.apiStatus, message, error)
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
