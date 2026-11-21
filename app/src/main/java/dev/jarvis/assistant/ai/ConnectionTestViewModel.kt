package dev.jarvis.assistant.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.chat.SettingsProviderSource
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.diag.UsageGuard
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.providerRegistry
import dev.jarvis.assistant.providers.QuotaLedgers
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.KeyChanges
import dev.jarvis.assistant.settings.AndroidKeyBook
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin Android wrapper around [ConnectionTester]. Every operation names the provider it is for; nothing here reads the
 * provider chosen for normal chat.
 */
class ConnectionTestViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val registry = app.providerRegistry()
    private val keys = AndroidKeyBook.create(app, registry)
    private val adapters = ProviderAdapters(registry, Providers::forProvider, Providers::diagnosticsFor)

    private val tester = ConnectionTester(
        scope = viewModelScope,
        source = SettingsProviderSource(
            registry = registry,
            settings = { repo.settings.first() },
            readKey = { id -> withContext(Dispatchers.IO) { keys.read(id) } },
            hasKey = keys::has,
            llmFor = adapters::llmFor,
            ledger = QuotaLedgers.get(app),
            savePreferred = {}, // tests never change the chat provider
        ),
        labelOf = ::labelOf,
        diagnosticsFor = adapters::diagnosticsFor,
        recentEntries = { DiagnosticsLog.entries.value },
    )

    /** Results per provider. A provider with no entry has nothing to show. */
    val states: StateFlow<Map<ProviderId, ProviderTestState>> = tester.states

    /** The name to show for [id], from its registered descriptor. */
    fun labelOf(id: ProviderId): String = registry.get(id)?.label ?: id.value

    /** The check running right now (any provider), or null. Only one runs at a time. */
    val active: StateFlow<ActiveTest?> = tester.active

    /** Generation HTTP attempts this phone made per provider in the last 24 hours, failed ones included. */
    val generationCalls24h: StateFlow<Map<String, Int>> = DiagnosticsLog.entries
        .map { list ->
            val since = System.currentTimeMillis() - UsageGuard.DAY_MS
            registry.all().associate { it.label to UsageGuard.generationCalls(list, it.label, since) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // A changed model or key makes that provider's earlier results stale.
        viewModelScope.launch {
            repo.settings
                .map { s -> AiProvider.entries.associate { it.toId() to s.modelOf(it) } }
                .distinctUntilChanged()
                .collect { tester.onModelsObserved(it) }
        }
        viewModelScope.launch { KeyChanges.events.collect { tester.invalidate(it) } }
    }

    /** Each returns false when another check is already running (nothing was started). */
    fun checkAvailability(provider: ProviderId): Boolean = tester.checkAvailability(provider)
    fun runTest(provider: ProviderId, prompt: String): Boolean = tester.runTest(provider, prompt)
    fun runDiagnostics(provider: ProviderId): Boolean = tester.runDiagnostics(provider)
}
