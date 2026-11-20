package dev.jarvis.assistant.settings

import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/** The saved-key operations, by credential name. The Android implementation is [SecureKeyStore] (see AndroidKeyBook). */
interface KeyVault {
    fun has(name: String): Boolean
    fun save(name: String, secret: String): Boolean
    fun read(name: String): String?
    fun remove(name: String): Boolean
    fun state(name: String): KeyState
}

/**
 * Saved API keys by provider. This is the one place that turns a [ProviderId] into the name a key is stored under; the
 * name comes from the provider's registered descriptor, and for the built-in providers it is exactly the name Build 46
 * and earlier already used (nothing is renamed, moved or re-encrypted, so every saved key stays where it was).
 *
 * A provider that is not registered has no credential name. Every operation then does nothing: it reads nothing,
 * writes nothing, deletes nothing, and never falls back to another provider's name. "No key" is the answer, never a guess.
 */
class KeyBook(private val registry: ProviderRegistry, private val vault: KeyVault) {

    /** The name this provider's key is stored under, or null if the provider is not registered. */
    fun keyNameOf(id: ProviderId): String? = registry.get(id)?.keyName

    fun has(id: ProviderId): Boolean = keyNameOf(id)?.let(vault::has) ?: false

    /** The saved key, or null when there is none, it can't be read right now, or the provider is not registered. */
    fun read(id: ProviderId): String? = keyNameOf(id)?.let(vault::read)

    /** True only when the encrypted key is on disk. False, and nothing written, for a provider that is not registered. */
    fun save(id: ProviderId, secret: String): Boolean = keyNameOf(id)?.let { vault.save(it, secret) } ?: false

    /** False, and nothing touched, for a provider that is not registered. */
    fun remove(id: ProviderId): Boolean = keyNameOf(id)?.let(vault::remove) ?: false

    fun state(id: ProviderId): KeyState = keyNameOf(id)?.let(vault::state) ?: KeyState.MISSING

    /** The state of every registered provider's key (reads each key). Background thread only. */
    fun states(): Map<ProviderId, KeyState> = registry.all().associate { it.id to vault.state(it.keyName) }

    /** Whether a key is stored for every registered provider (reads nothing). */
    fun presence(): Map<ProviderId, KeyState> =
        registry.all().associate { it.id to if (vault.has(it.keyName)) KeyState.USABLE else KeyState.MISSING }

    /** Deletes keys saved for providers that were removed from the app. Failures are ignored on purpose. */
    fun removeRetired() {
        for (name in RETIRED_KEY_NAMES) runCatching { vault.remove(name) }
    }

    companion object {
        /** SambaNova was removed from the app; a key saved for it is deleted at startup. */
        val RETIRED_KEY_NAMES: List<String> = listOf("sambanova_api_key")
    }
}
