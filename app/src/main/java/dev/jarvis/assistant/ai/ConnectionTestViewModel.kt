package dev.jarvis.assistant.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.settings.SecureKeyStore
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestState {
    data object Idle : TestState
    data class Running(val text: String) : TestState
    data class Done(val text: String, val millis: Long, val provider: String, val model: String) : TestState
    data class Failed(val message: String) : TestState
}

/** Sends one message through the currently selected provider so you can check key, model and personality work. */
class ConnectionTestViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val keys = SecureKeyStore(app)
    private var job: Job? = null

    private val _state = MutableStateFlow<TestState>(TestState.Idle)
    val state: StateFlow<TestState> = _state.asStateFlow()

    fun run(prompt: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = TestState.Running("")
            val settings = repo.settings.first()
            val provider = settings.provider

            val llm = Providers.forProvider(provider)
            if (llm == null) {
                _state.value = TestState.Failed(ErrorMessages.of(LlmError.Unsupported("${provider.label} (arrives in Step 5)")))
                return@launch
            }
            // Consent first: nothing is sent until the user has accepted where it goes.
            if (provider !in settings.acknowledged) {
                _state.value = TestState.Failed(ErrorMessages.of(LlmError.NotAcknowledged))
                return@launch
            }
            val key = withContext(Dispatchers.IO) { keys.read(provider.keyName) }
            if (key.isNullOrBlank()) {
                _state.value = TestState.Failed(ErrorMessages.of(LlmError.NoKey))
                return@launch
            }

            val model = settings.activeModel()
            val request = ChatRequest(
                model = model,
                apiKey = key,
                systemPrompt = PromptBuilder.build(settings),
                messages = listOf(ChatMessage(ChatRole.USER, prompt)),
            )
            val started = System.currentTimeMillis()
            val reply = StringBuilder()
            try {
                llm.stream(request).collect { chunk ->
                    reply.append(chunk)
                    _state.value = TestState.Running(reply.toString())
                }
                _state.value = TestState.Done(
                    reply.toString(), System.currentTimeMillis() - started, provider.label, model,
                )
            } catch (e: LlmException) {
                _state.value = TestState.Failed(ErrorMessages.of(e.error))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = TestState.Failed("Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
