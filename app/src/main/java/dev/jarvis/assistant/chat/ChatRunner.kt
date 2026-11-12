package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallEvents
import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.FailureFormatter
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.ResetFormat
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.providers.QuotaLedger
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What went wrong, in words the screen can show. [needsSetup] adds an "Open provider settings" button. */
data class ChatProblem(val message: String, val details: String?, val needsSetup: Boolean)

/** The five-second "switching provider" announcement. Shown with a Cancel button. */
data class FailoverOffer(
    val from: AiProvider,
    val to: AiProvider,
    val toModel: String,
    val secondsLeft: Int,
    /** When the provider we're leaving should be back, in plain words, or that it's unknown. */
    val resetNote: String,
) {
    val announcement: String
        get() = "Your current AI provider has exhausted its available quota. Switching to ${to.label} in $secondsLeft seconds."
    val disclosure: String
        get() = "This conversation so far will be sent to ${to.label} (model $toModel). Your API keys never leave their own provider."
}

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
    /** Non-null during the countdown before switching provider. */
    val failover: FailoverOffer? = null,
    /** The provider in use after an automatic switch. Null means the preferred provider is in use. */
    val temporaryProvider: AiProvider? = null,
    /** The preferred provider that [temporaryProvider] replaced. */
    val preferredProvider: AiProvider? = null,
    /** Set when the preferred provider is available again; the screen asks whether to switch back. */
    val recoveryOffer: AiProvider? = null,
    /** True after "simulate quota exhaustion" was armed for the next message. */
    val simulationArmed: Boolean = false,
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
 * Owns the chat state, the rule that only one generation may run, and automatic provider failover.
 * Everything the screen can do goes through here. Android-free, so it is unit-tested.
 */
