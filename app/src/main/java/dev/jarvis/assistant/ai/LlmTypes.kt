package dev.jarvis.assistant.ai

import kotlinx.coroutines.flow.Flow
import java.time.Instant

// Provider-independent types. Every AI provider (Gemini now, Groq next, maybe a local model later)
// speaks these, so the rest of the app never needs to know which one is answering.

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(val role: ChatRole, val text: String)

data class ChatRequest(
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
    /** The conversation so far, oldest first. Providers are stateless; the app resends this each time. */
    val messages: List<ChatMessage>,
) {
    /** Only the size, for diagnostics. The content itself is never logged. */
    fun sizeInChars(): Int = systemPrompt.length + messages.sumOf { it.text.length }
}

/** Lets the UI show "retrying..." while a provider waits between attempts. */
interface CallEvents {
    fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) {}
}

object NoEvents : CallEvents

/** [maxRetries] counts extra attempts after the first, and only ever applies to "overloaded" errors. */
data class CallOptions(val maxRetries: Int = 0, val events: CallEvents = NoEvents)

interface LlmProvider {
    /** Emits the reply in text chunks. Failures are thrown as [LlmException]. */
    fun stream(request: ChatRequest, options: CallOptions = CallOptions()): Flow<String>
}

data class ModelInfo(val id: String, val displayName: String?, val methods: List<String>) {
    /** Gemini lists "generateContent"; Groq's model list has no methods, so the adapter adds "chat.completions". */
    val canGenerate: Boolean get() = "generateContent" in methods || "chat.completions" in methods
}

/** Extra calls used only by the diagnostics tools. */
interface ProviderDiagnostics {
    /** Asks whether this key can see this model. Generates nothing, so it uses no generation quota. */
    suspend fun checkModel(apiKey: String, model: String): ModelInfo

    /** One non-streamed generation, used to tell a streaming problem from a provider problem. */
    suspend fun generateOnce(request: ChatRequest): String
}

sealed interface LlmError {
    data object NoKey : LlmError
    data object NotAcknowledged : LlmError
    data object Offline : LlmError
    data object InvalidKey : LlmError
    data class ModelNotFound(val model: String) : LlmError

    /** A short-lived limit (for example per-minute). Waiting a little and retrying works. */
    data class RateLimited(val retryAfterSeconds: Long?) : LlmError

    /** A genuine quota (for example the daily one) is used up. Retrying soon will not help. */
    data class QuotaExhausted(val resetAt: Instant?, val quotaId: String?) : LlmError

    /** The provider said "too many requests" without saying which limit. Treated as ordinary, not exhausted. */
    data class Unclear429(val retryAfterSeconds: Long?) : LlmError

    /** The provider says its servers are overloaded (Google: 503 UNAVAILABLE). Temporary, not your quota. */
    data class Overloaded(val apiMessage: String?) : LlmError

    /** Google 500 INTERNAL. Can also be caused by a request that is too long. */
    data class InternalError(val apiMessage: String?) : LlmError

    /** Google 504 DEADLINE_EXCEEDED: the request did not finish in time. */
    data class Timeout(val apiMessage: String?) : LlmError

    /** Google FAILED_PRECONDITION: the free tier is not available here and the provider wants billing. */
    data class BillingRequired(val apiMessage: String?) : LlmError

    data class PermissionDenied(val apiMessage: String?) : LlmError

    /** The provider rejected the request as malformed. That points at a JARVIS bug, not at the user. */
    data class InvalidRequest(val apiMessage: String?) : LlmError

    /** A 5xx status whose body is not the provider's own error format: something else may be answering. */
    data class NotFromApi(val httpCode: Int, val snippet: String) : LlmError

    /** A 5xx status in the provider's error format but without a status name we recognise. */
    data class ServerTrouble(val httpCode: Int) : LlmError

    data class Blocked(val reason: String) : LlmError
    data class Unsupported(val what: String) : LlmError
    data class Other(val message: String) : LlmError
}

/** Everything about a failed call that is safe to show: nothing secret, nothing from the conversation. */
data class FailureContext(
    val provider: String,
    val method: String,
    val model: String,
    val endpoint: String,
    val httpStatus: Int?,
    val apiStatus: String?,
    val apiMessage: String?,
)

class LlmException(val error: LlmError, val context: FailureContext? = null) : Exception(error.toString())
