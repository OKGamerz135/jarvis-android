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
            "The model \"${error.model}\" wasn't found. Model names change, so edit it in AI provider settings."
        is LlmError.RateLimited ->
            "Too many requests for a moment. Try again in ${error.retryAfterSeconds?.let { "$it seconds" } ?: "a few seconds"}."
        is LlmError.QuotaExhausted ->
            "The free quota for this provider is used up. It should reset ${ResetFormat.describe(error.resetAt, now, zone)}."
        is LlmError.Unclear429 ->
            "The provider says you've hit a limit but not which one. Wait a little and try again."
        is LlmError.ServerTrouble ->
            "The provider is having trouble (HTTP ${error.httpCode}). This isn't your quota; try again in a minute."
        is LlmError.Blocked -> "The provider declined to answer (${error.reason})."
        is LlmError.Unsupported -> "${error.what} isn't supported yet."
        is LlmError.Other -> error.message
    }
}
