package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallEvents
import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.FailureFormatter
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What went wrong, in words the screen can show. [needsSetup] adds an "Open provider settings" button. */
data class ChatProblem(val message: String, val details: String?, val needsSetup: Boolean)

data class ChatUiState(
    val loaded: Boolean = false,
    val conversation: Conversation = Conversation.empty("pending", 0),
    /** The reply being streamed right now, or null. */
    val streaming: String? = null,
    val generating: Boolean = false,
    /** True from the instant Stop is tapped until the request has really ended. */
    val stopping: Boolean = false,
    /** For example "Retrying 1 of 2 in 2 s...". */
    val note: String? = null,
    val problem: ChatProblem? = null,
    /** Shown when a send was refused because a reply was still being generated. Never sent later. */
    val rejection: String? = null,
    val history: List<ConversationSummary> = emptyList(),
)

enum class SendResult { ACCEPTED, EMPTY, BUSY }

/** The outcome of checking settings, key and consent before a request. */
sealed interface Resolved {
    class Ready(val target: ChatTarget, val llm: LlmProvider) : Resolved
    class Stop(val problem: ChatProblem) : Resolved
}

/** One request at a time. [tryAcquire] is atomic, so rapid or concurrent sends can't both get through. */
class SendGate {
    private val busy = AtomicBoolean(false)
    val isBusy: Boolean get() = busy.get()
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)
    fun release() = busy.set(false)
}

/**
 * Owns the chat state and the rule that only one generation may run. Everything the screen can do goes through
 * here, so the Send button and the keyboard's Send action are protected by the same lock. Android-free, so it is
 * unit-tested.
 */
class ChatRunner(
    private val storage: ChatStorage,
    private val engine: ChatEngine,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val resolve: suspend () -> Resolved,
    private val maxRetries: Int = 2,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val gate = SendGate()
    private var job: Job? = null

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val (latest, list) = withContext(io) {
                val list = storage.list()
                list.firstOrNull()?.let { storage.load(it.id) } to list
            }
            _state.update { it.copy(loaded = true, conversation = latest ?: newConversation(), history = list) }
        }
    }

    private fun newConversation() = Conversation.empty(newId(), clock())

    private suspend fun refreshHistory() {
        val list = withContext(io) { storage.list() }
        _state.update { it.copy(history = list) }
    }

    // ------------------------------------------------------------------ navigation between chats

    fun newChat() {
        if (gate.isBusy) return
        _state.update { it.copy(conversation = newConversation(), problem = null, note = null, streaming = null, rejection = null) }
    }

    fun open(id: String) {
        if (gate.isBusy) return
        scope.launch {
            val conv = withContext(io) { storage.load(id) } ?: return@launch
            if (gate.isBusy) return@launch
            _state.update { it.copy(conversation = conv, problem = null, note = null, streaming = null, rejection = null) }
        }
    }

    fun delete(id: String) {
        if (gate.isBusy && _state.value.conversation.id == id) return
        scope.launch {
            withContext(io) { storage.delete(id) }
            if (_state.value.conversation.id == id && !gate.isBusy) {
                _state.update { it.copy(conversation = newConversation(), problem = null) }
            }
            refreshHistory()
        }
    }

    // ------------------------------------------------------------------ sending

    /**
     * The single entry point for sending, used by the button and by the keyboard's Send action.
     * BUSY means a reply is still being generated: the message is refused, not queued, and never sent later.
     */
    fun send(text: String): SendResult {
        val clean = text.trim()
        if (clean.isEmpty()) return SendResult.EMPTY
        if (!gate.tryAcquire()) {
            _state.update { it.copy(rejection = REJECTION) }
            return SendResult.BUSY
        }
        begin()
        launchGeneration(userText = clean)
        return SendResult.ACCEPTED
    }

    /** Asks again for an answer to the last user message, without adding it a second time. */
    fun retry(): SendResult {
        if (!_state.value.conversation.awaitingReply) return SendResult.EMPTY
        if (!gate.tryAcquire()) {
            _state.update { it.copy(rejection = REJECTION) }
            return SendResult.BUSY
        }
        begin()
        launchGeneration(userText = null)
        return SendResult.ACCEPTED
    }

    private fun begin() {
        _state.update { it.copy(generating = true, stopping = false, problem = null, note = null, rejection = null) }
    }

    /** Cancels the running request right now. The UI reacts at once; the request is closed by the adapters. */
    fun stop() {
        val j = job ?: return
        if (!gate.isBusy) return
        _state.update { it.copy(stopping = true, note = "Stopping...") }
        j.cancel()
    }

    fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }

    fun dismissRejection() {
        _state.update { it.copy(rejection = null) }
    }

    private fun launchGeneration(userText: String?) {
        val j = scope.launch { runGeneration(userText) }
        job = j
        // Runs however the job ends, even if it was cancelled before it started: the lock can never get stuck.
        j.invokeOnCompletion {
            _state.update { it.copy(generating = false, stopping = false, streaming = null, note = null) }
            gate.release()
        }
    }

    private suspend fun runGeneration(userText: String?) {
        try {
            if (userText != null) {
                val withUser = withContext(io) { engine.addUserMessage(_state.value.conversation, userText) }
                _state.update { it.copy(conversation = withUser) }
            }
            when (val r = resolve()) {
                is Resolved.Stop -> _state.update { it.copy(problem = r.problem) }
                is Resolved.Ready -> generate(r)
            }
            refreshHistory()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // The engine already saved any partial reply. Reload so the screen shows it, marked interrupted.
                val saved = withContext(io) { storage.load(_state.value.conversation.id) }
                val kept = saved?.messages?.lastOrNull()?.let { it.interrupted } == true
                _state.update {
                    it.copy(
                        conversation = saved ?: it.conversation,
                        problem = ChatProblem(
                            if (kept) "Response interrupted. The text received so far was kept."
                            else "Response interrupted before any text arrived.",
                            null,
                            needsSetup = false,
                        ),
                    )
                }
                refreshHistory()
            }
        } catch (e: Exception) {
            _state.update { it.copy(problem = ChatProblem("Unexpected error: ${e.javaClass.simpleName}", null, false)) }
        }
    }

    private suspend fun generate(r: Resolved.Ready) {
        val label = r.target.providerLabel
        val events = object : CallEvents {
            override fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) {
                _state.update { it.copy(note = "$label is overloaded. Retrying $retryNumber of $maxRetries in ${(waitMs + 500) / 1000} s...") }
            }
        }
        val result = withContext(io) {
            engine.generate(
                conv = _state.value.conversation,
                target = r.target,
                llm = r.llm,
                options = CallOptions(maxRetries, events),
                onPartial = { text -> _state.update { it.copy(streaming = text, note = null) } },
            )
        }
        when (result) {
            is GenResult.Success -> _state.update { it.copy(conversation = result.conversation) }
            is GenResult.Failure -> {
                val message = result.error?.let { ErrorMessages.of(it) } ?: "Something went wrong."
                val setup = result.error is LlmError.InvalidKey || result.error is LlmError.ModelNotFound
                _state.update {
                    it.copy(
                        conversation = result.conversation,
                        problem = ChatProblem(message, FailureFormatter.details(result.exception?.context), setup),
                    )
                }
            }
        }
    }

    companion object {
        const val REJECTION = "Still answering. Your message was NOT sent and was not queued. Wait for the reply or tap Stop, then send it again."
    }
}
