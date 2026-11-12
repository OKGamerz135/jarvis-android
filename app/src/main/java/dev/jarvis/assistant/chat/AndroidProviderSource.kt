package dev.jarvis.assistant.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.ai.Providers
import dev.jarvis.assistant.providers.QuotaLedger
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.SecureKeyStore
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Reads the real settings and keys. Applies the same consent and key rules as the test tools. */
class AndroidProviderSource(
    private val settingsRepo: SettingsRepository,
    private val keys: SecureKeyStore,
    private val ledger: QuotaLedger,
) : ProviderSource {

    override suspend fun resolve(provider: AiProvider?): Resolved {
        val settings = settingsRepo.settings.first()
        val p = provider ?: settings.provider
        if (p !in settings.acknowledged) {
            return Resolved.Stop(ChatProblem(ErrorMessages.of(LlmError.NotAcknowledged), null, needsSetup = true))
        }
        val key = withContext(Dispatchers.IO) { keys.read(p.keyName) }
        if (key.isNullOrBlank()) {
            return Resolved.Stop(ChatProblem(ErrorMessages.of(LlmError.NoKey), null, needsSetup = true))
        }
        val target = ChatTarget(
            provider = p,
            historyBudgetChars = ChatLimits.historyBudgetChars(p),
            model = settings.modelOf(p),
            apiKey = key,
            systemPrompt = PromptBuilder.build(settings),
        )
        return Resolved.Ready(target, Providers.forProvider(p))
    }

    override suspend fun snapshot(): ProviderSnapshot {
        val settings = settingsRepo.settings.first()
        val candidates = settings.orderedProviders().map { p ->
            Candidate(
                provider = p,
                model = settings.modelOf(p),
                enabled = p !in settings.disabledProviders,
                hasKey = keys.has(p.keyName),
                acknowledged = p in settings.acknowledged,
                exhausted = ledger.isExhausted(p),
            )
        }
        return ProviderSnapshot(settings.provider, settings.failoverEnabled, candidates)
    }
}

/** Waits for the phone to report a usable network. Needs only the ACCESS_NETWORK_STATE permission. */
class AndroidNetworkWatcher(context: Context) : NetworkWatcher {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun awaitConnected() {
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                callback = cb
                cm.registerDefaultNetworkCallback(cb)
            }
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }
}
