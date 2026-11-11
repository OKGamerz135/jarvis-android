package dev.jarvis.assistant.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.diag.UsageGuard
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings
import dev.jarvis.assistant.settings.SecureKeyStore
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The generation test: sends one message through the selected provider. Uses quota. */
sealed interface TestState {
    data object Idle : TestState
    data class Running(val text: String, val note: String?) : TestState
    data class Done(val text: String, val millis: Long, val provider: String, val model: String, val attempts: Int) : TestState
    data class Failed(val message: String, val details: String?) : TestState
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

class ConnectionTestViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val keys = SecureKeyStore(app)
    private var testJob: Job? = null
    private var availJob: Job? = null
    private var diagJob: Job? = null

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val state: StateFlow<TestState> = _test.asStateFlow()

    private val _avail = MutableStateFlow<AvailabilityState>(AvailabilityState.Idle)
    val availability: StateFlow<AvailabilityState> = _avail.asStateFlow()

    private val _diag = MutableStateFlow<DiagState>(DiagState.Idle)
    val diagnostics: StateFlow<DiagState> = _diag.asStateFlow()

    /** Generation HTTP attempts this phone made to Gemini in the last 24 hours, failed ones included. */
    val generationCalls24h: StateFlow<Int> = DiagnosticsLog.entries
        .map { UsageGuard.generationCalls(it, AiProvider.GEMINI.label, System.currentTimeMillis() - UsageGuard.DAY_MS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private class Ready(
        val settings: JarvisSettings,
        val provider: AiProvider,
        val llm: LlmProvider,
        val tools: ProviderDiagnostics?,
        val key: String,
        val model: String,
    )

    private sealed interface Preflight {
        class Go(val ready: Ready) : Preflight
        class Stop(val message: String) : Preflight
    }

    /** Consent and key checks that run before anything is sent. */
    private suspend fun preflight(): Preflight {
        val settings = repo.settings.first()
        val provider = settings.provider
        val llm = Providers.forProvider(provider)
            ?: return Preflight.Stop(ErrorMessages.of(LlmError.Unsupported("${provider.label} (arrives in Build 5)")))
        if (provider !in settings.acknowledged) return Preflight.Stop(ErrorMessages.of(LlmError.NotAcknowledged))
        val key = withContext(Dispatchers.IO) { keys.read(provider.keyName) }
        if (key.isNullOrBlank()) return Preflight.Stop(ErrorMessages.of(LlmError.NoKey))
        return Preflight.Go(Ready(settings, provider, llm, Providers.diagnosticsFor(provider), key, settings.activeModel()))
    }

    private fun cooldownLeftMs(provider: AiProvider): Long =
        UsageGuard.cooldownRemainingMs(DiagnosticsLog.entries.value, provider.label, System.currentTimeMillis())

    private fun cooldownMessage(ms: Long): String =
        "Wait ${(ms + 999) / 1000} more seconds. This pause between generation calls protects your free quota."

    // ------------------------------------------------------------------ availability check (no generation)

    fun checkAvailability() {
        availJob?.cancel()
        availJob = viewModelScope.launch {
            _avail.value = AvailabilityState.Running
            try {
                val ready = when (val p = preflight()) {
                    is Preflight.Stop -> {
                        _avail.value = AvailabilityState.Failed(p.message, null)
                        return@launch
                    }
                    is Preflight.Go -> p.ready
                }
                val tools = ready.tools
                if (tools == null) {
                    _avail.value = AvailabilityState.Failed("This provider has no availability check yet.", null)
                    return@launch
                }
                val info = tools.checkModel(ready.key, ready.model)
                _avail.value = AvailabilityState.Ok(ready.provider.label, ready.model, info.displayName, info.canGenerate)
            } catch (e: LlmException) {
                _avail.value = AvailabilityState.Failed(ErrorMessages.of(e.error), FailureFormatter.details(e.context, 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _avail.value = AvailabilityState.Failed("Unexpected error: ${e.javaClass.simpleName}", null)
            }
        }
    }

    // ------------------------------------------------------------------ generation test (uses quota)

    fun runTest(prompt: String) {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _test.value = TestState.Running("", null)
            var retries = 0
            try {
                val ready = when (val p = preflight()) {
                    is Preflight.Stop -> {
                        _test.value = TestState.Failed(p.message, null)
                        return@launch
                    }
                    is Preflight.Go -> p.ready
                }
                val wait = cooldownLeftMs(ready.provider)
                if (wait > 0) {
                    _test.value = TestState.Failed(cooldownMessage(wait), null)
                    return@launch
                }

                val request = ChatRequest(
                    model = ready.model,
                    apiKey = ready.key,
                    systemPrompt = PromptBuilder.build(ready.settings),
                    messages = listOf(ChatMessage(ChatRole.USER, prompt)),
                )
                val events = object : CallEvents {
                    override fun onRetry(retryNumber: Int, maxRetries: Int, waitMs: Long) {
                        retries = retryNumber
                        _test.value = TestState.Running(
                            "",
                            "The provider is overloaded. Retrying $retryNumber of $maxRetries in ${(waitMs + 500) / 1000} s...",
                        )
                    }
                }
                val started = System.currentTimeMillis()
                val reply = StringBuilder()
                ready.llm.stream(request, CallOptions(MAX_RETRIES, events)).collect { chunk ->
                    reply.append(chunk)
                    _test.value = TestState.Running(reply.toString(), null)
                }
                _test.value = TestState.Done(
                    reply.toString(), System.currentTimeMillis() - started, ready.provider.label, ready.model, retries + 1,
                )
            } catch (e: LlmException) {
                val tries = retries + 1
                val suffix = if (retries > 0) " I tried $tries times." else ""
                _test.value = TestState.Failed(ErrorMessages.of(e.error) + suffix, FailureFormatter.details(e.context, tries))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _test.value = TestState.Failed("Unexpected error: ${e.javaClass.simpleName}", null)
            }
        }
    }

    // ------------------------------------------------------------------ diagnostics run (uses up to 2 generation calls)

    fun runDiagnostics() {
        diagJob?.cancel()
        diagJob = viewModelScope.launch {
            _diag.value = DiagState.Running("Preparing...")
            try {
                val ready = when (val p = preflight()) {
                    is Preflight.Stop -> {
                        _diag.value = DiagState.Failed(p.message)
                        return@launch
                    }
                    is Preflight.Go -> p.ready
                }
                val tools = ready.tools
                if (tools == null) {
                    _diag.value = DiagState.Failed("This provider has no diagnostics yet.")
                    return@launch
                }

                // Step 1: no generation, no generation quota.
                _diag.value = DiagState.Running("1 of 3: availability check (no generation)")
                val check = outcomeOf {
                    val info = tools.checkModel(ready.key, ready.model)
                    if (!info.canGenerate) {
                        throw LlmException(LlmError.Other("The model exists but doesn't list text generation among its methods."))
                    }
                    "key accepted, model ${info.id} found, text generation supported"
                }

                // Steps 2 and 3 use generation quota, so they respect the cooldown and a hard limit of two calls.
                val wait = cooldownLeftMs(ready.provider)
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
                        model = ready.model,
                        apiKey = ready.key,
                        systemPrompt = "You are a connection test. Reply with the single word: ok",
                        messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    )
                    _diag.value = DiagState.Running("2 of 3: streamed generation (1 call, no retries)")
                    stream = outcomeOf {
                        val text = StringBuilder()
                        ready.llm.stream(tiny, CallOptions(maxRetries = 0)).collect { text.append(it) }
                        "got a reply of ${text.length} characters"
                    }
                    if (stream is Outcome.Fail && DiagnosticsVerdict.isServerSide(stream.error)) {
                        _diag.value = DiagState.Running("3 of 3: non-streamed comparison (1 call)")
                        once = outcomeOf { "got a reply of ${tools.generateOnce(tiny).length} characters" }
                    }
                }
                _diag.value = DiagState.Done(
                    DiagnosticsVerdict.lines(check, stream, once),
                    DiagnosticsVerdict.verdict(check, stream, once),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diag.value = DiagState.Failed("Unexpected error: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun outcomeOf(block: suspend () -> String): Outcome =
        try {
            Outcome.Ok(block())
        } catch (e: LlmException) {
            Outcome.Fail(e.error, e.context)
        }

    private companion object {
        /** Two retries at most, and only for "overloaded". See [RetryPolicy]. */
        const val MAX_RETRIES = 2
    }
}
