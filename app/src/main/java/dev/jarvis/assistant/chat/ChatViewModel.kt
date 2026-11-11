package dev.jarvis.assistant.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.ai.CallEvents
import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.FailureFormatter
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.ai.Providers
import dev.jarvis.assistant.settings.SecureKeyStore
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What went wrong, in words the screen can show. [needsSetup] adds an "Open provider settings" button. */
data class ChatProblem(val message: String, val details: String?, val needsSetup: Boolean)

data class ChatUiState(
    val loaded: Boolean = false,
    val conversation: Conversation = Conversation.empty("pending", 0),
    /** The reply being streamed right now, or null. */
    val streaming: String? = null,
    val generating: Boolean = false,
    /** For example "Retrying 1 of 2 in 2 s...". */
    val note: String? = null,
    val problem: ChatProblem? = null,
    val history: List<ConversationSummary> = emptyList(),
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    private val keys = SecureKeyStore(app)
    private val storage = FileChatStorage(File(app.filesDir, "chats"))
    private val engine = ChatEngine(storage)
    private var job: Job? = null

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (latest, list) = withContext(Dispatchers.IO) {
                val list = storage.list()
                list.firstOrNull()?.let { storage.load(it.id) } to list
            }
            _state.update {
                it.copy(
                    loaded = true,
                    conversation = latest ?: newConversation(),
                    history = list,
                )
            }
        }
    }

    private fun newConversation() = Conversation.empty(UUID.randomUUID().toString(), System.currentTimeMillis())

    private suspend fun refreshHistory() {
        val list = withContext(Dispatchers.IO) { storage.list() }
        _state.update { it.copy(history = list) }
    }

    fun newChat() {
        if (_state.value.generating) return
        _state.update { it.copy(conversation = newConversation(), problem = null, note = null, streaming = null) }
    }

    fun open(id: String) {
        if (_state.value.generating) return
        viewModelScope.launch {
            val conv = withContext(Dispatchers.IO) { storage.load(id) } ?: return@launch
            _state.update { it.copy(conversation = conv, problem = null, note = null, streaming = null) }
        }
    }

    fun delete(id: String) {
        if (_state.value.generating && _state.value.conversation.id == id) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storage.delete(id) }
            if (_state.value.conversation.id == id) _state.update { it.copy(conversation = newConversation(), problem = null) }
            refreshHistory()
        }
    }

    /** Sends a new message. Ignored while a reply is being generated, which also stops accidental double sends. */
    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _state.value.generating) return
        _state.update { it.copy(generating = true, problem = null, note = null) }
        job = viewModelScope.launch {
            val withUser = withContext(Dispatchers.IO) { engine.addUserMessage(_state.value.conversation, clean) }
            _state.update { it.copy(conversation = withUser) }
            runGeneration()
        }
    }

    /** Asks again for an answer to the last user message, without adding it a second time. */
    fun retry() {
        val s = _state.value
        if (s.generating || !s.conversation.awaitingReply) return
        _state.update { it.copy(generating = true, problem = null, note = null) }
        job = viewModelScope.launch { runGeneration() }
    }

    fun stop() {
        job?.cancel()
    }

    fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }

    private suspend fun runGeneration() {
        try {
            val settings = settingsRepo.settings.first()
            val provider = settings.provider
            if (provider !in settings.acknowledged) {
                fail(ChatProblem(ErrorMessages.of(LlmError.NotAcknowledged), null, needsSetup = true))
                return
            }
            val key = withContext(Dispatchers.IO) { keys.read(provider.keyName) }
            if (key.isNullOrBlank()) {
                fail(ChatProblem(ErrorMessages.of(LlmError.NoKey), null, needsSetup = true))
                return
            }
            val target = ChatTarget(
                providerLabel = provider.label,
                historyBudgetChars = ChatLimits.historyBudgetChars(provider),
                model = settings.activeModel(),
                apiKey = key,
                systemPrompt = PromptBuilder.build(settings),
            )
            val events = object : CallEvents {
                override fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) {
                    _state.update {
                        it.copy(note = "${provider.label} is overloaded. Retrying $retryNumber of $maxRetries in ${(waitMs + 500) / 1000} s...")
                    }
                }
            }
            val result = withContext(Dispatchers.IO) {
                engine.generate(
                    conv = _state.value.conversation,
                    target = target,
                    llm = Providers.forProvider(provider),
                    options = CallOptions(MAX_RETRIES, events),
                    onPartial = { text -> _state.update { it.copy(streaming = text, note = null) } },
                )
            }
            when (result) {
                is GenResult.Success ->
                    _state.update { it.copy(conversation = result.conversation, streaming = null, generating = false, note = null) }
                is GenResult.Failure -> {
                    val message = result.error?.let { ErrorMessages.of(it) } ?: "Something went wrong."
                    val setup = result.error is LlmError.InvalidKey || result.error is LlmError.ModelNotFound
                    _state.update {
                        it.copy(
                            conversation = result.conversation,
                            streaming = null,
                            generating = false,
                            note = null,
                            problem = ChatProblem(message, FailureFormatter.details(result.exception?.context), setup),
                        )
                    }
                }
            }
            refreshHistory()
        } catch (e: CancellationException) {
            // Stop button: the engine has already saved any partial reply. Reload so the screen shows it.
            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) { storage.load(_state.value.conversation.id) }
                _state.update {
                    it.copy(conversation = saved ?: it.conversation, streaming = null, generating = false, note = null)
                }
                refreshHistory()
            }
        } catch (e: Exception) {
            fail(ChatProblem("Unexpected error: ${e.javaClass.simpleName}", null, needsSetup = false))
        }
    }

    private fun fail(problem: ChatProblem) {
        _state.update { it.copy(generating = false, streaming = null, note = null, problem = problem) }
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
