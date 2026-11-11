package dev.jarvis.assistant.ai

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Plain-language descriptions of "when does this come back", shown in the user's own time zone. */
object ResetFormat {
    fun describe(resetAt: Instant?, now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        if (resetAt == null) return "at a time I can't tell"
        val minutes = Duration.between(now, resetAt).toMinutes()
        return when {
            minutes < 1 -> "in under a minute"
            minutes < 90 -> "in about $minutes minutes"
            else -> {
                val local = resetAt.atZone(zone)
                val today = now.atZone(zone).toLocalDate()
                val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(local)
                val day = when (ChronoUnit.DAYS.between(today, local.toLocalDate())) {
                    0L -> "today"
                    1L -> "tomorrow"
                    else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(local)
                }
                "around $time $day"
            }
        }
    }
}

/** Neutral wording for errors. Personality-flavoured versions come later, once chat exists. */
object ErrorMessages {
    fun of(error: LlmError, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = when (error) {
        LlmError.NoKey -> "No API key is saved for this provider yet."
        LlmError.NotAcknowledged -> "Tick \"I understand\" for this provider first. It tells you where your messages go."
        LlmError.Offline -> "Can't reach the provider. Check your internet connection."
        LlmError.InvalidKey -> "The provider rejected your API key. Check it in AI provider settings."
        is LlmError.ModelNotFound ->
            "The provider says the model \"${error.model}\" doesn't exist for your key. Model names change, so edit it in AI provider settings."
        is LlmError.RateLimited ->
            "Too many requests for a moment. Try again in ${error.retryAfterSeconds?.let { "$it seconds" } ?: "a few seconds"}."
        is LlmError.QuotaExhausted ->
            "The free quota for this provider is used up. It should reset ${ResetFormat.describe(error.resetAt, now, zone)}."
        is LlmError.Unclear429 ->
            "The provider says you've hit a limit but not which one. Wait a little and try again."
        is LlmError.Overloaded ->
            "The provider says its servers are overloaded right now. That's temporary and not about your quota or your setup."
        is LlmError.InternalError ->
            "The provider had an internal error. If it keeps happening, the request may be too long for it."
        is LlmError.Timeout ->
            "The provider didn't finish in time. A shorter message may help."
        is LlmError.BillingRequired ->
            "The provider says the free tier isn't available for this project or region and wants billing enabled. " +
                "JARVIS will never enable billing for you. Use another provider or a local model instead."
        is LlmError.PermissionDenied ->
            "The provider denied access for this key or project. Check the key and project in the provider's console."
        is LlmError.InvalidRequest ->
            "The provider rejected the request as malformed. That points at a JARVIS bug, so send me the diagnostics log."
        is LlmError.NotFromApi ->
            "HTTP ${error.httpCode} came back, but not in the provider's own error format, so something else on the " +
                "network may be answering. Try another connection (mobile data instead of Wi-Fi, or turn off any VPN)."
        is LlmError.ServerTrouble ->
            "The provider is having trouble (HTTP ${error.httpCode}). This isn't your quota; try again in a minute."
        is LlmError.Blocked -> "The provider declined to answer (${error.reason})."
        is LlmError.Unsupported -> "${error.what} isn't supported yet."
        is LlmError.Other -> error.message
    }
}

/** Turns a [FailureContext] into the exact "what was called and what came back" block shown on every failure. */
object FailureFormatter {
    fun details(context: FailureContext?, attempts: Int = 1): String? {
        if (context == null) return null
        return buildString {
            appendLine("Provider: ${context.provider}")
            appendLine("Model: ${context.model}")
            appendLine("Endpoint: ${context.method} ${context.endpoint}")
            appendLine("HTTP status: ${context.httpStatus ?: "none (no response)"}")
            if (context.apiStatus != null) appendLine("Provider status: ${context.apiStatus}")
            if (!context.apiMessage.isNullOrBlank()) appendLine("Provider says: ${context.apiMessage}")
            append("Attempts: $attempts")
        }
    }

    /** Short form for the diagnostics result lines. */
    fun oneLine(context: FailureContext?): String {
        if (context == null) return ""
        val parts = mutableListOf<String>()
        parts += "HTTP ${context.httpStatus ?: "none"}"
        if (context.apiStatus != null) parts += context.apiStatus
        return parts.joinToString(", ")
    }
}
