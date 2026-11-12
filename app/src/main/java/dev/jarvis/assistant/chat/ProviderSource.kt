package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/** Everything the chat needs to know about the user's providers right now. */
data class ProviderSnapshot(
    val preferred: AiProvider,
    val failoverEnabled: Boolean,
    /** Every provider, in the user's priority order. */
    val candidates: List<Candidate>,
)

/** Supplies ready-to-use targets and the current provider picture. The Android version reads settings and keys. */
interface ProviderSource {
    /** Checks consent and key for [provider] (null means the preferred one) and builds the request target. */
    suspend fun resolve(provider: AiProvider?): Resolved

    suspend fun snapshot(): ProviderSnapshot
}

/** Waits until the phone has a network again. */
interface NetworkWatcher {
    suspend fun awaitConnected()
}

object NoNetworkWait : NetworkWatcher {
    override suspend fun awaitConnected() {}
}

/**
 * Stands in for a provider that says "quota exhausted", so the failover flow can be tested on the phone without
 * using any real quota. Nothing is sent anywhere.
 */
class SimulatedQuotaProvider(private val now: Instant = Instant.now()) : LlmProvider {
    override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flow {
        throw LlmException(LlmError.QuotaExhausted(now.plusSeconds(SIMULATED_MINUTES * 60), SIMULATED_ID, ResetSource.SIMULATED))
    }

    companion object {
        const val SIMULATED_ID = "simulated"
        const val SIMULATED_MINUTES = 3L
    }
}
