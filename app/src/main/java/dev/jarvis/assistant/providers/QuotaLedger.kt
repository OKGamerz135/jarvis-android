package dev.jarvis.assistant.providers

import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * What we know about one provider's used-up quota. Only filled in after a provider really said "quota exhausted".
 * [exhaustedUntil] is null when nobody told us the reset time; we never guess one.
 */
data class QuotaRecord(
    val provider: AiProvider,
    val exhaustedUntil: Long?,
    val source: ResetSource,
    val recordedAt: Long,
    val quotaId: String?,
)

/** What a stored ledger holds: the records for built-in providers, and well-formed rows for other ids, kept as they are. */
class LedgerContents(val records: Map<AiProvider, QuotaRecord>, val foreign: List<JSONObject>)

object LedgerCodec {
    /** [foreign] rows (see [LedgerContents]) are written back unchanged after the built-in providers' rows. */
    fun encode(records: Collection<QuotaRecord>, foreign: Collection<JSONObject> = emptyList()): String {
        val array = JSONArray()
        for (r in records) {
            array.put(
                JSONObject()
                    .put("provider", r.provider.toId().value)
                    .put("until", r.exhaustedUntil ?: JSONObject.NULL)
                    .put("source", r.source.name)
                    .put("at", r.recordedAt)
                    .put("quota", r.quotaId ?: JSONObject.NULL),
            )
        }
        for (row in foreign) array.put(row)
        return array.toString()
    }

    fun decode(text: String): Map<AiProvider, QuotaRecord> = decodeAll(text).records

    /**
     * Each row is read on its own: a damaged row is skipped and never costs the valid ones. A row whose provider is
     * not exactly a built-in id is never mapped to a built-in provider; if it is a well-formed custom id it is
     * kept in [LedgerContents.foreign] so that saving does not erase it, otherwise it is dropped. Text that is not a
     * JSON array at all reads as empty.
     */
    fun decodeAll(text: String): LedgerContents {
        if (text.isBlank()) return LedgerContents(emptyMap(), emptyList())
        val array = try { JSONArray(text) } catch (e: JSONException) { return LedgerContents(emptyMap(), emptyList()) }
        val out = LinkedHashMap<AiProvider, QuotaRecord>()
        val foreign = LinkedHashMap<ProviderId, JSONObject>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = ProviderId.parse(o.optString("provider")) ?: continue
            val p = id.toAiProviderOrNull()
            if (p == null) { foreign[id] = o; continue }
            try {
                val src = ResetSource.entries.firstOrNull { it.name == o.optString("source") } ?: ResetSource.UNKNOWN
                out[p] = QuotaRecord(
                    p,
                    if (o.isNull("until")) null else o.getLong("until"),
                    src,
                    o.optLong("at"),
                    if (o.isNull("quota")) null else o.optString("quota"),
                )
            } catch (e: JSONException) {
                // this row is damaged; the others are fine
            }
        }
        return LedgerContents(out, foreign.values.toList())
    }
}

interface QuotaStore {
    fun load(): String
    fun save(text: String)
}

class FileQuotaStore(private val file: File) : QuotaStore {
    override fun load(): String = try { if (file.exists()) file.readText() else "" } catch (e: IOException) { "" }
    override fun save(text: String) {
        try { file.writeText(text) } catch (e: IOException) { /* best effort: losing this only loses a hint */ }
    }
}

/**
 * Remembers, per provider, that its quota was reported used up, and until when if known. Survives app restarts.
 * An entry with an unknown reset time stays "exhausted" until it is cleared by hand or the provider works again.
 */
class QuotaLedger(private val store: QuotaStore, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val lock = Any()
    private val loaded = LedgerCodec.decodeAll(store.load())
    private val foreign = loaded.foreign
    private val _records = MutableStateFlow(loaded.records)
    val records: StateFlow<Map<AiProvider, QuotaRecord>> = _records.asStateFlow()

    fun markExhausted(provider: AiProvider, error: LlmError.QuotaExhausted) {
        put(QuotaRecord(provider, error.resetAt?.toEpochMilli(), error.resetSource, clock(), error.quotaId))
    }

    private fun put(r: QuotaRecord) = synchronized(lock) {
        val updated = _records.value + (r.provider to r)
        _records.value = updated
        store.save(LedgerCodec.encode(updated.values, foreign))
    }

    fun clear(provider: AiProvider) = synchronized(lock) {
        if (provider !in _records.value) return
        val updated = _records.value - provider
        _records.value = updated
        store.save(LedgerCodec.encode(updated.values, foreign))
    }

    fun record(provider: AiProvider): QuotaRecord? = _records.value[provider]

    /** True while the provider is known to be used up: reset still in the future, or reset time unknown. */
    fun isExhausted(provider: AiProvider, now: Long = clock()): Boolean {
        val r = _records.value[provider] ?: return false
        return r.exhaustedUntil == null || r.exhaustedUntil > now
    }

    fun exhaustedUntilInstant(provider: AiProvider): Instant? = record(provider)?.exhaustedUntil?.let { Instant.ofEpochMilli(it) }
}
