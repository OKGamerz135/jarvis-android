package dev.jarvis.assistant.diag

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure Kotlin (no Android classes) so it can be unit-tested on the build server.

enum class CallKind(val label: String) {
    AVAILABILITY("Availability check"),
    GENERATE_STREAM("Generation, streamed"),
    GENERATE_ONCE("Generation, not streamed"),

    /** A failover trace line written by JARVIS itself. It is not an HTTP call and never counts as quota use. */
    FAILOVER("Failover trace");

    /** Availability checks and failover trace lines are free of generation quota. */
    val usesGenerationQuota: Boolean get() = this == GENERATE_STREAM || this == GENERATE_ONCE
}

/**
 * One HTTP call to an AI provider. This is everything the diagnostics log ever keeps about a call:
 * no API keys, no prompts, no replies, no headers, no raw response bodies. [message] is the provider's own
 * error text after redaction and truncation. [requestChars] is only the size of what was sent.
 */
data class DiagnosticEntry(
    val timeMillis: Long,
    val kind: CallKind,
    val provider: String,
    val model: String,
    val method: String,
    val endpoint: String,
    val httpStatus: Int?,
    val apiStatus: String?,
    val message: String?,
    val durationMs: Long,
    val attempt: Int,
    val ok: Boolean,
    val requestChars: Int?,
)

interface DiagnosticsSink {
    fun record(entry: DiagnosticEntry)
}

object NoopSink : DiagnosticsSink {
    override fun record(entry: DiagnosticEntry) {}
}

object DiagnosticsCodec {
    fun encode(entries: List<DiagnosticEntry>): String {
        val array = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("t", e.timeMillis)
            o.put("kind", e.kind.name)
            o.put("provider", e.provider)
            o.put("model", e.model)
            o.put("method", e.method)
            o.put("endpoint", e.endpoint)
            e.httpStatus?.let { o.put("http", it) }
            e.apiStatus?.let { o.put("apiStatus", it) }
            e.message?.let { o.put("message", it) }
            o.put("ms", e.durationMs)
            o.put("attempt", e.attempt)
            o.put("ok", e.ok)
            e.requestChars?.let { o.put("chars", it) }
            array.put(o)
        }
        return array.toString()
    }

    fun decode(text: String): List<DiagnosticEntry> {
        if (text.isBlank()) return emptyList()
        val array = try {
            JSONArray(text)
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = ArrayList<DiagnosticEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val kind = CallKind.entries.firstOrNull { it.name == o.optString("kind") } ?: continue
            out += DiagnosticEntry(
                timeMillis = o.optLong("t"),
                kind = kind,
                provider = o.optString("provider"),
                model = o.optString("model"),
                method = o.optString("method"),
                endpoint = o.optString("endpoint"),
                httpStatus = if (o.has("http")) o.optInt("http") else null,
                apiStatus = if (o.has("apiStatus")) o.optString("apiStatus") else null,
                message = if (o.has("message")) o.optString("message") else null,
                durationMs = o.optLong("ms"),
                attempt = o.optInt("attempt", 1),
                ok = o.optBoolean("ok"),
                requestChars = if (o.has("chars")) o.optInt("chars") else null,
            )
        }
        return out
    }
}

object DiagnosticsFormat {
    /** Plain text meant to be pasted into a chat. Contains only what [DiagnosticEntry] holds. */
    fun toText(entries: List<DiagnosticEntry>, appVersion: String, now: Instant, zone: ZoneId): String {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss d MMM", Locale.ENGLISH)
        return buildString {
            appendLine("JARVIS diagnostics, app $appVersion, ${entries.size} calls, newest first.")
            appendLine("Contains no API keys, prompts or replies.")
            appendLine("Generated ${time.format(now.atZone(zone))}.")
            appendLine()
            for (e in entries.asReversed()) {
                val result = if (e.ok) "OK" else "FAILED"
                append("[${time.format(Instant.ofEpochMilli(e.timeMillis).atZone(zone))}] ")
                append("$result | ${e.kind.label} | ${e.provider} | model ${e.model} | ${e.method} ${e.endpoint}")
                append(" | HTTP ${e.httpStatus ?: "none"}")
                if (e.apiStatus != null) append(" | status ${e.apiStatus}")
                append(" | ${e.durationMs} ms | attempt ${e.attempt}")
                if (e.requestChars != null) append(" | sent ${e.requestChars} chars")
                appendLine()
                if (!e.message.isNullOrBlank()) appendLine("    says: ${e.message}")
            }
        }.trimEnd()
    }
}

/** Keeps free-tier usage low: a pause between generation calls, and a count so you can see what was used. */
object UsageGuard {
    const val COOLDOWN_MS = 60_000L
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Every generation HTTP attempt counts, including failed ones, because providers may count them too. */
    fun generationCalls(entries: List<DiagnosticEntry>, provider: String, sinceMillis: Long): Int =
        entries.count { it.kind.usesGenerationQuota && it.provider == provider && it.timeMillis >= sinceMillis }

    fun cooldownRemainingMs(entries: List<DiagnosticEntry>, provider: String, nowMillis: Long): Long {
        val last = entries
            .filter { it.kind.usesGenerationQuota && it.provider == provider }
            .maxOfOrNull { it.timeMillis } ?: return 0L
        return (COOLDOWN_MS - (nowMillis - last)).coerceIn(0L, COOLDOWN_MS)
    }
}
