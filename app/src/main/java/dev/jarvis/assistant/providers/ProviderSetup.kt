package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * What the "Key and model" screen may do for one provider id. Built-in providers are the only ones with stored settings
 * (model, acknowledgement) and a connection-test adapter today, so they are the only ones that can be edited.
 */
sealed interface ProviderSetup {
    /** A built-in provider: its screen works as it always has. */
    data class Editable(val provider: AiProvider, val descriptor: ProviderDescriptor) : ProviderSetup

    /**
     * A registered provider that has no setup storage yet (custom providers, until C4). Its screen is read-only: it shows
     * the name and says it is not available yet, and it has no key, model, consent or test state of any provider.
     */
    data class Unavailable(val descriptor: ProviderDescriptor) : ProviderSetup

    /** Nothing is registered under this id. There is no screen for it. */
    data object Unknown : ProviderSetup
}

object ProviderSetupResolver {
    /** Never maps an id to another provider: an id that is not a registered built-in is never [ProviderSetup.Editable]. */
    fun resolve(id: ProviderId, registry: ProviderRegistry): ProviderSetup {
        val descriptor = registry.get(id) ?: return ProviderSetup.Unknown
        val builtIn = id.toAiProviderOrNull() ?: return ProviderSetup.Unavailable(descriptor)
        return ProviderSetup.Editable(builtIn, descriptor)
    }

    /** One card on the Configure tab: [provider] is null for a provider that cannot be set up yet. */
    data class Row(val descriptor: ProviderDescriptor, val provider: AiProvider?)

    /** The Configure tab's cards: the registry's order, which is the built-in providers in [AiProvider] order first. */
    fun configureRows(registry: ProviderRegistry): List<Row> =
        registry.all().map { Row(it, it.id.toAiProviderOrNull()) }
}
