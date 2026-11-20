package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.QuotaLedger
import dev.jarvis.assistant.providers.QuotaStore
import dev.jarvis.assistant.providers.toAiProviderOrNull
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider

private val builtInRegistry = ProviderRegistry.withBuiltIns()

/** The name shown for [id], as the real source gives it: the descriptor's label, or a generic name, never another provider's. */
fun builtInLabelOf(id: ProviderId): String = builtInRegistry.get(id)?.label ?: SettingsProviderSource.UNKNOWN_LABEL

/** A target for a built-in provider, with its real label. */
fun chatTarget(
    p: AiProvider, budget: Int = 10_000, model: String = "m", key: String = "KEY", system: String = "system", reminder: String = "",
) = ChatTarget(p.toId(), builtInLabelOf(p.toId()), budget, model, key, system, reminder)

class MemoryStore(var text: String = "") : QuotaStore {
    override fun load(): String = text
    override fun save(text: String) { this.text = text }
}

fun newLedger(clock: () -> Long = { System.currentTimeMillis() }) = QuotaLedger(MemoryStore(), clock)

class FakeNetwork : NetworkWatcher {
    @Volatile var waits = 0
    override suspend fun awaitConnected() { waits++ }
}

/** Providers by enum; test controls which are ready, enabled, and the preferred one. */
class FakeSource(
    private val llms: Map<AiProvider, LlmProvider>,
    var preferred: AiProvider = llms.keys.first(),
    var failoverEnabled: Boolean = true,
    var disabled: Set<AiProvider> = emptySet(),
    var withoutKey: Set<AiProvider> = emptySet(),
    var ledger: QuotaLedger? = null,
    private val order: List<AiProvider> = AiProvider.entries,
) : ProviderSource {
    override fun labelOf(id: ProviderId): String = builtInLabelOf(id)

    override suspend fun resolve(provider: ProviderId?): Resolved {
        val p = provider?.let { it.toAiProviderOrNull() ?: return Resolved.Stop(ChatProblem("unknown provider", null, true)) } ?: preferred
        val llm = llms[p] ?: return Resolved.Stop(ChatProblem("no adapter for ${p.label}", null, true))
        return Resolved.Ready(chatTarget(p, model = "model-${p.name}"), llm)
    }

    /** Every provider that was saved as the preferred one, in order (the real source writes it to the settings). */
    val savedPreferred = java.util.concurrent.CopyOnWriteArrayList<AiProvider>()

    override suspend fun setPreferred(provider: ProviderId) {
        val p = provider.toAiProviderOrNull() ?: throw IllegalArgumentException("not a built-in provider")
        savedPreferred += p
        preferred = p
    }

    override suspend fun snapshot() = ProviderSnapshot(
        preferred.toId(),
        failoverEnabled,
        order.map { p ->
            Candidate(
                p.toId(), "model-${p.name}", p !in disabled, p !in withoutKey && p in llms, true,
                ledger?.isExhausted(p) ?: false,
            )
        },
    )
}
