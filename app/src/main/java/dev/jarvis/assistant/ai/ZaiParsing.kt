package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Request building and error reading for Z.ai's chat API (OpenAI-style). No networking here.
 *
 * Documented (docs.z.ai): POST https://api.z.ai/api/paas/v4/chat/completions, Bearer auth, `stream: true` gives an
 * event stream ending with [DONE], `thinking: {"type":"disabled"}` turns chain-of-thought off, errors are
 * {"error":{"code","message"}} where `code` is a business code such as 1113.
 *
 * Only the two models its pricing page lists as free may be called. Anything else would bill per token, so it is
 * refused here before any request is made.
 */
object ZaiParsing {
    val FREE_MODELS = listOf("glm-4.7-flash", "glm-4.5-flash")

    fun isFreeModel(model: String): Boolean = model.trim().lowercase() in FREE_MODELS

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
        return JSONObject()
            .put("model", request.model.trim())
            .put("messages", messages)
            .put("stream", stream)
            .put("thinking", JSONObject().put("type", "disabled"))
            .toString()
    }

    // Same OpenAI-shaped chunks as Groq. Only `delta.content` is read, so any reasoning text is never shown.
    fun deltaText(payload: String): String = GroqParsing.deltaText(payload)

    fun messageText(body: String): String = GroqParsing.messageText(body)

    fun parseFailure(
        status: Int,
        body: String,
        retryAfterHeader: String?,
        model: String,
        now: Instant,
        secrets: List<String> = emptyList(),
    ): ParsedError {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.opt("code")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        val raw = error?.optString("message").orEmpty()
        val message = Redactor.clean(raw, secrets, 300).ifEmpty { null }
        val notApi = LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
        val retry = GroqParsing.retryAfterSeconds(retryAfterHeader, "")

        val classified: LlmError = when (code) {
            // Balance / package: the free API is not available for this account. Never a reason to fail over.
            "1113" -> LlmError.BillingRequired(
                message ?: "Insufficient balance or no resource package.",
            )
            "1302", "1313" -> LlmError.RateLimited(retry)
            "1305" -> LlmError.Overloaded(message)
            // Usage windows and spend limits: a genuine quota. The reset time is only free text, so it is not guessed.
            "1308", "1310", "1316", "1317", "1318", "1319", "1320", "1321" ->
                LlmError.QuotaExhausted(null, "usage limit (code $code)", ResetSource.UNKNOWN)
            "1000", "1001", "1003", "1005" -> LlmError.InvalidKey
            "1220", "1309", "1311", "1314", "1315" -> LlmError.PermissionDenied(message)
            "1211", "1221", "1222" -> LlmError.ModelNotFound(model)
            "1301" -> LlmError.Blocked("the provider flagged the content as potentially unsafe")
            "1210", "1212", "1213", "1214", "1215", "1261" -> LlmError.InvalidRequest(message)
            "1200", "1230", "1234" -> LlmError.InternalError(message)
            else -> byStatus(status, error != null, message, notApi, retry)
        }
        return ParsedError(classified, code, message)
    }

    private fun byStatus(status: Int, hasError: Boolean, message: String?, notApi: LlmError, retry: Long?): LlmError = when {
        status == 401 -> LlmError.InvalidKey
        status == 403 -> if (hasError) LlmError.PermissionDenied(message) else notApi
        status == 400 -> LlmError.InvalidRequest(message)
        // A 429 without a recognised business code is never treated as exhausted quota.
        status == 429 -> LlmError.Unclear429(retry)
        status == 503 -> if (hasError) LlmError.Overloaded(message) else notApi
        status == 500 -> if (hasError) LlmError.InternalError(message) else notApi
        status in 502..599 -> if (hasError) LlmError.ServerTrouble(status) else notApi
        else -> LlmError.Other("The provider answered with HTTP $status. ${message ?: ""}".trim())
    }
}
