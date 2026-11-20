package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallEvents
import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.FailureFormatter
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.ResetFormat
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.ai.ChatRole
import dev.jarvis.assistant.memory.ChatTurn
import dev.jarvis.assistant.memory.MemoryStatusNote
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.QuotaLedger
import dev.jarvis.assistant.providers.QuotaRecord
import dev.jarvis.assistant.providers.toAiProviderOrNull
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
    val from: ProviderId,
    /** The names shown for [from] and [to] (from their descriptors). */
    val fromLabel: String,
    val to: ProviderId,
    val toLabel: String,
    val toModel: String,
    val secondsLeft: Int,
    /** When the provider we're leaving should be back, in plain words, or that it's unknown. */
    val resetNote: String,
) {
    val announcement: String
        get() = "Your current AI provider has exhausted its available quota. Switching to $toLabel in $secondsLeft seconds."
    val disclosure: String
        get() = "This conversation so far, and any saved memories that are relevant to it, will be sent to $toLabel (model $toModel). Your API keys never leave their own provider."
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
    /** The provider an automatic switch put in use (it is also the saved preferred provider then). Null = no switch is active. */
    val temporaryProvider: ProviderId? = null,
    /** The provider that was preferred before the automatic switch; the "switch back?" question is about this one. */
    val preferredProvider: ProviderId? = null,
    /** Set when the preferred provider is available again; the screen asks whether to switch back. */
    val recoveryOffer: ProviderId? = null,
    /** Set when the user's choice of preferred provider (or "switch back") could not be saved. Nothing was changed. */
    val preferenceError: String? = null,
    /** True after "simulate quota exhaustion" was armed for the next message. */
    val simulationArmed: Boolean = false,
    /** Which adapter really handled the latest attempt. Null until an adapter has been invoked. */
    val dispatch: DispatchInfo? = null,
    /** What memory just did with the latest message, written by the app itself (not by the AI). */
    val memoryNotice: String? = null,
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
    private val trace: TraceSink = NoTrace,
    private val memoryObserver: MemoryObserver = NoObserver,
) {
    private val gate = SendGate()

    /**
     * Makes "take the send gate and start a run" and "end a run: free the gate and say so" each one indivisible step.
     * Without it a send could land between the two halves of the cleanup: refused as BUSY while the screen already
     * said the answer was finished, or (if the halves were simply swapped) the old run's cleanup could wipe out the
     * "generating" state of a run that had just started.
     */
    private val runLock = Any()
    private var job: Job? = null
    private var failoverCancel: CompletableDeferred<Unit>? = null
    private var stoppedDuringFailover = false
    private var failingProvider: ProviderId? = null
    private var recoveryDeclinedFor: ProviderId? = null
    private var recoveryTimer: Job? = null

    /**
     * A preference change the user just asked for that is still being saved. A message sent in that moment waits for it
     * ([loop] joins it first), so it always goes to the provider the user just chose, never to the previous one.
     */
    @Volatile private var pendingPreference: Job? = null
    private val simulateNext = AtomicBoolean(false)
    private val visible = MutableStateFlow(true)

    /** The honest memory result for the message being answered; kept across retries and failover of that one message. */
    @Volatile private var memoryNote: String = ""

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
        synchronized(runLock) {
            if (!gate.tryAcquire()) {
                _state.update { it.copy(rejection = REJECTION) }
                return SendResult.BUSY
            }
            begin()
            launchGeneration(userText = clean)
        }
        return SendResult.ACCEPTED
    }

    /** Asks again for an answer to the last user message, without adding it a second time. */
    fun retry(): SendResult {
        if (!_state.value.conversation.awaitingReply) return SendResult.EMPTY
        synchronized(runLock) {
            if (!gate.tryAcquire()) {
                _state.update { it.copy(rejection = REJECTION) }
                return SendResult.BUSY
            }
            begin()
            launchGeneration(userText = null)
        }
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

    /** Switch back to the provider that was preferred before the automatic switch, and make it the preferred one again. */
    fun acceptRecovery() {
        val back = _state.value.recoveryOffer ?: _state.value.preferredProvider
        pendingPreference = scope.launch {
            if (back != null) {
                if (!saveAsPreferred(back)) { reportPreferenceNotSaved(back); return@launch }
                quotaClear(back) // only once the switch back really happened
            }
            endFailoverEpisode()
        }
    }

    /**
     * The user picked [provider] as the one that answers chats. That ends any automatic-switch episode: the saved
     * preference and what the chat screen says always agree, and the next message goes to [provider].
     */
    fun choosePreferred(provider: ProviderId) {
        pendingPreference = scope.launch {
            if (!saveAsPreferred(provider)) { reportPreferenceNotSaved(provider); return@launch }
            endFailoverEpisode()
        }
    }

    private suspend fun saveAsPreferred(provider: ProviderId): Boolean =
        try {
            source.setPreferred(provider)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // the preference could not be saved; leave everything as it was rather than show a state that isn't true
        }

    /** The save failed, so nothing changed: the saved preference and the state still agree. Say so instead of staying quiet. */
    private fun reportPreferenceNotSaved(wanted: ProviderId) {
        _state.update {
            it.copy(
                preferenceError = "Couldn't save ${source.labelOf(wanted)} as your preferred provider, so nothing was changed. " +
                    "Chats still use the provider that was saved before. Try again.",
            )
        }
    }

    // The quota ledger still knows only the built-in providers. A provider that is not one has no ledger entry: nothing
    // is read, written or cleared for it, and it is never mapped to some built-in provider's entry.
    private fun quotaRecord(id: ProviderId): QuotaRecord? = id.toAiProviderOrNull()?.let { ledger.record(it) }
    private fun quotaExhausted(id: ProviderId): Boolean = id.toAiProviderOrNull()?.let { ledger.isExhausted(it) } ?: false
    private fun quotaMark(id: ProviderId, error: LlmError.QuotaExhausted) { id.toAiProviderOrNull()?.let { ledger.markExhausted(it, error) } }
    private fun quotaClear(id: ProviderId) { id.toAiProviderOrNull()?.let { ledger.clear(it) } }

    private fun endFailoverEpisode() {
        recoveryTimer?.cancel()
        recoveryDeclinedFor = null
        _state.update { it.copy(temporaryProvider = null, preferredProvider = null, recoveryOffer = null, preferenceError = null) }
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
            if (quotaExhausted(preferred) || recoveryDeclinedFor == preferred) return@launch
            _state.update { it.copy(recoveryOffer = preferred) }
        }
    }

    private fun scheduleRecoveryCheck(provider: ProviderId) {
        recoveryTimer?.cancel()
        val until = quotaRecord(provider)?.exhaustedUntil ?: return // unknown reset: nothing to wait for
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
            // Free the gate first and only then say "not generating", all inside the lock: whoever sees the screen go
            // idle can send straight away (it is accepted), and no send can slip in between the two steps.
            synchronized(runLock) {
                gate.release()
                _state.update { it.copy(generating = false, stopping = false, streaming = null, note = null, failover = null) }
            }
            checkRecovery() // after the lock is free, so the question never appears mid-answer
        }
    }

    private suspend fun runGeneration(userText: String?) {
        try {
            if (userText != null) {
                val recent = _state.value.conversation.messages.takeLast(6).map { ChatTurn(it.role == ChatRole.USER, it.text) }
                val withUser = withContext(io) { engine.addUserMessage(_state.value.conversation, userText) }
                _state.update { it.copy(conversation = withUser) }
                // Remembering happens BEFORE the reply is requested, so the reply is based on what really happened:
                // the AI is given the true result and can only claim a save that is in the database. It is bounded
                // by a timeout and can never fail the chat.
                _state.update { it.copy(memoryNotice = null) }
                val outcome = withContext(io) {
                    try {
                        withTimeoutOrNull(MEMORY_TIMEOUT_MS) { memoryObserver.onUserMessage(userText, recent) }
                            ?: MemoryOutcome(MemoryStatusNote.UNKNOWN, "Memory: did not finish. Check the Memory screen.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MemoryOutcome(MemoryStatusNote.UNKNOWN, "Memory: did not finish. Check the Memory screen.") // never logged
                    }
                }
                memoryNote = outcome.note
                _state.update { it.copy(memoryNotice = outcome.notice) }
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
                        "Switch cancelled. I stayed with ${from?.let { source.labelOf(it) } ?: "the current provider"}, so I can't answer " +
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
        pendingPreference?.join()
        // The saved preference is the single truth about which provider answers. If it no longer matches the provider an
        // earlier automatic switch put in use (the user chose another one since), that switch episode is over.
        val temp = _state.value.temporaryProvider
        if (temp != null && source.snapshot().preferred != temp) endFailoverEpisode()
        var provider: ProviderId? = null
        val tried = LinkedHashSet<ProviderId>()
        var waitedRate = false
        var waitedNet = false
        var requestedFirst: ProviderId? = null
        var switchedFrom: Triple<ProviderId, String, String?>? = null // provider, model, classification

        while (true) {
            val ready = when (val r = source.resolve(provider)) {
                is Resolved.Stop -> {
                    _state.update { it.copy(problem = r.problem) }
                    return
                }
                is Resolved.Ready -> r
            }
            val current = ready.target.provider
            if (requestedFirst == null) requestedFirst = current
            tried += current
            val snap = source.snapshot()

            // If we already know this provider is used up, say so now instead of spending a request to find out.
            val known = quotaRecord(current)
            val knownExhausted = snap.failoverEnabled && known != null && quotaExhausted(current)
            val failure: GenResult.Failure = if (knownExhausted) {
                GenResult.Failure(
                    _state.value.conversation,
                    LlmError.QuotaExhausted(known!!.exhaustedUntil?.let { Instant.ofEpochMilli(it) }, known.quotaId, known.source),
                    null,
                )
            } else {
                val simulated = simulateNext.getAndSet(false)
                val llm: LlmProvider = if (simulated) {
                    _state.update { it.copy(simulationArmed = false) }
                    SimulatedQuotaProvider(Instant.ofEpochMilli(clock()))
                } else {
                    ready.llm
                }
                when (val result = generate(ready, llm, requestedFirst ?: current, simulated, switchedFrom)) {
                    is GenResult.Success -> {
                        quotaClear(current) // it just worked, so whatever we remembered is out of date
                        _state.update { it.copy(conversation = result.conversation) }
                        return
                    }
                    is GenResult.Failure -> result
                }
            }

            _state.update { it.copy(conversation = failure.conversation) }
            val error = failure.error
            if (error is LlmError.QuotaExhausted && !knownExhausted) {
                quotaMark(current, error)
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
                    switchedFrom = Triple(current, ready.target.model, error?.javaClass?.simpleName)
                    trace.record(
                        TraceEvent(
                            originalProvider = current, originalLabel = ready.target.providerLabel, originalModel = ready.target.model,
                            classification = error?.javaClass?.simpleName,
                            fallbackProvider = d.to.provider, fallbackLabel = source.labelOf(d.to.provider), fallbackModel = d.to.model,
                            adapter = null, stage = "decision: switch after countdown",
                            streamStarted = false, responseReceived = false,
                            dispatchedAtMillis = clock(), result = "pending",
                        ),
                    )
                    val note = resetNote(current)
                    if (!countdown(current, d.to, note)) {
                        _state.update { it.copy(problem = cancelledProblem(current)) }
                        return
                    }
                    // The switch only happens if it can be saved: what the app says and what is used must agree. If it
                    // can't be saved, nothing is sent through the other provider and the user is told.
                    val original = _state.value.preferredProvider ?: snap.preferred
                    if (!saveAsPreferred(d.to.provider)) {
                        _state.update { it.copy(problem = switchNotSavedProblem(current, d.to.provider)) }
                        return
                    }
                    provider = d.to.provider
                    _state.update {
                        it.copy(
                            temporaryProvider = d.to.provider,
                            preferredProvider = original,
                            recoveryOffer = null,
                            preferenceError = null, // a stale "couldn't save" message must not survive a successful save
                        )
                    }
                    recoveryDeclinedFor = null
                    scheduleRecoveryCheck(original)
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
                            ErrorMessages.of(error!!) + " Automatic provider failover is off, so I stayed with ${source.labelOf(current)}.",
                            FailureFormatter.details(failure.exception?.context),
                            needsSetup = true,
                        ))
                    }
                    return
                }
                is Decision.NoAlternative -> {
                    val why = d.reasons.joinToString("; ") { (p, r) -> "${source.labelOf(p)}: $r" }
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

    private fun resetNote(provider: ProviderId): String {
        val r = quotaRecord(provider) ?: return "reset time unknown"
        val until = r.exhaustedUntil ?: return "reset time unknown"
        val when_ = ResetFormat.describe(Instant.ofEpochMilli(until), Instant.ofEpochMilli(clock()), zone)
        return when (r.source) {
            ResetSource.REPORTED -> "resets $when_ (reported by the provider)"
            ResetSource.DOCUMENTED -> "resets $when_ (per its documentation, not reported in the error)"
            ResetSource.SIMULATED -> "simulated test"
            ResetSource.UNKNOWN -> "reset time unknown"
        }
    }

    private fun switchNotSavedProblem(from: ProviderId, to: ProviderId) = ChatProblem(
        "${source.labelOf(from)} has used its quota and I was about to switch to ${source.labelOf(to)}, but I couldn't save that change, so " +
            "I did not switch and nothing was sent to ${source.labelOf(to)}. Your message is saved. Retry to try again, or choose " +
            "another provider yourself under AI providers.",
        null,
        needsSetup = false,
    )

    private fun cancelledProblem(from: ProviderId) = ChatProblem(
        "Switch cancelled. I stayed with ${source.labelOf(from)}, which has used its quota, so I can't answer until you pick " +
            "another provider or its quota resets.",
        null,
        needsSetup = true,
    )

    /** The five-second announcement. Returns false if the user cancelled. Pauses while the app is off screen. */
    private suspend fun countdown(from: ProviderId, to: Candidate, resetNote: String): Boolean {
        val cancel = CompletableDeferred<Unit>()
        failoverCancel = cancel
        failingProvider = from
        try {
            for (s in countdownSeconds downTo 1) {
                _state.update { it.copy(failover = FailoverOffer(from, source.labelOf(from), to.provider, source.labelOf(to.provider), to.model, s, resetNote)) }
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

    private suspend fun generate(
        r: Resolved.Ready,
        llmIn: LlmProvider,
        requested: ProviderId,
        simulated: Boolean,
        switchedFrom: Triple<ProviderId, String, String?>?,
    ): GenResult {
        val label = r.target.providerLabel
        var dispatchedAt = 0L
        var streamStarted = false
        val llm = TracingLlm(
            llmIn,
            onDispatched = {
                dispatchedAt = clock()
                setDispatch(requested, r.target, llmIn, DispatchStage.DISPATCHED, simulated)
            },
            onFirstText = {
                streamStarted = true
                setDispatch(requested, r.target, llmIn, DispatchStage.STREAMING, simulated)
            },
        )
        val events = object : CallEvents {
            override fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) {
                _state.update { it.copy(note = "$label is overloaded. Retrying $retryNumber of $maxRetries in ${(waitMs + 500) / 1000} s...") }
            }
        }
        val result = withContext(io) {
            engine.generate(
                conv = _state.value.conversation,
                target = r.target,
                llm = llm,
                options = CallOptions(maxRetries, events),
                onPartial = { text -> _state.update { it.copy(streaming = text, note = null) } },
                memoryNote = memoryNote,
            )
        }
        val ok = result is GenResult.Success
        if (dispatchedAt != 0L) {
            setDispatch(requested, r.target, llmIn, if (ok) DispatchStage.FINISHED_OK else DispatchStage.FINISHED_FAILED, simulated)
            if (switchedFrom != null || simulated) {
                val err = (result as? GenResult.Failure)?.error
                trace.record(
                    TraceEvent(
                        originalProvider = switchedFrom?.first, originalLabel = switchedFrom?.first?.let { source.labelOf(it) },
                        originalModel = switchedFrom?.second,
                        classification = switchedFrom?.third,
                        fallbackProvider = r.target.provider, fallbackLabel = r.target.providerLabel, fallbackModel = r.target.model,
                        adapter = llm.adapterName, stage = "attempt finished",
                        streamStarted = streamStarted, responseReceived = ok,
                        dispatchedAtMillis = dispatchedAt,
                        result = if (ok) "success" else "failed: ${err?.javaClass?.simpleName ?: "unknown"}",
                    ),
                )
            }
        }
        return result
    }

    private fun setDispatch(requested: ProviderId, t: ChatTarget, llm: LlmProvider, stage: DispatchStage, simulated: Boolean) {
        _state.update { it.copy(dispatch = DispatchInfo(requested, source.labelOf(requested), t.provider, llm.javaClass.simpleName, t.model, stage, simulated)) }
    }

    companion object {
        const val MEMORY_TIMEOUT_MS = 3000L
        const val REJECTION = "Still answering. Your message was NOT sent and was not queued. Wait for the reply or tap Stop, then send it again."
    }
}
