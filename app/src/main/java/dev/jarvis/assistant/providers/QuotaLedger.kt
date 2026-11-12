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

object LedgerCodec {
    fun encode(records: Collection<QuotaRecord>): String {
        val array = JSONArray()
        for (r in records) {
            array.put(
                JSONObject()
                    .put("provider", r.provider.name)
                    .put("until", r.exhaustedUntil ?: JSONObject.NULL)
                    .put("source", r.source.name)
                    .put("at", r.recordedAt)
                    .put("quota", r.quotaId ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    fun decode(text: String): Map<AiProvider, QuotaRecord> {
        if (text.isBlank()) return emptyMap()
        return try {
            val array = JSONArray(text)
            val out = LinkedHashMap<AiProvider, QuotaRecord>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val p = AiProvider.entries.firstOrNull { it.name == o.optString("provider") } ?: continue
                val src = ResetSource.entries.firstOrNull { it.name == o.optString("source") } ?: ResetSource.UNKNOWN
                out[p] = QuotaRecord(
                    p,
                    if (o.isNull("until")) null else o.getLong("until"),
                    src,
                    o.optLong("at"),
                    if (o.isNull("quota")) null else o.optString("quota"),
                )
            }
            out
        } catch (e: JSONException) {
            emptyMap()
        }
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
    private val _records = MutableStateFlow(LedgerCodec.decode(store.load()))
    val records: StateFlow<Map<AiProvider, QuotaRecord>> = _records.asStateFlow()

    fun markExhausted(provider: AiProvider, error: LlmError.QuotaExhausted) {
        put(QuotaRecord(provider, error.resetAt?.toEpochMilli(), error.resetSource, clock(), error.quotaId))
    }

    private fun put(r: QuotaRecord) = synchronized(lock) {
        val updated = _records.value + (r.provider to r)
        _records.value = updated
        store.save(LedgerCodec.encode(updated.values))
    }

    fun clear(provider: AiProvider) = synchronized(lock) {
        if (provider !in _records.value) return
        val updated = _records.value - provider
        _records.value = updated
        store.save(LedgerCodec.encode(updated.values))
    }

    fun record(provider: AiProvider): QuotaRecord? = _records.value[provider]

    /** True while the provider is known to be used up: reset still in the future, or reset time unknown. */
    fun isExhausted(provider: AiProvider, now: Long = clock()): Boolean {
        val r = _records.value[provider] ?: return false
        return r.exhaustedUntil == null || r.exhaustedUntil > now
    }

    fun exhaustedUntilInstant(provider: AiProvider): Instant? = record(provider)?.exhaustedUntil?.let { Instant.ofEpochMilli(it) }
}
