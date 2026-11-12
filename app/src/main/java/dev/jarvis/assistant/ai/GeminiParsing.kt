package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class ParsedError(val error: LlmError, val apiStatus: String?, val apiMessage: String?)

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

    /** Pulls the reply text out of one streamed chunk or a whole non-streamed reply. Throws if the prompt was blocked. */
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

    /** Reads a models.get reply. An unreadable reply still counts as "the model exists" because the status was 200. */
    fun parseModelInfo(body: String, requested: String): ModelInfo {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return ModelInfo(requested, null, emptyList())
        }
        val methods = root.optJSONArray("supportedGenerationMethods")
        val list = ArrayList<String>()
        for (i in 0 until (methods?.length() ?: 0)) list += methods!!.optString(i)
        return ModelInfo(
            id = root.optString("name").removePrefix("models/").ifEmpty { requested },
            displayName = root.optString("displayName").ifEmpty { null },
            methods = list,
        )
    }

    fun parseError(status: Int, body: String, model: String, now: Instant): LlmError =
        parseFailure(status, body, model, now).error

    /**
     * Classifies a failed call by Google's own error status first (UNAVAILABLE, INTERNAL, ...) and only then by
     * the HTTP number, because the HTTP number alone can't tell overload from misconfiguration.
     */
    fun parseFailure(
        status: Int,
        body: String,
        model: String,
        now: Instant,
        secrets: List<String> = emptyList(),
    ): ParsedError {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val apiStatus = error?.optString("status")?.takeIf { it.isNotEmpty() }
        val rawMessage = error?.optString("message").orEmpty()
        val message = Redactor.clean(rawMessage, secrets, 300).ifEmpty { null }
        val classified = classify(status, error, apiStatus, rawMessage, message, body, model, now, secrets)
        return ParsedError(classified, apiStatus, message)
    }

    private fun classify(
        status: Int,
        error: JSONObject?,
        apiStatus: String?,
        rawMessage: String,
        message: String?,
        body: String,
        model: String,
        now: Instant,
        secrets: List<String>,
    ): LlmError {
        val mentionsKey = rawMessage.contains("API key", true)
        when (apiStatus) {
            "UNAVAILABLE" -> return LlmError.Overloaded(message)
            "INTERNAL" -> return LlmError.InternalError(message)
            "DEADLINE_EXCEEDED" -> return LlmError.Timeout(message)
            "FAILED_PRECONDITION" -> return LlmError.BillingRequired(message)
            "UNAUTHENTICATED" -> return LlmError.InvalidKey
            "PERMISSION_DENIED" -> return if (mentionsKey) LlmError.InvalidKey else LlmError.PermissionDenied(message)
            "NOT_FOUND" -> return LlmError.ModelNotFound(model)
            "INVALID_ARGUMENT" -> return if (mentionsKey) LlmError.InvalidKey else LlmError.InvalidRequest(message)
            "RESOURCE_EXHAUSTED" -> return classify429(error, now)
        }
        return when (status) {
            400 ->
                if (mentionsKey) LlmError.InvalidKey
                else LlmError.Other(message ?: "The provider rejected the request (HTTP 400).")
            401 -> LlmError.InvalidKey
            403 ->
                if (mentionsKey) LlmError.InvalidKey
                else LlmError.Other("Access denied by the provider: ${message ?: "no details given"}")
            404 -> LlmError.ModelNotFound(model)
            429 -> classify429(error, now)
            500, 502, 503, 504 ->
                if (error != null) LlmError.ServerTrouble(status)
                else LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
            else -> LlmError.Other("The provider answered with HTTP $status. ${message ?: ""}".trim())
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
            daily != null -> LlmError.QuotaExhausted(nextMidnightPacific(now), daily, ResetSource.DOCUMENTED)
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
