package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.QuotaLedger
import dev.jarvis.assistant.providers.toAiProviderOrNull
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings

// Pure Kotlin on purpose (no android.* imports): the Android pieces are passed in as functions, so the rules that
// decide what a request is sent to are unit-tested.

/**
 * Reads the settings and keys through the functions it is given and applies the consent and key rules. Used by the chat
 * AND by the connection tests, so both always agree on what "ready" means for a provider.
 *
 * Providers are identified by [ProviderId]. A provider can only be used if it is registered AND has an adapter here;
 * today only the built-in providers do. Anything else (an id nobody registered, or a registered provider that cannot
 * run yet) is refused with [Resolved.Stop] before a key is read or an adapter is built, and is never mapped to another
 * provider.
 *
 * Before any custom provider is allowed to run, two things that still use [AiProvider] must move to [ProviderId]:
 *  - ChatLimits.historyBudgetChars (HistoryTrimmer): custom providers get a conservative 10,000 character budget, with
 *    tests that they cannot skip trimming;
 *  - QuotaLedger: so a custom provider's quota is remembered under its own id (ChatRunner does nothing with the ledger
 *    for such a provider today, and never maps it onto a built-in provider's entry).
 */
class SettingsProviderSource(
    private val registry: ProviderRegistry,
    private val settings: suspend () -> JarvisSettings,
    /** Returns the saved key or null when there is none or it can't be read. May run blocking work. */
    private val readKey: suspend (ProviderId) -> String?,
    private val hasKey: (ProviderId) -> Boolean,
    private val llmFor: (AiProvider) -> LlmProvider,
    private val ledger: QuotaLedger,
    private val savePreferred: suspend (AiProvider) -> Unit,
) : ProviderSource {

    override fun labelOf(id: ProviderId): String = registry.get(id)?.label ?: UNKNOWN_LABEL

    override suspend fun resolve(provider: ProviderId?): Resolved {
        val s = settings()
        val id = provider ?: s.provider.toId()
        val descriptor = registry.get(id)
            ?: return Resolved.Stop(ChatProblem(unknownProviderMessage(), null, needsSetup = true))
        // Only providers with an adapter in this build can run. (The generic adapter for custom providers comes later.)
        val builtIn = id.toAiProviderOrNull()
            ?: return Resolved.Stop(ChatProblem(cannotRunYetMessage(descriptor.label), null, needsSetup = true))
        if (builtIn !in s.acknowledged) {
            return Resolved.Stop(ChatProblem(notAcknowledgedMessage(descriptor.label), null, needsSetup = true))
        }
        val key = readKey(id)
        if (key.isNullOrBlank()) {
            return Resolved.Stop(ChatProblem(notConfiguredMessage(descriptor.label), null, needsSetup = true))
        }
        val target = ChatTarget(
            provider = id,
            providerLabel = descriptor.label,
            historyBudgetChars = ChatLimits.historyBudgetChars(builtIn),
            model = s.modelOf(builtIn),
            apiKey = key,
            systemPrompt = PromptBuilder.build(s),
            styleReminder = PromptBuilder.styleReminder(s),
        )
        return Resolved.Ready(target, llmFor(builtIn))
    }

    override suspend fun snapshot(): ProviderSnapshot {
        val s = settings()
        // Only built-in providers are in the saved order today; each one is looked up by its own id.
        val candidates = s.orderedProviders().map { p ->
            val id = p.toId()
            Candidate(
                provider = id,
                model = s.modelOf(p),
                enabled = p !in s.disabledProviders,
                hasKey = hasKey(id),
                acknowledged = p in s.acknowledged,
                exhausted = ledger.isExhausted(p),
            )
        }
        return ProviderSnapshot(s.provider.toId(), s.failoverEnabled, candidates)
    }

    /** Saves [provider] as the preferred one. Refuses (throws) for a provider that is not a built-in one: nothing is saved. */
    override suspend fun setPreferred(provider: ProviderId) {
        val builtIn = provider.toAiProviderOrNull()
            ?: throw IllegalArgumentException("This provider can't be the preferred one yet.")
        savePreferred(builtIn)
    }

    companion object {
        /** The name shown for an id that is not registered. Never another provider's name. */
        const val UNKNOWN_LABEL = "Unknown provider"

        /** Shown when a request is refused because the provider has no usable key. Nothing was sent anywhere. */
        fun notConfiguredMessage(label: String) =
            "$label is not configured: no usable API key is saved for it. Nothing was sent, and I did not switch to " +
                "another provider. Add its key under AI providers, or choose a different provider yourself."

        fun notAcknowledgedMessage(label: String) =
            "${ErrorMessages.of(LlmError.NotAcknowledged)} ($label.) Nothing was sent, and I did not switch to " +
                "another provider."

        fun unknownProviderMessage() =
            "That provider isn't known to this app. Nothing was sent, and I did not switch to another provider. " +
                "Choose a provider under AI providers."

        fun cannotRunYetMessage(label: String) =
            "$label can't be used for chat yet. Nothing was sent, and I did not switch to another provider. " +
                "Choose a different provider under AI providers."
    }
}
