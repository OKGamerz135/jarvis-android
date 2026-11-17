package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.providers.QuotaLedger
import dev.jarvis.assistant.providers.QuotaStore
import dev.jarvis.assistant.settings.AiProvider

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
    override suspend fun resolve(provider: AiProvider?): Resolved {
        val p = provider ?: preferred
        val llm = llms[p] ?: return Resolved.Stop(ChatProblem("no adapter for ${p.label}", null, true))
        return Resolved.Ready(ChatTarget(p, 10_000, "model-${p.name}", "KEY", "system"), llm)
    }

    /** Every provider that was saved as the preferred one, in order (the real source writes it to the settings). */
    val savedPreferred = java.util.concurrent.CopyOnWriteArrayList<AiProvider>()

    override suspend fun setPreferred(provider: AiProvider) {
        savedPreferred += provider
        preferred = provider
    }

    override suspend fun snapshot() = ProviderSnapshot(
        preferred,
        failoverEnabled,
        order.map { p ->
            Candidate(
                p, "model-${p.name}", p !in disabled, p !in withoutKey && p in llms, true,
                ledger?.isExhausted(p) ?: false,
            )
        },
    )
}
