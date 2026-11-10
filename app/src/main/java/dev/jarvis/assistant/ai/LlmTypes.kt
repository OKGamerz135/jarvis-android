package dev.jarvis.assistant.ai

import kotlinx.coroutines.flow.Flow
import java.time.Instant

// Provider-independent types. Every AI provider (Gemini now, Groq later, maybe a local model)
// speaks these, so the rest of the app never needs to know which one is answering.

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(val role: ChatRole, val text: String)

data class ChatRequest(
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
    /** The conversation so far, oldest first. Providers are stateless; the app resends this each time. */
    val messages: List<ChatMessage>,
)

interface LlmProvider {
    /** Emits the reply in text chunks. Failures are thrown as [LlmException]. */
    fun stream(request: ChatRequest): Flow<String>
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

    /** The provider itself is struggling (HTTP 5xx). Not a quota problem. */
    data class ServerTrouble(val httpCode: Int) : LlmError

    data class Blocked(val reason: String) : LlmError
    data class Unsupported(val what: String) : LlmError
    data class Other(val message: String) : LlmError
}

class LlmException(val error: LlmError) : Exception(error.toString())
