package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider
import java.util.UUID

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * The stable identity of a provider. Two kinds exist:
 *  - built-in: exactly the name of an [AiProvider] entry ("GEMINI", "GROQ", ...), so everything already stored under
 *    those names keeps meaning the same provider;
 *  - custom: "custom:" followed by a UUID, for providers the user adds. They are never enum entries.
 *
 * The constructor is private: the only way to get a ProviderId is [parse], [of], [custom] or [newCustom], so a
 * ProviderId that exists is always a valid one. A custom id is stored in lower case, so two spellings of the same
 * UUID are the same id.
 */
@JvmInline
value class ProviderId private constructor(val value: String) {

    val isCustom: Boolean get() = value.startsWith(CUSTOM_PREFIX)
    val isBuiltIn: Boolean get() = !isCustom

    override fun toString(): String = value

    companion object {
        const val CUSTOM_PREFIX = "custom:"

        private val uuidText = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** The id of a built-in provider. */
        fun of(provider: AiProvider): ProviderId = ProviderId(provider.name)

        /** A custom id from a UUID. */
        fun custom(uuid: UUID): ProviderId = ProviderId(CUSTOM_PREFIX + uuid.toString())

        /** A fresh random custom id, created once when the user adds a provider and then kept forever. */
        fun newCustom(): ProviderId = custom(UUID.randomUUID())

        /**
         * Reads a stored id. Returns null (never throws, never guesses) for anything that is not exactly a built-in
         * name or "custom:" plus a UUID; for example empty text, other letter case for a built-in name, surrounding
         * spaces, a missing or malformed UUID, or extra text after it.
         */
        fun parse(text: String?): ProviderId? {
            if (text == null) return null
            AiProvider.entries.firstOrNull { it.name == text }?.let { return of(it) }
            if (!text.startsWith(CUSTOM_PREFIX)) return null
            val uuid = text.substring(CUSTOM_PREFIX.length).lowercase()
            return if (uuidText.matches(uuid)) ProviderId(CUSTOM_PREFIX + uuid) else null
        }
    }
}

/** The provider id for this built-in provider. */
fun AiProvider.toId(): ProviderId = ProviderId.of(this)

/** The matching [AiProvider], or null for a custom id (custom providers are never enum entries). */
fun ProviderId.toAiProviderOrNull(): AiProvider? = AiProvider.entries.firstOrNull { it.name == value }
