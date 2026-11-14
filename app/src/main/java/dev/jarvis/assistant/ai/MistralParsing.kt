package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Request building and error reading for Mistral's chat API (OpenAI-style). No networking here.
 *
 * Documented (docs.mistral.ai): POST https://api.mistral.ai/v1/chat/completions, Bearer auth, `stream: true` gives
 * server-sent events ending with [DONE], GET /v1/models lists models. Mistral's own help pages say the Experiment
 * plan is free, needs no credit card (only a verified phone number), and that requests on it may be used to train
 * Mistral's models. Nothing can be billed unless the user upgrades to the Scale plan themselves.
 *
 * Unverified (no Mistral key was available to test against): the exact error body. Both {"message":...} and
 * {"error":{"message":...}} are read; anything unrecognised falls back to a generic classification by status.
 */
object MistralParsing {
    fun buildBody(request: ChatRequest, stream: Boolean): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        for (m in request.messages) {
            messages.put(
                JSONObject()
                    .put("role", if (m.role == ChatRole.USER) "user" else "assistant")
                    .put("content", m.text),
            )
        }
        return JSONObject().put("model", request.model.trim()).put("messages", messages).put("stream", stream).toString()
    }

    /** Text of one streamed chunk. Mistral's chunks are OpenAI-shaped, and only a plain string `delta.content` is shown. */
    fun deltaText(payload: String): String {
        val root = try { JSONObject(payload) } catch (e: JSONException) { return "" }
        val delta = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return ""
        return contentText(delta.opt("content"))
    }

    fun messageText(body: String): String {
        val root = try { JSONObject(body) } catch (e: JSONException) { return "" }
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return contentText(message.opt("content"))
    }

    /** Content is a string for ordinary models; reasoning models may send a list of typed parts, of which only "text" is shown. */
    private fun contentText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        else -> ""
    }

    fun parseFailure(
        status: Int,
        body: String,
        retryAfterHeader: String?,
        model: String,
        now: Instant,
        secrets: List<String> = emptyList(),
    ): ParsedError {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val error = root?.optJSONObject("error")
        val raw = (error?.optString("message").takeUnless { it.isNullOrBlank() } ?: root?.optString("message")).orEmpty()
        val message = Redactor.clean(raw, secrets, 300).ifEmpty { null }
        val apiStatus = (error?.optString("type").takeUnless { it.isNullOrBlank() } ?: root?.optString("type"))?.takeIf { it.isNotBlank() }
        val hasError = root != null && (error != null || raw.isNotEmpty())
        val notApi = LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
        val retry = GroqParsing.retryAfterSeconds(retryAfterHeader, "")
        val lower = raw.lowercase()

        val classified: LlmError = when {
            status == 401 -> LlmError.InvalidKey
            status == 403 -> if (hasError) LlmError.PermissionDenied(message) else notApi
            status == 404 -> LlmError.ModelNotFound(model)
            status == 400 || status == 422 -> LlmError.InvalidRequest(message)
            status == 429 -> when {
                // Mistral also uses 429 for "the service tier's capacity is exceeded", which is an overload, not your quota.
                "capacity" in lower -> LlmError.Overloaded(message)
                // Only a message that clearly names a monthly limit counts as used-up quota; the reset time is not guessed.
                "month" in lower -> LlmError.QuotaExhausted(null, "monthly limit", ResetSource.UNKNOWN)
                "rate limit" in lower || "too many requests" in lower -> LlmError.RateLimited(retry)
                else -> LlmError.Unclear429(retry)
            }
            status == 503 -> if (hasError) LlmError.Overloaded(message) else notApi
            status == 500 -> if (hasError) LlmError.InternalError(message) else notApi
            status in 502..599 -> if (hasError) LlmError.ServerTrouble(status) else notApi
            else -> LlmError.Other("The provider answered with HTTP $status. ${message ?: ""}".trim())
        }
        return ParsedError(classified, apiStatus, message)
    }
}
