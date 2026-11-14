package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Request building and error reading for SambaNova Cloud's OpenAI-compatible chat API. No networking here.
 *
 * Documented (docs.sambanova.ai): base URL https://api.sambanova.ai/v1; error body {"error":{"message","type","param",
 * "code"},"request_id"}; 429 comes as code `insufficient_quota` (rate limit of your tier) or `queue_full`; every response
 * carries `x-ratelimit-*-requests` (per minute) and `x-ratelimit-*-requests-day` (per day) headers, where the "reset"
 * headers are "time until reset".
 *
 * NOT documented, so handled defensively: the unit and format of those reset headers (seconds assumed, anything
 * unparseable or absurd becomes "unknown"), and whether `insufficient_quota` is sent for the per-minute or the per-day
 * limit. The headers decide: remaining-day == 0 is a genuine quota; only then may failover be offered.
 */
object SambaNovaParsing {
    private const val MAX_SANE_RESET_SECONDS = 2L * 24 * 3600

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

    // The reply shape is the standard OpenAI one, same as Groq's. Only `delta.content` is read.
    fun deltaText(payload: String): String = GroqParsing.deltaText(payload)

    fun messageText(body: String): String = GroqParsing.messageText(body)

    fun findModel(body: String, model: String): Boolean {
        val data = try { JSONObject(body).optJSONArray("data") } catch (e: JSONException) { null } ?: return false
        for (i in 0 until data.length()) if (data.optJSONObject(i)?.optString("id") == model) return true
        return false
    }

    /** [headers] must have lower-case names. */
    fun parseFailure(
        status: Int,
        body: String,
        headers: Map<String, String?>,
        model: String,
        now: Instant,
        secrets: List<String> = emptyList(),
    ): ParsedError {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf { it.isNotEmpty() && it != "null" }
        val type = error?.optString("type")?.takeIf { it.isNotEmpty() && it != "null" }
        val raw = error?.optString("message").orEmpty()
        val message = Redactor.clean(raw, secrets, 300).ifEmpty { null }
        val hasError = error != null
        val c = code?.lowercase().orEmpty()
        val notApi = LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))

        val classified: LlmError = when {
            c == "model_not_found" || c == "model_deprecated" || status == 410 -> LlmError.ModelNotFound(model)
            status == 401 -> LlmError.InvalidKey
            status == 402 -> LlmError.BillingRequired(message)
            status == 403 -> if (hasError) LlmError.PermissionDenied(message) else notApi
            status == 404 -> LlmError.ModelNotFound(model)
            status == 400 || status == 422 -> LlmError.InvalidRequest(message)
            status == 408 -> LlmError.Timeout(message)
            status == 429 -> classify429(c, raw.lowercase(), headers, model, now)
            status == 503 -> if (hasError) LlmError.Overloaded(message) else notApi
            status == 500 -> if (hasError) LlmError.InternalError(message) else notApi
            status in 502..599 -> if (hasError) LlmError.ServerTrouble(status) else notApi
            else -> LlmError.Other("The provider answered with HTTP $status. ${message ?: ""}".trim())
        }
        return ParsedError(classified, code ?: type, message)
    }

    private fun classify429(code: String, lowerMessage: String, h: Map<String, String?>, model: String, now: Instant): LlmError {
        if (code == "queue_full") return LlmError.Overloaded("SambaNova's queue is full right now (queue_full).")
        val dayLeft = h["x-ratelimit-remaining-requests-day"]?.trim()?.toLongOrNull()
        val minuteLeft = h["x-ratelimit-remaining-requests"]?.trim()?.toLongOrNull()
        val dayReset = secondsUntil(h["x-ratelimit-reset-requests-day"])
        val minuteReset = secondsUntil(h["x-ratelimit-reset-requests"]) ?: secondsUntil(h["retry-after"])
        return when {
            dayLeft != null && dayLeft <= 0 -> LlmError.QuotaExhausted(
                dayReset?.let { now.plusSeconds(it) },
                "requests per day ($model)",
                if (dayReset != null) ResetSource.REPORTED else ResetSource.UNKNOWN,
            )
            minuteLeft != null && minuteLeft <= 0 -> LlmError.RateLimited(minuteReset)
            // No usable headers: only an explicit "per day" in the text counts as a quota; everything else is ordinary.
            dayLeft == null && minuteLeft == null && ("per day" in lowerMessage || "daily" in lowerMessage) ->
                LlmError.QuotaExhausted(null, "requests per day ($model)", ResetSource.UNKNOWN)
            else -> LlmError.Unclear429(minuteReset)
        }
    }

    /** "time until reset": plain seconds ("37", "37.5") or a duration like "7m12s". Anything else, or absurdly large, is unknown. */
    fun secondsUntil(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = v.toDoubleOrNull()?.let { Math.ceil(it).toLong() } ?: GroqParsing.parseDuration(v)
        return seconds?.takeIf { it in 0..MAX_SANE_RESET_SECONDS }
    }
}
