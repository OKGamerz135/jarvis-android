package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Request building and response/error reading for OpenRouter's OpenAI-compatible API. No networking here.
 *
 * Sources (opened 2026-11-13): openrouter.ai/docs errors, streaming, limits, models and key-endpoint pages. The 429 body
 * does not say which limit was hit, so the adapter asks the key endpoint for the free-model daily counter instead of
 * guessing from text.
 */
object OpenRouterParsing {
    /** The model setting meaning "pick a currently free model for me". */
    const val AUTO = "auto-free"

    /** JARVIS only calls ids ending in ":free", so no request can ever be charged. */
    fun isFreeId(id: String): Boolean = id.endsWith(":free")

    fun buildBody(request: ChatRequest, model: String, stream: Boolean): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        for (m in request.messages) {
            messages.put(
                JSONObject()
                    .put("role", if (m.role == ChatRole.USER) "user" else "assistant")
                    .put("content", m.text),
            )
        }
        return JSONObject().put("model", model).put("messages", messages).put("stream", stream).toString()
    }

    // ------------------------------------------------------------------ streaming events

    sealed interface StreamItem {
        data class Text(val text: String) : StreamItem
        /** An error delivered inside a 200 stream. [code] is a number or a word, as OpenRouter sent it. */
        data class Error(val code: String?, val message: String?) : StreamItem
        data object Nothing : StreamItem
    }

    /** One `data:` payload. Comment lines (": OPENROUTER PROCESSING") never reach here. */
    fun streamItem(payload: String): StreamItem {
        val root = try { JSONObject(payload) } catch (e: JSONException) { return StreamItem.Nothing }
        root.optJSONObject("error")?.let {
            return StreamItem.Error(it.opt("code")?.toString()?.takeIf { c -> c != "null" }, it.optString("message").ifBlank { null })
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        if (choice != null && choice.optString("finish_reason") == "error") return StreamItem.Error(null, null)
        val delta = choice?.optJSONObject("delta") ?: return StreamItem.Nothing
        val text = if (delta.isNull("content")) "" else delta.optString("content")
        return if (text.isEmpty()) StreamItem.Nothing else StreamItem.Text(text)
    }

    /** A non-streamed reply: text, or an error object inside a 200 body. */
    fun messageResult(body: String): StreamItem {
        val root = try { JSONObject(body) } catch (e: JSONException) { return StreamItem.Nothing }
        root.optJSONObject("error")?.let {
            return StreamItem.Error(it.opt("code")?.toString()?.takeIf { c -> c != "null" }, it.optString("message").ifBlank { null })
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message") ?: return StreamItem.Nothing
        val text = if (message.isNull("content")) "" else message.optString("content")
        return if (text.isEmpty()) StreamItem.Nothing else StreamItem.Text(text)
    }

    fun isRateLimit(code: String?, message: String?): Boolean {
        val c = code?.lowercase().orEmpty()
        val m = message?.lowercase().orEmpty()
        return c == "429" || "rate_limit" in c || "rate limit" in m
    }

    /** An error that arrived inside a stream (or a 200 body) as a typed error. Rate limits are handled by the caller. */
    fun errorFromItem(item: StreamItem.Error, model: String, secrets: List<String> = emptyList()): LlmError {
        val message = item.message?.let { Redactor.clean(it, secrets, 300) }?.ifEmpty { null }
        val c = item.code?.lowercase().orEmpty()
        return when {
            c == "401" -> LlmError.InvalidKey
            c == "402" -> LlmError.BillingRequired(message)
            c == "403" -> LlmError.PermissionDenied(message)
            c == "404" -> LlmError.ModelNotFound(model)
            c == "408" || "timeout" in c -> LlmError.Timeout(message)
            c == "502" || c == "503" || "server_error" in c || "unavailable" in c || "overloaded" in c ->
                LlmError.Overloaded(message)
            else -> LlmError.Other(message ?: "OpenRouter reported an error while answering.")
        }
    }

    // ------------------------------------------------------------------ HTTP failures

    fun parseFailure(
        status: Int,
        body: String,
        retryAfterHeader: String?,
        resetHeaderMillis: String?,
        model: String,
        now: Instant,
        secrets: List<String> = emptyList(),
    ): ParsedError {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.opt("code")?.toString()?.takeIf { it != "null" }
        val raw = error?.optString("message").orEmpty()
        val message = Redactor.clean(raw, secrets, 300).ifEmpty { null }
        val hasError = error != null
        fun notApi() = LlmError.NotFromApi(status, Redactor.clean(body, secrets, 200))
        val classified: LlmError = when (status) {
            401 -> LlmError.InvalidKey
            402 -> LlmError.BillingRequired(message)
            403 -> if (hasError) LlmError.PermissionDenied(message) else notApi()
            404 -> LlmError.ModelNotFound(model)
            400, 422 -> LlmError.InvalidRequest(message)
            408 -> LlmError.Timeout(message)
            429 -> LlmError.Unclear429(retrySeconds(retryAfterHeader, resetHeaderMillis, now))
            500 -> if (hasError) LlmError.InternalError(message) else notApi()
            502 -> if (hasError) LlmError.ServerTrouble(status) else notApi()
            503 -> if (hasError) LlmError.Overloaded(message) else notApi()
            else -> LlmError.Other("OpenRouter answered with HTTP $status. ${message ?: ""}".trim())
        }
        return ParsedError(classified, code, message)
    }

    /** `Retry-After` seconds first; else the reset time from `X-RateLimit-Reset` (epoch ms) if it is within two minutes. */
    fun retrySeconds(retryAfter: String?, resetMillis: String?, now: Instant): Long? {
        retryAfter?.trim()?.toDoubleOrNull()?.let { return Math.ceil(it).toLong().coerceAtLeast(1) }
        val reset = resetMillis?.trim()?.toLongOrNull() ?: return null
        val wait = Math.ceil((reset - now.toEpochMilli()) / 1000.0).toLong()
        return if (wait in 1..120) wait else null
    }

    // ------------------------------------------------------------------ free quota (GET /api/v1/key)

    data class FreeQuota(val used: Int, val limit: Int, val remaining: Int)

    fun parseKey(body: String): FreeQuota? {
        val o = try { JSONObject(body).optJSONObject("data")?.optJSONObject("free_model_daily_requests") } catch (e: JSONException) { null }
            ?: return null
        if (!o.has("used") || !o.has("limit") || !o.has("remaining")) return null
        return FreeQuota(o.optInt("used"), o.optInt("limit"), o.optInt("remaining"))
    }

    /** OpenRouter says the free-model counter resets at UTC midnight. That is documentation, not a reported value. */
    fun nextUtcMidnight(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    fun describe(q: FreeQuota): String =
        "Free-model requests today (UTC day): ${q.used} used of ${q.limit}, ${q.remaining} left. Reported by OpenRouter; resets at 00:00 UTC."

    // ------------------------------------------------------------------ model list

    data class FreeModel(val id: String, val contextLength: Int)

    sealed interface Lookup {
        data class Free(val model: FreeModel) : Lookup
        data object NotListed : Lookup
        data object NotFree : Lookup
        data object Expired : Lookup
    }

    private fun zeroPrice(o: JSONObject?, key: String): Boolean {
        if (o == null || !o.has(key)) return false
        return o.optString(key).trim().toDoubleOrNull() == 0.0
    }

    private fun entryLookup(o: JSONObject, today: LocalDate): Lookup {
        val id = o.optString("id")
        val pricing = o.optJSONObject("pricing")
        // Free means the id says so AND both token prices are exactly zero. A per-request price must be zero if present.
        val free = isFreeId(id) && zeroPrice(pricing, "prompt") && zeroPrice(pricing, "completion") &&
            (pricing?.has("request") != true || zeroPrice(pricing, "request"))
        if (!free) return Lookup.NotFree
        val exp = o.optString("expiration_date").takeIf { it.isNotBlank() && it != "null" }
        if (exp != null) {
            val date = runCatching { LocalDate.parse(exp) }.getOrNull()
            if (date != null && !date.isAfter(today)) return Lookup.Expired
        }
        return Lookup.Free(FreeModel(id, o.optInt("context_length", 0)))
    }

    private fun textOutput(o: JSONObject): Boolean {
        val out = o.optJSONObject("architecture")?.optJSONArray("output_modalities") ?: return true
        for (i in 0 until out.length()) if (out.optString(i) == "text") return true
        return false
    }

    fun freeModels(body: String, now: Instant): List<FreeModel> {
        val data = try { JSONObject(body).optJSONArray("data") } catch (e: JSONException) { null } ?: return emptyList()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val out = ArrayList<FreeModel>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (!textOutput(o)) continue
            (entryLookup(o, today) as? Lookup.Free)?.let { out += it.model }
        }
        return out
    }

    /** Biggest context first, then id, so the pick is stable. Quality can't be judged from the list. */
    fun pickAuto(models: List<FreeModel>): FreeModel? =
        models.sortedWith(compareByDescending<FreeModel> { it.contextLength }.thenBy { it.id }).firstOrNull()

    fun lookup(body: String, model: String, now: Instant): Lookup {
        val data = try { JSONObject(body).optJSONArray("data") } catch (e: JSONException) { null } ?: return Lookup.NotListed
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (o.optString("id") == model) return entryLookup(o, today)
        }
        return Lookup.NotListed
    }
}
