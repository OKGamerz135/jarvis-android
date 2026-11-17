package dev.jarvis.assistant.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.ai.Providers
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.memory.Memories
import dev.jarvis.assistant.providers.QuotaLedgers
import dev.jarvis.assistant.settings.SecureKeyStore
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
    private val keyStore = SecureKeyStore(app)
    private val hooks = AndroidMemoryHooks(settingsRepo, Memories.get(app))
    private val runner = ChatRunner(
        storage = storage,
        engine = ChatEngine(storage, memory = hooks),
        scope = viewModelScope,
        io = Dispatchers.IO,
        source = SettingsProviderSource(
            settings = { settingsRepo.settings.first() },
            readKey = { name -> withContext(Dispatchers.IO) { keyStore.read(name) } },
            hasKey = keyStore::has,
            llmFor = Providers::forProvider,
            ledger = ledger,
            savePreferred = { p -> settingsRepo.update { it.copy(provider = p) } },
        ),
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
    fun choosePreferred(provider: dev.jarvis.assistant.settings.AiProvider) = runner.choosePreferred(provider)
    fun acceptRecovery() = runner.acceptRecovery()
    fun declineRecovery() = runner.declineRecovery()
    fun checkRecovery() = runner.checkRecovery()
}
