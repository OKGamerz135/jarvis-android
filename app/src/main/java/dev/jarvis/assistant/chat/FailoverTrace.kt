package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.providers.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How far one generation attempt got. */
enum class DispatchStage { DISPATCHED, STREAMING, FINISHED_OK, FINISHED_FAILED }

/**
 * Which adapter really handled the latest attempt. It only exists once the adapter was actually invoked, so seeing it
 * means "confirmed", never "planned". [requested] is the provider this message started with.
 */
data class DispatchInfo(
    val requested: ProviderId,
    /** The name shown for [requested] (from its descriptor). */
    val requestedLabel: String,
    val actual: ProviderId,
    val adapter: String,
    val model: String,
    val stage: DispatchStage,
    val simulated: Boolean,
) {
    /** For example "Google Gemini -> GroqProvider -> openai/gpt-oss-120b". */
    val line: String get() = "$requestedLabel → $adapter → $model"

    val stageText: String
        get() = when (stage) {
            DispatchStage.DISPATCHED -> "request sent to the adapter, waiting"
            DispatchStage.STREAMING -> "reply is streaming"
            DispatchStage.FINISHED_OK -> "reply received"
            DispatchStage.FINISHED_FAILED -> "request failed"
        }

    /** True when the adapter that ran doesn't belong to the provider we meant to use (and isn't the labelled simulation). */
    val mismatch: Boolean get() = !simulated && !adapter.contains(actual.value, ignoreCase = true)
}

/** One line of the failover trace. Never carries keys, headers or message text. */
data class TraceEvent(
    val originalProvider: ProviderId?,
    val originalLabel: String?,
    val originalModel: String?,
    val classification: String?,
    val fallbackProvider: ProviderId?,
    val fallbackLabel: String?,
    val fallbackModel: String?,
    val adapter: String?,
    val stage: String,
    val streamStarted: Boolean,
    val responseReceived: Boolean,
    val dispatchedAtMillis: Long,
    val result: String,
)

interface TraceSink {
    fun record(event: TraceEvent)
}

object NoTrace : TraceSink {
    override fun record(event: TraceEvent) {}
}

/**
 * Wraps the adapter chosen for an attempt. The callbacks fire when the adapter is really invoked, when its first
 * text arrives, and when it ends. It forwards everything unchanged.
 */
class TracingLlm(
    private val inner: LlmProvider,
    private val onDispatched: () -> Unit,
    private val onFirstText: () -> Unit,
) : LlmProvider {
    val adapterName: String get() = inner.javaClass.simpleName

    override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow {
        onDispatched()
        var first = true
        inner.stream(request, options).collect {
            if (first) {
                first = false
                onFirstText()
            }
            emit(it)
        }
    }
}

/** "https://api.groq.com/openai/v1/chat/completions" or "api.groq.com/openai/..." -> "api.groq.com". Never includes a query string. */
fun hostOf(endpoint: String): String =
    endpoint.substringAfter("://").substringBefore('/').substringBefore('?')

/**
 * Writes trace lines into the diagnostics log. The host and HTTP status come from the adapter's own log entry for
 * the same attempt, so they describe what was really sent, not what we intended. No keys, headers or chat text.
 */
class DiagnosticsTraceSink(
    private val sink: dev.jarvis.assistant.diag.DiagnosticsSink,
    private val entries: () -> List<dev.jarvis.assistant.diag.DiagnosticEntry>,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : TraceSink {
    override fun record(event: TraceEvent) {
        val real = if (event.adapter != null && event.fallbackProvider != null) {
            entries().lastOrNull {
                it.kind != dev.jarvis.assistant.diag.CallKind.FAILOVER &&
                    it.provider == event.fallbackLabel && it.timeMillis >= event.dispatchedAtMillis
            }
        } else {
            null
        }
        val from = if (event.originalProvider != null) "${event.originalLabel ?: "unknown provider"} (${event.originalModel})" else "none"
        val message = "from=$from; adapter=${event.adapter ?: "n/a"}; streamStarted=${event.streamStarted}; " +
            "responseReceived=${event.responseReceived}; result=${event.result}"
        sink.record(
            dev.jarvis.assistant.diag.DiagnosticEntry(
                timeMillis = clock(),
                kind = dev.jarvis.assistant.diag.CallKind.FAILOVER,
                provider = event.fallbackLabel ?: "",
                model = event.fallbackModel ?: "",
                method = event.stage,
                endpoint = real?.let { hostOf(it.endpoint) } ?: "",
                httpStatus = real?.httpStatus,
                apiStatus = event.classification,
                message = message,
                durationMs = 0,
                attempt = 1,
                ok = event.result == "pending" || event.result == "success",
                requestChars = null,
            ),
        )
    }
}
