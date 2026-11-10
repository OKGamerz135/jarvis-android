package dev.jarvis.assistant.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Request building and response/error reading for the Gemini REST API. No networking here, so it is unit-testable. */
object GeminiParsing {
    // Google's daily free-tier quotas reset at midnight Pacific time (stated in Google's rate-limit docs).
    private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
    private val RETRY_DELAY = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)s\s*$""")

    fun buildBody(request: ChatRequest): String {
        val contents = JSONArray()
        for (m in request.messages) {
            contents.put(
                JSONObject()
                    .put("role", if (m.role == ChatRole.USER) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text))),
            )
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
            .put("contents", contents)
            .toString()
    }

    /** Pulls the reply text out of one streamed chunk. Throws [LlmException] if the prompt was blocked. */
    fun textOf(payload: String): String {
        val root = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            return ""
        }
        root.optJSONObject("error")?.let {
            throw LlmException(LlmError.Other(it.optString("message").ifBlank { "The provider reported an error mid-reply." }))
        }
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (blockReason.isNotEmpty()) throw LlmException(LlmError.Blocked(blockReason))

        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue // the model's private reasoning, not the answer
            out.append(part.optString("text"))
        }
        return out.toString()
    }

    fun parseError(status: Int, body: String, model: String, now: Instant): LlmError {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val message = error?.optString("message").orEmpty()
        return when (status) {
            400 ->
                if (message.contains("API key", true)) LlmError.InvalidKey
                else LlmError.Other(message.ifBlank { "The provider rejected the request (HTTP 400)." })
            401 -> LlmError.InvalidKey
            403 ->
                if (message.contains("API key", true)) LlmError.InvalidKey
                else LlmError.Other("Access denied by the provider: ${message.ifBlank { "no details given" }}")
            404 -> LlmError.ModelNotFound(model)
            429 -> classify429(error, now)
            500, 502, 503, 504 -> LlmError.ServerTrouble(status)
            else -> LlmError.Other("The provider answered with HTTP $status. $message".trim())
        }
    }

    /**
     * Gemini's retryDelay can't be trusted on its own: a real daily-quota error has been seen with
     * retryDelay "1s". The quota id inside the error details is what says which limit was hit.
     */
    private fun classify429(error: JSONObject?, now: Instant): LlmError {
        val quotaIds = mutableListOf<String>()
        var retry: Long? = null
        val details = error?.optJSONArray("details")
        for (i in 0 until (details?.length() ?: 0)) {
            val d = details?.optJSONObject(i) ?: continue
            val type = d.optString("@type")
            if (type.endsWith("QuotaFailure")) {
                val violations = d.optJSONArray("violations")
                for (j in 0 until (violations?.length() ?: 0)) {
                    val id = violations?.optJSONObject(j)?.optString("quotaId").orEmpty()
                    if (id.isNotEmpty()) quotaIds += id
                }
            } else if (type.endsWith("RetryInfo")) {
                retry = parseRetrySeconds(d.optString("retryDelay"))
            }
        }
        val daily = quotaIds.firstOrNull { it.lowercase().let { id -> "perday" in id || "daily" in id } }
        return when {
            daily != null -> LlmError.QuotaExhausted(nextMidnightPacific(now), daily)
            quotaIds.any { "perminute" in it.lowercase() } -> LlmError.RateLimited(retry)
            else -> LlmError.Unclear429(retry)
        }
    }

    fun parseRetrySeconds(text: String): Long? {
        val match = RETRY_DELAY.matchEntire(text) ?: return null
        return Math.ceil(match.groupValues[1].toDouble()).toLong()
    }

    fun nextMidnightPacific(now: Instant): Instant =
        now.atZone(PACIFIC).toLocalDate().plusDays(1).atStartOfDay(PACIFIC).toInstant()
}
