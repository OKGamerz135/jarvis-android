package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.providers.QuotaLedger
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings

// Pure Kotlin on purpose (no android.* imports): the Android pieces are passed in as functions, so the rules that
// decide what a request is sent to are unit-tested.

/**
 * Reads the settings and keys through the functions it is given and applies the consent and key rules. Used by the chat
 * AND by the connection tests, so both always agree on what "ready" means for a provider.
 */
class SettingsProviderSource(
    private val settings: suspend () -> JarvisSettings,
    /** Returns the saved key or null when there is none or it can't be read. May run blocking work. */
    private val readKey: suspend (keyName: String) -> String?,
    private val hasKey: (keyName: String) -> Boolean,
    private val llmFor: (AiProvider) -> LlmProvider,
    private val ledger: QuotaLedger,
    private val savePreferred: suspend (AiProvider) -> Unit,
) : ProviderSource {

    override suspend fun resolve(provider: AiProvider?): Resolved {
        val s = settings()
        val p = provider ?: s.provider
        if (p !in s.acknowledged) {
            return Resolved.Stop(ChatProblem(notAcknowledgedMessage(p), null, needsSetup = true))
        }
        val key = readKey(p.keyName)
        if (key.isNullOrBlank()) {
            return Resolved.Stop(ChatProblem(notConfiguredMessage(p), null, needsSetup = true))
        }
        val target = ChatTarget(
            provider = p,
            historyBudgetChars = ChatLimits.historyBudgetChars(p),
            model = s.modelOf(p),
            apiKey = key,
            systemPrompt = PromptBuilder.build(s),
            styleReminder = PromptBuilder.styleReminder(s),
        )
        return Resolved.Ready(target, llmFor(p))
    }

    override suspend fun snapshot(): ProviderSnapshot {
        val s = settings()
        val candidates = s.orderedProviders().map { p ->
            Candidate(
                provider = p,
                model = s.modelOf(p),
                enabled = p !in s.disabledProviders,
                hasKey = hasKey(p.keyName),
                acknowledged = p in s.acknowledged,
                exhausted = ledger.isExhausted(p),
            )
        }
        return ProviderSnapshot(s.provider, s.failoverEnabled, candidates)
    }

    override suspend fun setPreferred(provider: AiProvider) = savePreferred(provider)

    companion object {
        /** Shown when a request is refused because the provider has no usable key. Nothing was sent anywhere. */
        fun notConfiguredMessage(p: AiProvider) =
            "${p.label} is not configured: no usable API key is saved for it. Nothing was sent, and I did not switch to " +
                "another provider. Add its key under AI providers, or choose a different provider yourself."

        fun notAcknowledgedMessage(p: AiProvider) =
            "${ErrorMessages.of(LlmError.NotAcknowledged)} (${p.label}.) Nothing was sent, and I did not switch to " +
                "another provider."
    }
}
