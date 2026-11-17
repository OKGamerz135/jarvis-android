package dev.jarvis.assistant.providers

import dev.jarvis.assistant.ai.ResetFormat
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.settings.AiProvider
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Plain-language status for the providers list. Says where each fact comes from; never invents a reset time. */
object ProviderStatus {
    fun describe(
        provider: AiProvider,
        acknowledged: Boolean,
        hasKey: Boolean,
        record: QuotaRecord?,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (!hasKey) return "Needs an API key"
        if (!acknowledged) return "Needs your consent (tick \"I understand\")"
        quotaLine(provider, record, now, zone, locale)?.let { return it }
        // Says what is known (key and consent present). It is NOT a claim that a connection test passed.
        return "Configured. No quota problem has been reported."
    }

    /** What the quota ledger says about [provider], or null when nothing has been recorded. Never invents a reset time. */
    fun quotaLine(
        provider: AiProvider,
        record: QuotaRecord?,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (record == null) return null
        val until = record.exhaustedUntil?.let { Instant.ofEpochMilli(it) }
        val active = until == null || until.isAfter(now)
        if (!active) return "Quota should be available again (the reset time has passed)"
        val reset = ResetFormat.describe(until, now, zone, locale)
        return "Quota used up. " + when (record.source) {
            ResetSource.REPORTED -> "Resets $reset (reported by ${provider.label})."
            ResetSource.DOCUMENTED -> "Resets $reset (from ${provider.label}'s documentation; the error itself didn't say)."
            ResetSource.SIMULATED -> "Simulated test, ends $reset."
            ResetSource.UNKNOWN -> "Reset time unknown."
        }
    }

    /**
     * A warning for the provider that is selected for chat when it can't be used yet, or null when it is fine.
     * Selecting such a provider is allowed; chatting with it is refused (nothing is sent, nothing falls back silently).
     */
    fun chatWarning(provider: AiProvider, acknowledged: Boolean, hasKey: Boolean): String? = when {
        !hasKey ->
            "${provider.label} is not configured: it has no usable API key. While it is selected, chat messages are NOT sent " +
                "anywhere, and JARVIS will not switch to another provider by itself. Add its key, or choose another provider."
        !acknowledged ->
            "${provider.label} needs your consent first (tick \"I understand\" in its setup). Until then chat messages are " +
                "NOT sent anywhere, and JARVIS will not switch to another provider by itself."
        else -> null
    }
}
