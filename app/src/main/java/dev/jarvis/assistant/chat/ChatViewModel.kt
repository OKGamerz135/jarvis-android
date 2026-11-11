package dev.jarvis.assistant.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.ai.Providers
import dev.jarvis.assistant.settings.SecureKeyStore
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Thin Android wrapper: gathers settings and the key, and hands everything else to [ChatRunner]. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    private val keys = SecureKeyStore(app)
    private val storage = FileChatStorage(File(app.filesDir, "chats"))
    private val runner = ChatRunner(storage, ChatEngine(storage), viewModelScope, Dispatchers.IO, { resolve() })

    val state: StateFlow<ChatUiState> = runner.state

    fun send(text: String): SendResult = runner.send(text)
    fun retry(): SendResult = runner.retry()
    fun stop() = runner.stop()
    fun newChat() = runner.newChat()
    fun open(id: String) = runner.open(id)
    fun delete(id: String) = runner.delete(id)
    fun dismissProblem() = runner.dismissProblem()
    fun dismissRejection() = runner.dismissRejection()

    private suspend fun resolve(): Resolved {
        val settings = settingsRepo.settings.first()
        val provider = settings.provider
        if (provider !in settings.acknowledged) {
            return Resolved.Stop(ChatProblem(ErrorMessages.of(LlmError.NotAcknowledged), null, needsSetup = true))
        }
        val key = withContext(Dispatchers.IO) { keys.read(provider.keyName) }
        if (key.isNullOrBlank()) {
            return Resolved.Stop(ChatProblem(ErrorMessages.of(LlmError.NoKey), null, needsSetup = true))
        }
        val target = ChatTarget(
            providerLabel = provider.label,
            historyBudgetChars = ChatLimits.historyBudgetChars(provider),
            model = settings.activeModel(),
            apiKey = key,
            systemPrompt = PromptBuilder.build(settings),
        )
        return Resolved.Ready(target, Providers.forProvider(provider))
    }
}
