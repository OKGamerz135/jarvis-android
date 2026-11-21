package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * What the app needs to know about a provider in order to list it, label it and find its saved key.
 * It describes a provider; it does not talk to one. Protocol, endpoint and capability details are added later,
 * by the stages that actually use them.
 *
 * @property keyName where this provider's API key is stored in the secure key store. No two registered providers
 * may share one, otherwise one provider could read another's key (see [ProviderRegistry.register]).
 */
data class ProviderDescriptor(
    val id: ProviderId,
    val label: String,
    val keyName: String,
    val defaultModel: String,
    val keyHelp: String,
    val notice: String,
) {
    init {
        require(label.isNotBlank()) { "A provider needs a label." }
        require(ProviderLabels.hasVisibleCharacter(label)) { "A provider's label needs at least one visible character." }
        require(keyName.isNotBlank()) { "A provider needs a key name." }
    }

    val isBuiltIn: Boolean get() = id.isBuiltIn

    companion object {
        /** The descriptor of a built-in provider, read from [AiProvider] so the texts exist in one place only. */
        fun of(provider: AiProvider): ProviderDescriptor = ProviderDescriptor(
            id = provider.toId(),
            label = provider.label,
            keyName = provider.keyName,
            defaultModel = provider.defaultModel,
            keyHelp = provider.keyHelp,
            notice = provider.notice,
        )
    }
}
