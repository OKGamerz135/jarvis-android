package dev.jarvis.assistant.ai

sealed interface Outcome {
    data class Ok(val note: String) : Outcome
    data class Fail(val error: LlmError, val context: FailureContext?) : Outcome
    data class Skipped(val reason: String) : Outcome
}

/** [ok] is null for a skipped step. */
data class DiagLine(val label: String, val ok: Boolean?, val detail: String)

/**
 * Turns the three diagnostic steps into result lines and a plain-language verdict. Pure logic, unit-tested:
 * availability check (no generation), streamed generation, and non-streamed generation as a comparison.
 */
object DiagnosticsVerdict {
    const val CHECK_LABEL = "Availability check (no generation)"
    const val STREAM_LABEL = "Generation, streamed (uses quota)"
    const val ONCE_LABEL = "Generation, not streamed (uses quota)"

    fun isServerSide(error: LlmError): Boolean =
        error is LlmError.Overloaded || error is LlmError.InternalError ||
            error is LlmError.Timeout || error is LlmError.ServerTrouble

    fun lines(check: Outcome, stream: Outcome, once: Outcome): List<DiagLine> = listOf(
        line(CHECK_LABEL, check),
        line(STREAM_LABEL, stream),
        line(ONCE_LABEL, once),
    )

    private fun line(label: String, outcome: Outcome): DiagLine = when (outcome) {
        is Outcome.Ok -> DiagLine(label, true, outcome.note)
        is Outcome.Skipped -> DiagLine(label, null, "skipped: ${outcome.reason}")
        is Outcome.Fail -> {
            val where = FailureFormatter.oneLine(outcome.context)
            val text = ErrorMessages.of(outcome.error)
            DiagLine(label, false, if (where.isEmpty()) text else "$where. $text")
        }
    }

    fun verdict(check: Outcome, stream: Outcome, once: Outcome, name: String = "Google"): String {
        if (check is Outcome.Fail) return checkFailed(check.error, name)
        if (check !is Outcome.Ok) return "The availability check didn't run, so there is nothing to conclude yet."

        return when (stream) {
            is Outcome.Ok ->
                "Everything works: $name was reached, your key is accepted, the model exists and streaming returned a reply. " +
                    "Any earlier server errors were most likely temporary overload on $name's side."
            is Outcome.Skipped ->
                "The availability check passed (key accepted, model exists), but I skipped the generation steps: ${stream.reason}."
            is Outcome.Fail -> streamFailed(stream, once, name)
        }
    }

    private fun checkFailed(error: LlmError, name: String): String = when (error) {
        LlmError.Offline ->
            "$name couldn't be reached at all, so this is a network problem on your side. Try mobile data instead of Wi-Fi, or turn off any VPN."
        is LlmError.NotFromApi ->
            "Something other than $name's API answered the lookup. That points at your network (Wi-Fi, VPN, proxy or ISP), not at $name, your key or JARVIS."
        LlmError.InvalidKey, is LlmError.PermissionDenied ->
            "$name was reached but rejects this key or project. The model name and JARVIS's request aren't the issue."
        is LlmError.BillingRequired ->
            "$name was reached but says the free tier isn't available for this project or region. JARVIS will not enable billing; use another provider instead."
        is LlmError.ModelNotFound ->
            "$name was reached and accepted the key, but this model name isn't available to your key. Edit the model name in provider settings."
        else ->
            "Even the lookup, which makes no generation call, failed: ${ErrorMessages.of(error)} " +
                "So $name's API itself looks unwell right now, or something is in the way."
    }

    private fun streamFailed(stream: Outcome.Fail, once: Outcome, name: String): String {
        if (!isServerSide(stream.error)) {
            return "Key and model are fine, but generation failed: ${ErrorMessages.of(stream.error)}"
        }
        return when (once) {
            is Outcome.Ok ->
                "Streaming fails but non-streaming works, with the same key and model. " +
                    "That points at JARVIS's streaming code, not at $name's capacity. Copy the diagnostics log and send it to me."
            is Outcome.Fail ->
                if (isServerSide(once.error)) {
                    "$name reports a server-side problem for both streaming and non-streaming requests, while the key and the model lookup work. " +
                        "So it isn't your key, the model name or JARVIS's request format. Wait and try again later, or switch provider by hand."
                } else {
                    "Streaming hit a server-side error, and the non-streamed comparison failed differently: ${ErrorMessages.of(once.error)}"
                }
            is Outcome.Skipped ->
                "Streaming hit a server-side error and the non-streamed comparison was skipped (${once.reason}), so I can't separate $name's capacity from JARVIS's streaming code yet."
        }
    }
}
