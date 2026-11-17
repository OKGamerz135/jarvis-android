package dev.jarvis.assistant.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.chat.SettingsProviderSource
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.diag.UsageGuard
import dev.jarvis.assistant.providers.QuotaLedgers
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.KeyChanges
import dev.jarvis.assistant.settings.SecureKeyStore
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
    private val keys = SecureKeyStore(app)

    private val tester = ConnectionTester(
        scope = viewModelScope,
        source = SettingsProviderSource(
            settings = { repo.settings.first() },
            readKey = { name -> withContext(Dispatchers.IO) { keys.read(name) } },
            hasKey = keys::has,
            llmFor = Providers::forProvider,
            ledger = QuotaLedgers.get(app),
            savePreferred = {}, // tests never change the chat provider
        ),
        diagnosticsFor = Providers::diagnosticsFor,
        recentEntries = { DiagnosticsLog.entries.value },
    )

    /** Results per provider. A provider with no entry has nothing to show. */
    val states: StateFlow<Map<AiProvider, ProviderTestState>> = tester.states

    /** The check running right now (any provider), or null. Only one runs at a time. */
    val active: StateFlow<ActiveTest?> = tester.active

    /** Generation HTTP attempts this phone made per provider in the last 24 hours, failed ones included. */
    val generationCalls24h: StateFlow<Map<String, Int>> = DiagnosticsLog.entries
        .map { list ->
            val since = System.currentTimeMillis() - UsageGuard.DAY_MS
            AiProvider.entries.associate { it.label to UsageGuard.generationCalls(list, it.label, since) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // A changed model or key makes that provider's earlier results stale.
        viewModelScope.launch {
            repo.settings
                .map { s -> AiProvider.entries.associateWith { s.modelOf(it) } }
                .distinctUntilChanged()
                .collect { tester.onModelsObserved(it) }
        }
        viewModelScope.launch { KeyChanges.events.collect { tester.invalidate(it) } }
    }

    /** Each returns false when another check is already running (nothing was started). */
    fun checkAvailability(provider: AiProvider): Boolean = tester.checkAvailability(provider)
    fun runTest(provider: AiProvider, prompt: String): Boolean = tester.runTest(provider, prompt)
    fun runDiagnostics(provider: AiProvider): Boolean = tester.runDiagnostics(provider)
}