class ChatRunner(
    private val storage: ChatStorage,
    private val engine: ChatEngine,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val source: ProviderSource,
    private val ledger: QuotaLedger,
    private val network: NetworkWatcher = NoNetworkWait,
    private val maxRetries: Int = 2,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val countdownSeconds: Int = 5,
    private val tickMs: Long = 1000,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val gate = SendGate()
    private var job: Job? = null
    private var failoverCancel: CompletableDeferred<Unit>? = null
    private var stoppedDuringFailover = false
    private var failingProvider: AiProvider? = null
    private var recoveryDeclinedFor: AiProvider? = null
    private var recoveryTimer: Job? = null
    private val simulateNext = AtomicBoolean(false)
    private val visible = MutableStateFlow(true)

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
     * BUSY means a reply is still being generated (or a provider switch is counting down): the message is refused,
     * not queued, and never sent later.
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
        // Sending a message while the "switch back?" question is showing counts as "stay for now".
        _state.value.recoveryOffer?.let { recoveryDeclinedFor = it }
        _state.update { it.copy(generating = true, stopping = false, problem = null, note = null, rejection = null, recoveryOffer = null) }
    }

    /** Cancels the running request (or countdown) right now. */
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

    // ------------------------------------------------------------------ failover controls

    /** The Cancel button on the countdown. */
    fun cancelFailover() {
        failoverCancel?.complete(Unit)
    }

    /** The app tells us whether it's on screen. The countdown only counts down while it is. */
    fun setVisible(isVisible: Boolean) {
        visible.value = isVisible
    }

    /** Arms a fake "quota exhausted" for the next request. Nothing is sent to any provider. */
    fun armSimulation() {
        simulateNext.set(true)
        _state.update { it.copy(simulationArmed = true) }
    }

    /** Switch back to the preferred provider after it recovered. */
    fun acceptRecovery() {
        val back = _state.value.recoveryOffer ?: _state.value.preferredProvider
        if (back != null) ledger.clear(back)
        recoveryDeclinedFor = null
        _state.update { it.copy(temporaryProvider = null, preferredProvider = null, recoveryOffer = null) }
    }

    /** Stay on the temporary provider and stop asking about this recovery. */
    fun declineRecovery() {
        recoveryDeclinedFor = _state.value.recoveryOffer
        _state.update { it.copy(recoveryOffer = null) }
    }

    /**
     * Offers to switch back when the preferred provider is no longer known to be used up. Called after each
     * answer, when the app comes back to the screen, and when a known reset time passes. Never while a reply is
     * being generated, and only ever as a question.
     */
    fun checkRecovery() {
        scope.launch {
            val s = _state.value
            val preferred = s.preferredProvider ?: return@launch
            if (s.temporaryProvider == null || gate.isBusy || s.generating) return@launch
            if (ledger.isExhausted(preferred) || recoveryDeclinedFor == preferred) return@launch
            _state.update { it.copy(recoveryOffer = preferred) }
        }
    }

    private fun scheduleRecoveryCheck(provider: AiProvider) {
        recoveryTimer?.cancel()
        val until = ledger.record(provider)?.exhaustedUntil ?: return // unknown reset: nothing to wait for
        val wait = (until - clock()).coerceAtLeast(0) + 500
        recoveryTimer = scope.launch {
            delay(wait)
            checkRecovery()
        }
    }

    // ------------------------------------------------------------------ the generation loop

    private fun launchGeneration(userText: String?) {
        val j = scope.launch { runGeneration(userText) }
        job = j
        // Runs however the job ends, even if it was cancelled before it started: the lock can never get stuck.
        j.invokeOnCompletion {
            _state.update { it.copy(generating = false, stopping = false, streaming = null, note = null, failover = null) }
            gate.release()
            checkRecovery() // after the lock is free, so the question never appears mid-answer
        }
    }

    private suspend fun runGeneration(userText: String?) {
        try {
            if (userText != null) {
                val withUser = withContext(io) { engine.addUserMessage(_state.value.conversation, userText) }
                _state.update { it.copy(conversation = withUser) }
            }
            loop()
            refreshHistory()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { onCancelled() }
        } catch (e: Exception) {
            _state.update { it.copy(problem = ChatProblem("Unexpected error: ${e.javaClass.simpleName}", null, false)) }
        }
    }

    private suspend fun onCancelled() {
        val saved = withContext(io) { storage.load(_state.value.conversation.id) }
        if (stoppedDuringFailover) {
            stoppedDuringFailover = false
            val from = failingProvider
            _state.update {
                it.copy(
                    conversation = saved ?: it.conversation,
                    problem = ChatProblem(
                        "Switch cancelled. I stayed with ${from?.label ?: "the current provider"}, so I can't answer " +
                            "until you pick another provider or its quota resets.",
                        null,
                        needsSetup = true,
                    ),
                )
            }
        } else {
            val kept = saved?.messages?.lastOrNull()?.interrupted == true
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
        }
        refreshHistory()
    }

    /** Tries providers until one answers or we run out of sensible options. Never re-adds the user's message. */
    private suspend fun loop() {
        var provider: AiProvider? = _state.value.temporaryProvider
        val tried = LinkedHashSet<AiProvider>()
        var waitedRate = false
        var waitedNet = false

        while (true) {
            val ready = when (val r = source.resolve(provider)) {
                is Resolved.Stop -> {
                    _state.update { it.copy(problem = r.problem) }
                    return
                }
                is Resolved.Ready -> r
            }
            val current = ready.target.provider
            tried += current
            val snap = source.snapshot()

            // If we already know this provider is used up, say so now instead of spending a request to find out.
            val known = ledger.record(current)
            val knownExhausted = snap.failoverEnabled && known != null && ledger.isExhausted(current)
            val failure: GenResult.Failure = if (knownExhausted) {
                GenResult.Failure(
                    _state.value.conversation,
                    LlmError.QuotaExhausted(known!!.exhaustedUntil?.let { Instant.ofEpochMilli(it) }, known.quotaId, known.source),
                    null,
                )
            } else {
                val llm: LlmProvider = if (simulateNext.getAndSet(false)) {
                    _state.update { it.copy(simulationArmed = false) }
                    SimulatedQuotaProvider(Instant.ofEpochMilli(clock()))
                } else {
                    ready.llm
                }
                when (val result = generate(ready, llm)) {
                    is GenResult.Success -> {
                        ledger.clear(current) // it just worked, so whatever we remembered is out of date
                        _state.update { it.copy(conversation = result.conversation) }
                        return
                    }
                    is GenResult.Failure -> result
                }
            }

            _state.update { it.copy(conversation = failure.conversation) }
            val error = failure.error
            if (error is LlmError.QuotaExhausted && !knownExhausted) {
                ledger.markExhausted(current, error)
            }

            val ctx = FailoverContext(
                current = current,
                failoverEnabled = snap.failoverEnabled,
                candidates = snap.candidates,
                partialStarted = failure.partial,
                alreadyWaitedForRateLimit = waitedRate,
                alreadyWaitedForNetwork = waitedNet,
                alreadyTried = tried,
            )
            when (val d = FailoverPolicy.decide(error, ctx)) {
                is Decision.Switch -> {
                    val note = resetNote(current)
                    if (!countdown(current, d.to, note)) {
                        _state.update { it.copy(problem = cancelledProblem(current)) }
                        return
                    }
                    provider = d.to.provider
                    _state.update {
                        it.copy(
                            temporaryProvider = d.to.provider,
                            preferredProvider = it.preferredProvider ?: snap.preferred,
                            recoveryOffer = null,
                        )
                    }
                    recoveryDeclinedFor = null
                    scheduleRecoveryCheck(snap.preferred)
                }
                is Decision.WaitRateLimit -> {
                    waitedRate = true
                    for (s in d.seconds downTo 1) {
                        _state.update { it.copy(note = "Rate limit reached. Retrying once in $s s...") }
                        delay(tickMs)
                    }
                }
                Decision.WaitForNetwork -> {
                    waitedNet = true
                    _state.update { it.copy(note = "You're offline. Your message is saved. Waiting for a connection...") }
                    network.awaitConnected()
                    delay(tickMs) // let the connection settle before the one retry
                }
                Decision.FailoverOff -> {
                    _state.update {
                        it.copy(problem = ChatProblem(
                            ErrorMessages.of(error!!) + " Automatic provider failover is off, so I stayed with ${current.label}.",
                            FailureFormatter.details(failure.exception?.context),
                            needsSetup = true,
                        ))
                    }
                    return
                }
                is Decision.NoAlternative -> {
                    val why = d.reasons.joinToString("; ") { (p, r) -> "${p.label}: $r" }
                    _state.update {
                        it.copy(problem = ChatProblem(
                            ErrorMessages.of(error!!) + " No other provider is ready" + (if (why.isEmpty()) "." else " ($why).") +
                                " Set one up under AI providers.",
                            FailureFormatter.details(failure.exception?.context),
                            needsSetup = true,
                        ))
                    }
                    return
                }
                Decision.ShowError -> {
                    val message = error?.let { ErrorMessages.of(it) } ?: "Something went wrong."
                    val setup = error is LlmError.InvalidKey || error is LlmError.ModelNotFound
                    _state.update {
                        it.copy(problem = ChatProblem(message, FailureFormatter.details(failure.exception?.context), setup))
                    }
                    return
                }
            }
        }
    }

    private fun resetNote(provider: AiProvider): String {
        val r = ledger.record(provider) ?: return "reset time unknown"
        val until = r.exhaustedUntil ?: return "reset time unknown"
        val when_ = ResetFormat.describe(Instant.ofEpochMilli(until), Instant.ofEpochMilli(clock()), zone)
        return when (r.source) {
            ResetSource.REPORTED -> "resets $when_ (reported by the provider)"
            ResetSource.DOCUMENTED -> "resets $when_ (per its documentation, not reported in the error)"
            ResetSource.SIMULATED -> "simulated test"
            ResetSource.UNKNOWN -> "reset time unknown"
        }
    }

    private fun cancelledProblem(from: AiProvider) = ChatProblem(
        "Switch cancelled. I stayed with ${from.label}, which has used its quota, so I can't answer until you pick " +
            "another provider or its quota resets.",
        null,
        needsSetup = true,
    )

    /** The five-second announcement. Returns false if the user cancelled. Pauses while the app is off screen. */
    private suspend fun countdown(from: AiProvider, to: Candidate, resetNote: String): Boolean {
        val cancel = CompletableDeferred<Unit>()
        failoverCancel = cancel
        failingProvider = from
        try {
            for (s in countdownSeconds downTo 1) {
                _state.update { it.copy(failover = FailoverOffer(from, to.provider, to.model, s, resetNote)) }
                visible.first { it } // never switch while the user can't see the announcement
                if (withTimeoutOrNull(tickMs) { cancel.await() } != null) return false
            }
            return true
        } catch (e: CancellationException) {
            stoppedDuringFailover = true
            throw e
        } finally {
            failoverCancel = null
            _state.update { it.copy(failover = null) }
        }
    }

    private suspend fun generate(r: Resolved.Ready, llm: LlmProvider): GenResult {
        val label = r.target.providerLabel
        val events = object : CallEvents {
            override fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) {
                _state.update { it.copy(note = "$label is overloaded. Retrying $retryNumber of $maxRetries in ${(waitMs + 500) / 1000} s...") }
            }
        }
        return withContext(io) {
            engine.generate(
                conv = _state.value.conversation,
                target = r.target,
                llm = llm,
                options = CallOptions(maxRetries, events),
                onPartial = { text -> _state.update { it.copy(streaming = text, note = null) } },
            )
        }
    }

    companion object {
        const val REJECTION = "Still answering. Your message was NOT sent and was not queued. Wait for the reply or tap Stop, then send it again."
    }
}
