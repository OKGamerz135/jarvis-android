package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Pure Kotlin apart from org.json, like the other parsing objects. No networking here.

/**
 * Request building and response/error reading for a generic OpenAI-compatible chat API (POST .../chat/completions,
 * GET .../models). It makes no provider-specific assumptions: no reasoning options, no vendor quota wording.
 *
 * A 429 is never reported as an exhausted quota: a custom endpoint's wording cannot be trusted to say "daily", and
 * "quota exhausted" is what lets automatic failover switch providers. It is [LlmError.RateLimited] or
 * [LlmError.Unclear429], which only ever mean "wait".
 */
object OpenAiCompatibleParsing {
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
        return JSONObject().put("model", request.model).put("messages", messages).put("stream", stream).toString()
    }

    /** Text of one streamed chunk. Only `delta.content` is read. An error object mid-stream is a failure. */
    fun deltaText(payload: String): String {
        val root = try { JSONObject(payload) } catch (e: JSONException) { return "" }
        root.optJSONObject("error")?.let {
            throw LlmException(LlmError.Other(it.optString("message").ifBlank { "The provider reported an error mid-reply." }))
        }
        val delta = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return ""
        return if (delta.isNull("content")) "" else delta.optString("content")
    }

    /** Text of a whole non-streamed reply. */
    fun messageText(body: String): String {
        val root = try { JSONObject(body) } catch (e: JSONException) { return "" }
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return if (message.isNull("content")) "" else message.optString("content")
    }

    /** The ids in a model list, or null if [body] is not an OpenAI-style list (`{"data":[{"id":...}]}`). */
    fun modelIds(body: String): List<String>? {
        val data = try { JSONObject(body).optJSONArray("data") } catch (e: JSONException) { null } ?: return null
        val ids = ArrayList<String>()
        for (i in 0 until data.length()) data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { ids += it }
        return ids
    }

    fun parseFailure(status: Int, body: String, retryAfterHeader: String?, model: String, secrets: List<String> = emptyList()): ParsedError {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf { it.isNotEmpty() && it != "null" }
        val type = error?.optString("type")?.takeIf { it.isNotEmpty() && it != "null" }
        val raw = error?.optString("message").orEmpty()
        val message = Redactor.clean(raw, secrets, 300).ifEmpty { null }
        val notApi = LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
        val hasError = error != null
        val classified: LlmError = when (status) {
            401 -> LlmError.InvalidKey
            402 -> LlmError.BillingRequired(message)
            403 -> if (hasError) LlmError.PermissionDenied(message) else notApi
            404 -> LlmError.ModelNotFound(model)
            400, 422 -> LlmError.InvalidRequest(message)
            413 -> LlmError.Other("The request is too big for this endpoint's limits (HTTP 413). ${message ?: ""}".trim())
            429 -> {
                val retry = retryAfterSeconds(retryAfterHeader)
                val lower = raw.lowercase()
                if ("per minute" in lower || "(rpm)" in lower || "(tpm)" in lower) LlmError.RateLimited(retry) else LlmError.Unclear429(retry)
            }
            503 -> if (hasError) LlmError.Overloaded(message) else notApi
            500 -> if (hasError) LlmError.InternalError(message) else notApi
            502, 504 -> if (hasError) LlmError.ServerTrouble(status) else notApi
            else -> LlmError.Other("The endpoint answered with HTTP $status. ${message ?: ""}".trim())
        }
        return ParsedError(classified, code ?: type, message)
    }

    private val SECONDS = Regex("""^\s*(\d+(?:\.\d+)?)\s*$""")

    private fun retryAfterSeconds(header: String?): Long? =
        header?.let { h -> SECONDS.matchEntire(h)?.let { Math.ceil(it.groupValues[1].toDouble()).toLong() } }
}
