package dev.jarvis.assistant.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.providers.QuotaLedgers
import dev.jarvis.assistant.settings.SecureKeyStore
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Thin Android wrapper: wires the real settings, keys, quota ledger and network to [ChatRunner]. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = FileChatStorage(File(app.filesDir, "chats"))
    private val ledger = QuotaLedgers.get(app)
    private val runner = ChatRunner(
        storage = storage,
        engine = ChatEngine(storage),
        scope = viewModelScope,
        io = Dispatchers.IO,
        source = AndroidProviderSource(SettingsRepository(app), SecureKeyStore(app), ledger),
        ledger = ledger,
        network = AndroidNetworkWatcher(app),
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
    fun acceptRecovery() = runner.acceptRecovery()
    fun declineRecovery() = runner.declineRecovery()
    fun checkRecovery() = runner.checkRecovery()
}
