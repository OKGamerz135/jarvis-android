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
        if (record != null) {
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
        return "Ready. No quota problem has been reported."
    }
}
