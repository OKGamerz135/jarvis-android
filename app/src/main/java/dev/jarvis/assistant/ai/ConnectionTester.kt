package dev.jarvis.assistant.ai

import dev.jarvis.assistant.chat.ProviderSource
import dev.jarvis.assistant.chat.Resolved
import dev.jarvis.assistant.diag.DiagnosticEntry
import dev.jarvis.assistant.diag.UsageGuard
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.toAiProviderOrNull
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/** The generation test: sends one message through the provider being configured. Uses quota. */
sealed interface TestState {
    data object Idle : TestState
    data class Running(val text: String, val note: String?) : TestState
    data class Done(val text: String, val millis: Long, val provider: String, val model: String, val attempts: Int) : TestState
    /** [offerAlternative] is true only after an "overloaded" error survived every retry; the UI then shows a manual button. */
    data class Failed(val message: String, val details: String?, val offerAlternative: Boolean = false) : TestState
}

/** The availability check: asks whether the key can see the model. Generates nothing, uses no generation quota. */
sealed interface AvailabilityState {
    data object Idle : AvailabilityState
    data object Running : AvailabilityState
    data class Ok(val provider: String, val model: String, val displayName: String?, val canGenerate: Boolean) : AvailabilityState
    data class Failed(val message: String, val details: String?) : AvailabilityState
}

sealed interface DiagState {
    data object Idle : DiagState
    data class Running(val step: String) : DiagState
    data class Done(val lines: List<DiagLine>, val verdict: String) : DiagState
    data class Failed(val message: String) : DiagState
}

/** Everything the three checks currently show for ONE provider. */
data class ProviderTestState(
    val availability: AvailabilityState = AvailabilityState.Idle,
    val generation: TestState = TestState.Idle,
    val diagnostics: DiagState = DiagState.Idle,
)

enum class TestKind { AVAILABILITY, GENERATION, DIAGNOSTICS }

/** The one check that is running right now, and for which provider. */
data class ActiveTest(val provider: ProviderId, val kind: TestKind)

/**
 * Runs the availability check, the generation test and the diagnostics for the provider it is TOLD to test.
 *
 * - Every operation takes the provider explicitly. The provider chosen for normal chat is never consulted, so testing
 *   Groq can never touch Cloudflare's adapter, key, model or consent.
 * - Only one check runs at a time (they use free quota and share a cooldown). A second request is refused, not queued.
 * - Results are kept per provider. [invalidate] (called when that provider's key or model changes) drops them and stops
 *   a running check, so a result can never be shown for a configuration it was not obtained with.
 */
