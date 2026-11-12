package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.settings.AiProvider

/** One provider as a possible failover target. */
data class Candidate(
    val provider: AiProvider,
    val model: String,
    val enabled: Boolean,
    val hasKey: Boolean,
    val acknowledged: Boolean,
    val exhausted: Boolean,
) {
    /** Why this provider can't be used, or null when it can. */
    fun whyNot(): String? = when {
        !enabled -> "turned off for failover"
        !hasKey -> "no API key saved"
        !acknowledged -> "you haven't ticked \"I understand\""
        exhausted -> "its quota is used up too"
        else -> null
    }

    val eligible: Boolean get() = whyNot() == null
}

data class FailoverContext(
    val current: AiProvider,
    val failoverEnabled: Boolean,
    /** All providers in the user's priority order. */
    val candidates: List<Candidate>,
    /** True once any reply text had already arrived. Then nothing is ever switched or retried. */
    val partialStarted: Boolean,
    val alreadyWaitedForRateLimit: Boolean,
    val alreadyWaitedForNetwork: Boolean,
    /** Providers already used for this message, so we never ping-pong. */
    val alreadyTried: Set<AiProvider>,
)

sealed interface Decision {
    data class Switch(val to: Candidate) : Decision
    data class WaitRateLimit(val seconds: Long) : Decision
    data object WaitForNetwork : Decision

    /** Quota is used up but the user turned failover off. */
    data object FailoverOff : Decision

    /** Quota is used up and nothing else is usable. [reasons] explain each other provider. */
    data class NoAlternative(val reasons: List<Pair<AiProvider, String>>) : Decision

    data object ShowError : Decision
}

/**
 * Pure rules for what to do after a failed request. Only a confirmed, genuine quota exhaustion ever switches provider.
 * Everything else either waits briefly (per-minute limits, offline) or just shows the error.
 */
object FailoverPolicy {
    /** Longest provider-supplied "try again in" we will wait out automatically. */
    const val MAX_AUTO_WAIT_SECONDS = 60L

    fun decide(error: LlmError?, ctx: FailoverContext): Decision {
        if (error == null || ctx.partialStarted) return Decision.ShowError
        return when (error) {
            is LlmError.QuotaExhausted -> onQuota(ctx)
            is LlmError.RateLimited -> {
                val s = error.retryAfterSeconds
                if (s != null && s <= MAX_AUTO_WAIT_SECONDS && !ctx.alreadyWaitedForRateLimit) {
                    Decision.WaitRateLimit(s.coerceAtLeast(1))
                } else {
                    Decision.ShowError
                }
            }
            LlmError.Offline -> if (ctx.alreadyWaitedForNetwork) Decision.ShowError else Decision.WaitForNetwork
            else -> Decision.ShowError
        }
    }

    private fun onQuota(ctx: FailoverContext): Decision {
        if (!ctx.failoverEnabled) return Decision.FailoverOff
        val others = ctx.candidates.filter { it.provider != ctx.current }
        val target = others.firstOrNull { it.eligible && it.provider !in ctx.alreadyTried }
        if (target != null) return Decision.Switch(target)
        val reasons = others.map { c ->
            c.provider to (c.whyNot() ?: "already tried for this message")
        }
        return Decision.NoAlternative(reasons)
    }
}
