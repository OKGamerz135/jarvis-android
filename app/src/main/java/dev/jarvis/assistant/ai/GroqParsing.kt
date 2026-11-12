package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Request building and response/error reading for Groq's OpenAI-compatible chat API. No networking here.
 *
 * Unverified assumptions (no Groq key was available to test against): the error body is OpenAI-style
 * {"error":{"message","type","code"}}, and 429 messages name the limit as "requests per day (RPD)",
 * "tokens per minute (TPM)" and so on. Anything that doesn't match falls back to [LlmError.Unclear429].
 */
object GroqParsing {
    private val DURATION_PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")
    private val SIMPLE_SECONDS = Regex("""^\s*(\d+(?:\.\d+)?)\s*$""")

    /** GPT-OSS models always reason. We ask for the least and hide it; it cannot be switched off. */
    fun isGptOss(model: String): Boolean = model.startsWith("openai/gpt-oss")

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
        val body = JSONObject().put("model", request.model).put("messages", messages).put("stream", stream)
        if (isGptOss(request.model)) {
            body.put("reasoning_effort", "low")
            body.put("include_reasoning", false)
        }
        return body.toString()
    }

    /** Text of one streamed chunk. Only `delta.content` is read, so reasoning text is never shown. */
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

    /** Looks the model up in the /models list. Null means "not listed". */
    fun findModel(body: String, model: String): ModelEntry? {
        val data = try { JSONObject(body).optJSONArray("data") } catch (e: JSONException) { null } ?: return null
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (o.optString("id") == model) return ModelEntry(model, !o.has("active") || o.optBoolean("active", true))
        }
        return null
    }

    data class ModelEntry(val id: String, val active: Boolean)

    fun parseFailure(
        status: Int,
        body: String,
        retryAfterHeader: String?,
        model: String,
        now: Instant,
        secrets: List<String> = emptyList(),
    ): ParsedError {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf { it.isNotEmpty() && it != "null" }
        val type = error?.optString("type")?.takeIf { it.isNotEmpty() && it != "null" }
        val apiStatus = code ?: type
        val raw = error?.optString("message").orEmpty()
        val message = Redactor.clean(raw, secrets, 300).ifEmpty { null }
        val classified = classify(status, error != null, code, raw, message, body, retryAfterHeader, model, now, secrets)
        return ParsedError(classified, apiStatus, message)
    }

    private fun classify(
        status: Int,
        hasError: Boolean,
        code: String?,
        raw: String,
        message: String?,
        body: String,
        retryAfterHeader: String?,
        model: String,
        now: Instant,
        secrets: List<String>,
    ): LlmError {
        val lower = raw.lowercase()
        val c = code?.lowercase().orEmpty()
        if (c.contains("model_not_found") || c.contains("decommissioned") || lower.contains("decommissioned")) {
            return LlmError.ModelNotFound(model)
        }
        return when (status) {
            401 -> LlmError.InvalidKey
            403 ->
                if (hasError) LlmError.PermissionDenied(message)
                else LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
            404 -> LlmError.ModelNotFound(model)
            400, 422 -> LlmError.InvalidRequest(message)
            413 -> LlmError.Other("The request is too big for this model's limits (HTTP 413). ${message ?: ""}".trim())
            429 -> classify429(lower, retryAfterSeconds(retryAfterHeader, raw), now)
            503 ->
                if (hasError) LlmError.Overloaded(message)
                else LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
            500 ->
                if (hasError) LlmError.InternalError(message)
                else LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
            502, 504, 498 ->
                if (hasError) LlmError.ServerTrouble(status)
                else LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
            else -> LlmError.Other("The provider answered with HTTP $status. ${message ?: ""}".trim())
        }
    }

    /**
     * Daily limits mean "used up for a long while"; per-minute limits mean "wait a few seconds". Groq says which
     * in the message. Groq's daily limits roll rather than reset at a fixed hour, so the reset time is "now plus
     * the retry time Groq gave", never an assumed midnight.
     */
    private fun classify429(lower: String, retry: Long?, now: Instant): LlmError {
        val daily = "per day" in lower || "(rpd)" in lower || "(tpd)" in lower
        val perMinute = "per minute" in lower || "(rpm)" in lower || "(tpm)" in lower
        return when {
            daily -> LlmError.QuotaExhausted(
                retry?.let { now.plusSeconds(it) },
                quotaName(lower),
                if (retry != null) ResetSource.REPORTED else ResetSource.UNKNOWN,
            )
            perMinute -> LlmError.RateLimited(retry)
            else -> LlmError.Unclear429(retry)
        }
    }

    private fun quotaName(lower: String): String = when {
        "(tpd)" in lower || "tokens per day" in lower -> "tokens per day"
        else -> "requests per day"
    }

    /** The `retry-after` header (seconds) first, then "try again in 7m12.5s" inside the message. */
    fun retryAfterSeconds(header: String?, message: String): Long? {
        header?.let { h -> SIMPLE_SECONDS.matchEntire(h)?.let { return Math.ceil(it.groupValues[1].toDouble()).toLong() } }
        val idx = message.indexOf("try again in", ignoreCase = true)
        if (idx < 0) return null
        return parseDuration(message.substring(idx + "try again in".length))
    }

    /** "7m12.5s" -> 733, "2h3m" -> 7380, "340ms" -> 1. Stops at the first character that isn't part of a duration. */
    fun parseDuration(text: String): Long? {
        val trimmed = text.trim()
        var pos = 0
        var total = 0.0
        var any = false
        while (pos < trimmed.length) {
            val m = DURATION_PART.matchAt(trimmed, pos) ?: break
            val n = m.groupValues[1].toDouble()
            total += when (m.groupValues[2]) {
                "h" -> n * 3600
                "m" -> n * 60
                "s" -> n
                else -> n / 1000
            }
            any = true
            pos = m.range.last + 1
        }
        return if (any) Math.ceil(total).toLong() else null
    }
}