class ConnectionTester(
    private val scope: CoroutineScope,
    /** Resolves consent, key and model for the requested provider. Same rules the chat uses. */
    private val source: ProviderSource,
    /** The name shown for a provider (from its registered descriptor). Used in messages, never to pick a provider. */
    private val labelOf: (ProviderId) -> String,
    private val diagnosticsFor: (AiProvider) -> ProviderDiagnostics,
    private val recentEntries: () -> List<DiagnosticEntry>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxRetries: Int = 2,
) {
    private val lock = Any()
    private var activeJob: Job? = null
    private val epochs = HashMap<ProviderId, Int>()
    private var lastModels: Map<ProviderId, String>? = null

    private val _states = MutableStateFlow<Map<ProviderId, ProviderTestState>>(emptyMap())
    val states: StateFlow<Map<ProviderId, ProviderTestState>> = _states.asStateFlow()

    private val _active = MutableStateFlow<ActiveTest?>(null)
    val active: StateFlow<ActiveTest?> = _active.asStateFlow()

    fun stateOf(provider: ProviderId): ProviderTestState = _states.value[provider] ?: ProviderTestState()

    // ------------------------------------------------------------------ plumbing

    private fun epochOf(provider: ProviderId): Int = epochs[provider] ?: 0

    /** Writes a result only if the provider's configuration has not changed since this run began. */
    private fun publish(provider: ProviderId, epoch: Int, change: (ProviderTestState) -> ProviderTestState) {
        synchronized(lock) {
            if (epochOf(provider) != epoch) return
            _states.value = _states.value + (provider to change(stateOf(provider)))
        }
    }

    /** Returns false (and does nothing) when another check is already running. */
    private fun start(provider: ProviderId, kind: TestKind, body: suspend (epoch: Int) -> Unit): Boolean {
        val epoch: Int
        val job: Job
        synchronized(lock) {
            if (activeJob != null) return false
            epoch = epochOf(provider)
            job = scope.launch(start = CoroutineStart.LAZY) { body(epoch) }
            activeJob = job
            _active.value = ActiveTest(provider, kind)
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeJob === job) {
                    activeJob = null
                    _active.value = null
                }
            }
        }
        job.start()
        return true
    }

    private class Go(val target: dev.jarvis.assistant.chat.ChatTarget, val llm: LlmProvider, val builtIn: AiProvider)

    /**
     * Consent and key checks for exactly [provider]. Returns the reason text instead of a target when not usable.
     * Only built-in providers can be resolved today ([ProviderSource] still speaks [AiProvider]); an id that is not one
     * is refused here, before anything is resolved or sent, and is never mapped to some other provider.
     */
    private suspend fun preflight(provider: ProviderId): Pair<Go?, String?> {
        val builtIn = provider.toAiProviderOrNull()
            ?: return null to "${labelOf(provider)} can't be tested here yet. Nothing was sent."
        return when (val r = source.resolve(provider)) {
            is Resolved.Stop -> null to r.problem.message
            is Resolved.Ready ->
                if (r.target.provider != provider) {
                    // A resolver that answers for another provider would reintroduce the old bug. Refuse loudly.
                    null to "Internal error: asked for ${labelOf(provider)} but was given ${r.target.providerLabel}. Nothing was sent."
                } else {
                    Go(r.target, r.llm, builtIn) to null
                }
        }
    }

    private fun cooldownLeftMs(provider: ProviderId): Long =
        UsageGuard.cooldownRemainingMs(recentEntries(), labelOf(provider), clock())

    private fun cooldownMessage(ms: Long): String =
        "Wait ${(ms + 999) / 1000} more seconds. This pause between generation calls protects your free quota."

    // ------------------------------------------------------------------ configuration changes

    /** The key or the model of [provider] changed (or was removed): its results are stale. */
    fun invalidate(provider: ProviderId) {
        synchronized(lock) {
            epochs[provider] = epochOf(provider) + 1
            if (_states.value.containsKey(provider)) _states.value = _states.value - provider
            if (_active.value?.provider == provider) activeJob?.cancel()
        }
    }

    /** Feed this the effective model of every provider whenever settings change; changed models invalidate that provider. */
    fun onModelsObserved(models: Map<ProviderId, String>) {
        val before = synchronized(lock) { lastModels.also { lastModels = models } } ?: return
        for ((provider, model) in models) if (before[provider] != model) invalidate(provider)
    }

    // ------------------------------------------------------------------ availability check (no generation)

    fun checkAvailability(provider: ProviderId): Boolean = start(provider, TestKind.AVAILABILITY) { epoch ->
        publish(provider, epoch) { it.copy(availability = AvailabilityState.Running) }
        val result: AvailabilityState = try {
            val (go, why) = preflight(provider)
            if (go == null) {
                AvailabilityState.Failed(why ?: "Not ready.", null)
            } else {
                val info = diagnosticsFor(go.builtIn).checkModel(go.target.apiKey, go.target.model)
                AvailabilityState.Ok(labelOf(provider), go.target.model, info.displayName, info.canGenerate)
            }
        } catch (e: LlmException) {
            AvailabilityState.Failed(ErrorMessages.of(e.error), FailureFormatter.details(e.context, 1))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AvailabilityState.Failed("Unexpected error: ${e.javaClass.simpleName}", null)
        }
        publish(provider, epoch) { it.copy(availability = result) }
    }

    // ------------------------------------------------------------------ generation test (uses quota)

    fun runTest(provider: ProviderId, prompt: String): Boolean = start(provider, TestKind.GENERATION) { epoch ->
        publish(provider, epoch) { it.copy(generation = TestState.Running("", null)) }
        var retries = 0
        val result: TestState = try {
            val (go, why) = preflight(provider)
            if (go == null) {
                TestState.Failed(why ?: "Not ready.", null)
            } else {
                val wait = cooldownLeftMs(provider)
                if (wait > 0) {
                    TestState.Failed(cooldownMessage(wait), null)
                } else {
                    val request = ChatRequest(
                        model = go.target.model,
                        apiKey = go.target.apiKey,
                        systemPrompt = go.target.systemPrompt,
                        messages = listOf(ChatMessage(ChatRole.USER, prompt)),
                    )
                    val events = object : CallEvents {
                        override fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) {
                            retries = retryNumber
                            publish(provider, epoch) {
                                it.copy(
                                    generation = TestState.Running(
                                        "",
                                        "The provider is overloaded. Retrying $retryNumber of $maxRetries in ${(waitMs + 500) / 1000} s...",
                                    ),
                                )
                            }
                        }
                    }
                    val started = clock()
                    val reply = StringBuilder()
                    go.llm.stream(request, CallOptions(maxRetries, events)).collect { chunk ->
                        reply.append(chunk)
                        publish(provider, epoch) { it.copy(generation = TestState.Running(reply.toString(), null)) }
                    }
                    TestState.Done(reply.toString(), clock() - started, labelOf(provider), go.target.model, retries + 1)
                }
            }
        } catch (e: LlmException) {
            val tries = retries + 1
            val suffix = if (retries > 0) " I tried $tries times." else ""
            val offer = e.error is LlmError.Overloaded && retries >= maxRetries && provider == AiProvider.GEMINI.toId()
            TestState.Failed(ErrorMessages.of(e.error) + suffix, FailureFormatter.details(e.context, tries), offer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestState.Failed("Unexpected error: ${e.javaClass.simpleName}", null)
        }
        publish(provider, epoch) { it.copy(generation = result) }
    }

    // ------------------------------------------------------------------ diagnostics run (uses up to 2 generation calls)

    fun runDiagnostics(provider: ProviderId): Boolean = start(provider, TestKind.DIAGNOSTICS) { epoch ->
        fun step(text: String) = publish(provider, epoch) { it.copy(diagnostics = DiagState.Running(text)) }
        step("Preparing...")
        val result: DiagState = try {
            val (go, why) = preflight(provider)
            if (go == null) {
                DiagState.Failed(why ?: "Not ready.")
            } else {
                val tools = diagnosticsFor(go.builtIn)
                val target = go.target

                // Step 1: no generation, no generation quota.
                step("1 of 3: availability check (no generation)")
                val check = outcomeOf {
                    val info = tools.checkModel(target.apiKey, target.model)
                    if (!info.canGenerate) {
                        throw LlmException(LlmError.Other("The model exists but doesn't list text generation among its methods."))
                    }
                    "key accepted, model ${info.id} found, text generation supported"
                }

                // Steps 2 and 3 use generation quota, so they respect the cooldown and a hard limit of two calls.
                val wait = cooldownLeftMs(provider)
                val stream: Outcome
                var once: Outcome = Outcome.Skipped("not needed")
                if (check !is Outcome.Ok) {
                    stream = Outcome.Skipped("the availability check failed first")
                    once = Outcome.Skipped("the availability check failed first")
                } else if (wait > 0) {
                    stream = Outcome.Skipped("cooldown, ${(wait + 999) / 1000} s left")
                    once = Outcome.Skipped("cooldown")
                } else {
                    val tiny = ChatRequest(
                        model = target.model,
                        apiKey = target.apiKey,
                        systemPrompt = "You are a connection test. Reply with the single word: ok",
                        messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    )
                    step("2 of 3: streamed generation (1 call, no retries)")
                    stream = outcomeOf {
                        val text = StringBuilder()
                        go.llm.stream(tiny, CallOptions(maxRetries = 0)).collect { text.append(it) }
                        "got a reply of ${text.length} characters"
                    }
                    if (stream is Outcome.Fail && DiagnosticsVerdict.isServerSide(stream.error)) {
                        step("3 of 3: non-streamed comparison (1 call)")
                        once = outcomeOf { "got a reply of ${tools.generateOnce(tiny).length} characters" }
                    }
                }
                DiagState.Done(
                    DiagnosticsVerdict.lines(check, stream, once),
                    DiagnosticsVerdict.verdict(check, stream, once, labelOf(provider)),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagState.Failed("Unexpected error: ${e.javaClass.simpleName}")
        }
        publish(provider, epoch) { it.copy(diagnostics = result) }
    }

    private suspend fun outcomeOf(block: suspend () -> String): Outcome =
        try {
            Outcome.Ok(block())
        } catch (e: LlmException) {
            Outcome.Fail(e.error, e.context)
        }
}
