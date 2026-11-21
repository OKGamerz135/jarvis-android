package dev.jarvis.assistant.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.ai.Providers
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.memory.Memories
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.providerRegistry
import dev.jarvis.assistant.providers.QuotaLedgers
import dev.jarvis.assistant.settings.AndroidKeyBook
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Thin Android wrapper: wires the real settings, keys, quota ledger and network to [ChatRunner]. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = FileChatStorage(File(app.filesDir, "chats"))
    private val ledger = QuotaLedgers.get(app)
    private val settingsRepo = SettingsRepository(app)
    private val registry = app.providerRegistry()
    private val keyBook = AndroidKeyBook.create(app, registry)
    private val hooks = AndroidMemoryHooks(settingsRepo, Memories.get(app))
    private val source = SettingsProviderSource(
        registry = registry,
        settings = { settingsRepo.settings.first() },
        readKey = { id -> withContext(Dispatchers.IO) { keyBook.read(id) } },
        hasKey = keyBook::has,
        llmFor = Providers::forProvider,
        ledger = ledger,
        savePreferred = { p -> settingsRepo.update { it.copy(provider = p) } },
    )
    private val runner = ChatRunner(
        storage = storage,
        engine = ChatEngine(storage, memory = hooks),
        scope = viewModelScope,
        io = Dispatchers.IO,
        source = source,
        ledger = ledger,
        network = AndroidNetworkWatcher(app),
        trace = DiagnosticsTraceSink(DiagnosticsLog, { DiagnosticsLog.entries.value }),
        memoryObserver = hooks,
    )

    val state: StateFlow<ChatUiState> = runner.state

    fun send(text: String): SendResult = runner.send(text)
    fun retry(): SendResult = runner.retry()
    fun stop() = runner.stop()
    fun newChat() = runner.newChat()
    fun open(id: String) = runner.open(id)
    fun delete(id: String) = runner.delete(id)
    fun dismissProblem() = runner.dismissProblem()
    fun dismissRejection() = runner.dismissRejection()
    fun cancelFailover() = runner.cancelFailover()
    fun setVisible(visible: Boolean) = runner.setVisible(visible)
    fun armSimulation() = runner.armSimulation()
    fun choosePreferred(provider: ProviderId) = runner.choosePreferred(provider)

    /** The name to show for a provider id. Display only. */
    fun labelOf(id: ProviderId): String = source.labelOf(id)
    fun acceptRecovery() = runner.acceptRecovery()
    fun declineRecovery() = runner.declineRecovery()
    fun checkRecovery() = runner.checkRecovery()
}
