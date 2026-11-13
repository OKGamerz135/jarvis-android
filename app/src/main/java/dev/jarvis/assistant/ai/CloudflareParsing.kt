package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * Request building and response/error reading for Cloudflare Workers AI's OpenAI-compatible chat endpoint
 * (`/accounts/{id}/ai/v1/chat/completions`). No networking here.
 *
 * Sources (opened 2026-11-13): developers.cloudflare.com Workers AI pricing, errors, OpenAI-compatibility, REST-API
 * and streaming pages. The docs describe streaming for the native endpoint (`data: {"response":"..."}`) and don't spell
 * it out for the compatible one, so streamed chunks are read in both shapes, and a plain JSON body is accepted too.
 */
object CloudflareParsing {
    /** Account IDs are 32 hex characters. The stored key is "ACCOUNT_ID:API_TOKEN". */
    private val ACCOUNT_ID = Regex("^[0-9a-fA-F]{32}$")

    data class Credentials(val accountId: String, val token: String)

    /** Null when the saved key isn't in the expected shape. Never echoes the key. */
    fun credentials(key: String): Credentials? {
        val trimmed = key.trim()
        val idx = trimmed.indexOf(':')
        if (idx <= 0) return null
        val id = trimmed.substring(0, idx).trim()
        val token = trimmed.substring(idx + 1).trim()
        if (!ACCOUNT_ID.matches(id) || token.isEmpty() || token.any { it.isWhitespace() }) return null
        return Credentials(id, token)
    }

    const val BAD_KEY_MESSAGE = "The Cloudflare key must be saved as ACCOUNT_ID:API_TOKEN: the 32-character account ID, a colon, then the " +
        "API token, all in one value."

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

    sealed interface Item {
        data class Text(val text: String) : Item
        data class Error(val code: String?, val message: String?) : Item
        data object Nothing : Item
    }

    private fun errorOf(root: JSONObject): Item.Error? {
        root.optJSONObject("error")?.let {
            return Item.Error(it.opt("code")?.toString()?.takeIf { c -> c != "null" }, it.optString("message").ifBlank { null })
        }
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val e = errors.optJSONObject(0)
            return Item.Error(e?.opt("code")?.toString()?.takeIf { c -> c != "null" }, e?.optString("message")?.ifBlank { null })
        }
        if (root.has("success") && !root.optBoolean("success", true)) return Item.Error(null, null)
        return null
    }

    /** One streamed `data:` payload: OpenAI-style delta, native `response`, or an error. */
    fun streamItem(payload: String): Item {
        val root = try { JSONObject(payload) } catch (e: JSONException) { return Item.Nothing }
        errorOf(root)?.let { return it }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        if (choice != null) {
            val delta = choice.optJSONObject("delta") ?: return Item.Nothing
            val text = if (delta.isNull("content")) "" else delta.optString("content")
            return if (text.isEmpty()) Item.Nothing else Item.Text(text)
        }
        if (root.has("response") && !root.isNull("response")) {
            val text = root.optString("response")
            return if (text.isEmpty()) Item.Nothing else Item.Text(text)
        }
        return Item.Nothing
    }

    /** A whole (non-streamed) reply body: OpenAI-style message, `result.response`, or an error. */
    fun messageResult(body: String): Item {
        val root = try { JSONObject(body) } catch (e: JSONException) { return Item.Nothing }
        errorOf(root)?.let { return it }
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        if (message != null) {
            val text = if (message.isNull("content")) "" else message.optString("content")
            return if (text.isEmpty()) Item.Nothing else Item.Text(text)
        }
        val result = root.optJSONObject("result")
        val text = result?.optString("response").orEmpty().ifEmpty { root.optString("response") }
        return if (text.isEmpty()) Item.Nothing else Item.Text(text)
    }

    /** Daily free allocation used up (internal code 3036). Documented to reset at 00:00 UTC. */
    fun isFreeAllocationUsedUp(code: String?, message: String?): Boolean =
        code == "3036" || message?.contains("daily free allocation", ignoreCase = true) == true

    fun nextUtcMidnight(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    /** Classifies an error body (HTTP failure, in-stream error or error inside a 200 body). */
    fun classify(
        status: Int?,
        code: String?,
        rawMessage: String?,
        model: String,
        now: Instant,
        secrets: List<String> = emptyList(),
    ): LlmError {
        val message = rawMessage?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null }
        val lower = rawMessage?.lowercase().orEmpty()
        if (isFreeAllocationUsedUp(code, rawMessage)) {
            return LlmError.QuotaExhausted(nextUtcMidnight(now), "free-neurons-per-day", ResetSource.DOCUMENTED)
        }
        return when {
            code == "5035" -> LlmError.BillingRequired(message ?: "This model needs a Workers Paid plan.")
            code == "3040" -> LlmError.Overloaded(message) // "Out of capacity, try again": temporary, not your quota
            code == "5007" || code == "3042" || code == "5028" -> LlmError.ModelNotFound(model)
            code == "10000" || "authentication" in lower -> LlmError.InvalidKey
            code == "3023" || code == "5016" || code == "5018" || code == "3041" -> LlmError.PermissionDenied(message)
            code == "3007" || code == "3008" -> LlmError.Timeout(message)
            code == "3006" -> LlmError.Other("The request is too large for Cloudflare (code 3006). ${message ?: ""}".trim())
            status == 401 -> LlmError.InvalidKey
            status == 403 -> LlmError.PermissionDenied(message)
            status == 404 || status == 410 -> LlmError.ModelNotFound(model)
            status == 400 || status == 422 -> LlmError.InvalidRequest(message)
            status == 408 -> LlmError.Timeout(message)
            status == 413 -> LlmError.Other("The request is too large for Cloudflare. ${message ?: ""}".trim())
            status == 429 -> LlmError.Unclear429(null)
            status == 503 -> LlmError.Overloaded(message)
            status == 500 -> LlmError.InternalError(message)
            status == 502 || status == 504 -> LlmError.ServerTrouble(status)
            status != null -> LlmError.Other("Cloudflare answered with HTTP $status. ${message ?: ""}".trim())
            else -> LlmError.Other(message ?: "Cloudflare reported an error while answering.")
        }
    }

    fun parseFailure(status: Int, body: String, model: String, now: Instant, secrets: List<String> = emptyList()): ParsedError {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val err = root?.let { errorOf(it) }
        if (err == null && status >= 500 && status != 503) {
            // A 5xx that isn't Cloudflare's own error format: something else may be answering.
            return ParsedError(LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200)), null, null)
        }
        val error = classify(status, err?.code, err?.message, model, now, secrets)
        return ParsedError(error, err?.code, err?.message?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null })
    }

    /** Model names from GET /ai/models/search. The response shape isn't documented, so this is deliberately tolerant. */
    fun modelNames(body: String): List<String> {
        val result = try { JSONObject(body).optJSONArray("result") } catch (e: JSONException) { null } ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until result.length()) {
            val o = result.optJSONObject(i) ?: continue
            val name = o.optString("name").ifEmpty { o.optString("id") }
            if (name.isNotEmpty()) out += name
        }
        return out
    }
}
