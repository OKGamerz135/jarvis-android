package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/** The outcome of [ProviderRegistry.register]. Nothing is ever replaced silently. */
sealed interface RegisterResult {
    data object Added : RegisterResult

    /** The id is already registered (a built-in always is). The registration that was there is unchanged. */
    data class AlreadyRegistered(val existing: ProviderDescriptor) : RegisterResult

    /** Another provider already uses this key name, so the new one would read that provider's saved key. */
    data class KeyNameInUse(val usedBy: ProviderId) : RegisterResult

    /**
     * Another provider already has this label (compared after [ProviderLabels.normalize]: case, spacing and invisible
     * characters do not make a different label). Diagnostics, cooldown and usage are matched on the label, so two
     * providers sharing one would share that state.
     */
    data class LabelInUse(val usedBy: ProviderId) : RegisterResult
}

/** The outcome of [ProviderRegistry.remove]. */
enum class RemoveResult { Removed, NotFound, BuiltInProtected }

/**
 * The providers JARVIS knows about: the built-in ones, which are always there, and custom ones the user adds.
 *
 * It only keeps the list. It does not save anything, talk to a provider, or decide what is usable; that stays with
 * the code that already does so. It is safe to use from several threads. [all] lists the built-in providers first
 * (in [AiProvider] order) and then custom ones in the order they were registered.
 */
class ProviderRegistry private constructor() {
    private val lock = Any()
    private val entries = LinkedHashMap<ProviderId, ProviderDescriptor>()

    init {
        for (p in AiProvider.entries) entries[p.toId()] = ProviderDescriptor.of(p)
    }

    /** Adds a provider. Refuses, without changing anything, an id that is already registered, a key name or a label already in use (checked in that order). */
    fun register(descriptor: ProviderDescriptor): RegisterResult = synchronized(lock) {
        entries[descriptor.id]?.let { return RegisterResult.AlreadyRegistered(it) }
        entries.values.firstOrNull { it.keyName == descriptor.keyName }?.let { return RegisterResult.KeyNameInUse(it.id) }
        val wanted = ProviderLabels.normalize(descriptor.label)
        entries.values.firstOrNull { ProviderLabels.normalize(it.label) == wanted }?.let { return RegisterResult.LabelInUse(it.id) }
        entries[descriptor.id] = descriptor
        RegisterResult.Added
    }

    /** The provider with this id, or null if there is none. */
    fun get(id: ProviderId): ProviderDescriptor? = synchronized(lock) { entries[id] }

    fun contains(id: ProviderId): Boolean = synchronized(lock) { entries.containsKey(id) }

    /** Every registered provider, as a snapshot: later changes to the registry do not change a list already returned. */
    fun all(): List<ProviderDescriptor> = synchronized(lock) { entries.values.toList() }

    /** Only the custom providers, in registration order. */
    fun custom(): List<ProviderDescriptor> = all().filter { !it.isBuiltIn }

    /** Removes a custom provider. A built-in provider is never removed. Other registrations are not affected. */
    fun remove(id: ProviderId): RemoveResult = synchronized(lock) {
        when {
            id.isBuiltIn && entries.containsKey(id) -> RemoveResult.BuiltInProtected
            entries.remove(id) != null -> RemoveResult.Removed
            else -> RemoveResult.NotFound
        }
    }

    companion object {
        /** A new registry holding the built-in providers. Each call gives an independent registry; there is no shared global. */
        fun withBuiltIns(): ProviderRegistry = ProviderRegistry()
    }
}
