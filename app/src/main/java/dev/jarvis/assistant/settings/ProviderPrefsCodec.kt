package dev.jarvis.assistant.settings

import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.toAiProviderOrNull
import dev.jarvis.assistant.providers.toId

// Pure Kotlin on purpose (no android.* imports) so it can be tested with real stored values on the build server.

/** The four provider references exactly as they are stored on the phone. null means the key is not there. */
data class RawProviderPrefs(
    val provider: String?,
    val acknowledged: Set<String>?,
    /** Comma-joined ids, for example "GEMINI,GROQ". */
    val order: String?,
    val disabled: Set<String>?,
)

/** The same four references as the app uses them today: built-in providers only. */
data class ProviderPrefs(
    val provider: AiProvider,
    val acknowledged: Set<AiProvider>,
    val order: List<AiProvider>,
    val disabled: Set<AiProvider>,
)

/** What is written back. Nothing is null: a write always stores all four. */
data class StoredProviderPrefs(
    val provider: String,
    val acknowledged: Set<String>,
    val order: String,
    val disabled: Set<String>,
)

/**
 * Reads and writes the stored provider references.
 *
 * What is stored for a built-in provider has not changed: it is the provider's [ProviderId], which is the same text
 * as the [AiProvider] name that Build 46 and the Phase B builds already stored. So existing data reads exactly as
 * before and new data is written in the same form.
 *
 * Reading never guesses. Text that is not exactly a built-in id is not a built-in provider: it is skipped (a removed
 * or misspelled provider) or, if it is a well-formed custom id, simply not visible to code that only knows built-in
 * providers. It is never turned into some other provider, with one long-standing exception kept as it was: a
 * preferred provider that cannot be read falls back to the default.
 *
 * Writing never erases what it cannot show. [JarvisSettings] is still typed by [AiProvider], so a well-formed custom
 * id found in the stored data is carried over unchanged when anything else is saved (the lists get them back after the
 * built-in providers, in their original order). Before this stage any save erased them. Text that is not a valid id at all is
 * dropped, as before.
 */
object ProviderPrefsCodec {

    private fun builtIn(text: String): AiProvider? = ProviderId.parse(text)?.toAiProviderOrNull()

    private fun customIds(texts: Collection<String>): List<String> =
        texts.mapNotNull { t -> ProviderId.parse(t)?.takeIf { it.isCustom }?.value }.distinct()

    fun decode(raw: RawProviderPrefs, defaultProvider: AiProvider, defaultOrder: List<AiProvider>): ProviderPrefs = ProviderPrefs(
        provider = raw.provider?.let { builtIn(it) } ?: defaultProvider,
        acknowledged = raw.acknowledged.orEmpty().mapNotNull { builtIn(it) }.toSet(),
        order = raw.order?.split(",")?.mapNotNull { builtIn(it) }?.ifEmpty { null } ?: defaultOrder,
        disabled = raw.disabled.orEmpty().mapNotNull { builtIn(it) }.toSet(),
    )

    /**
     * [new] is what should be stored. [before] is what [decode] returned for [existing], the data that is stored now;
     * it is how a preferred provider that was not touched is told apart from one that was.
     */
    fun encode(new: ProviderPrefs, before: ProviderPrefs, existing: RawProviderPrefs): StoredProviderPrefs {
        val keptCustomPreferred = existing.provider
            ?.let { ProviderId.parse(it) }
            ?.takeIf { it.isCustom && new.provider == before.provider }
        return StoredProviderPrefs(
            provider = keptCustomPreferred?.value ?: new.provider.toId().value,
            acknowledged = new.acknowledged.map { it.toId().value }.toSet() + customIds(existing.acknowledged.orEmpty()),
            order = (new.order.map { it.toId().value } + customIds(existing.order?.split(",").orEmpty())).joinToString(","),
            disabled = new.disabled.map { it.toId().value }.toSet() + customIds(existing.disabled.orEmpty()),
        )
    }
}
