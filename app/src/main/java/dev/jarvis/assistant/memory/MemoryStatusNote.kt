package dev.jarvis.assistant.memory

/**
 * The truth about what memory did with ONE user message, written for the AI so it can only claim what really happened.
 * It is built from the real result of the database operation (see [MemoryService.observe]). It never contains the
 * memory text itself.
 */
object MemoryStatusNote {
    private const val FOOTER =
        "You cannot save memories yourself: the app does it, and this note is the only proof. Never say you saved, updated, " +
            "stored or will remember anything unless this note says it was done. This note covers only the latest user " +
            "message; the Memory screen in Settings is the record. Never say the memory feature is down, broken, offline or " +
            "unavailable, and never invent a technical reason: unless this note says FAILED, memory is working normally."

    const val OFF =
        "Memory status: memory is switched off in Settings, so nothing is being saved. If the user asks you to remember " +
            "something, say memory is off and they can turn it on under Settings, Memory. Never claim to have saved anything."

    const val UNKNOWN =
        "Memory status: the memory update did not finish in time, so you do not know whether anything was saved. Do not " +
            "claim it was saved; if the user asked, say it may not have been and to check the Memory screen. $FOOTER"

    fun of(r: ObserveResult): String {
        val lines = ArrayList<String>()
        if (r.failed) {
            lines += "FAILED: the memory store was unavailable, so NOTHING was saved or changed. If the user asked you to " +
                "remember something, tell them plainly it could not be saved right now."
        } else {
            if (r.saved > 0) lines += "SAVED: ${r.saved} new memory(ies) were stored and confirmed."
            if (r.updated > 0) lines += "UPDATED: ${r.updated} existing memory(ies) were changed to the new value and confirmed."
            if (r.duplicates > 0 && r.saved + r.updated == 0) lines += "ALREADY SAVED: it was already remembered, nothing changed."
            if (r.proposed > 0) {
                lines += "AWAITING APPROVAL: ${r.proposed} item(s) are sensitive or uncertain, so they are NOT saved yet. Say they " +
                    "are waiting for the user's approval (Save / Don't save in the banner above the chat, or Settings, Memory)."
            }
            if (r.blocked > 0) {
                lines += "REFUSED: it looked like a secret, password, key, or card, account or ID number. It was NOT saved and " +
                    "never will be. Tell the user that, and advise against sharing secrets."
            }
            if (r.full) lines += "NOT SAVED: the memory is full. Tell the user to delete some memories in Settings, Memory."
            if (lines.isEmpty() && r.referenceAsked) {
                lines += "NOT SAVED: the user said to save \"that\", but no earlier message of theirs held a clear fact to keep. " +
                    "Say it was NOT saved because it was unclear what to save, and ask them to say it in a full sentence, for " +
                    "example \"Remember that my favourite colour is orange\". Memory itself is working."
            }
            if (lines.isEmpty() && r.correctionAsked) {
                lines += "NOT UPDATED: the user seems to be correcting something you remember, but it could not be matched to " +
                    "one saved memory, so NOTHING was changed. Say it was NOT updated, and ask them to say it in full, for example " +
                    "\"My favourite colour is orange\". Do not act as if the correction was stored."
            }
            if (lines.isEmpty()) {
                lines += if (r.askedToSave) {
                    "NOT SAVED: the user asked you to remember something but nothing could be stored (it was not clear what to " +
                        "keep). Say it was NOT saved, and suggest the form \"Remember that I ...\" or adding it in Settings, Memory."
                } else {
                    "Nothing was saved or changed for this message."
                }
            }
        }
        return "Memory status for the user's latest message: " + lines.joinToString(" ") + " " + FOOTER
    }

    /**
     * A short line the APP shows under the chat, built from the same real result. The AI cannot change it, so what the
     * user sees about memory never depends on what the model says. Null when memory had nothing to do with the message.
     */
    fun notice(r: ObserveResult): String? = when {
        r.failed -> "Memory: could not save right now. Nothing was changed."
        r.updated > 0 -> "Memory updated: ${r.summary.orEmpty()}".trim().trimEnd(':')
        r.saved > 0 -> "Memory saved: ${r.summary.orEmpty()}".trim().trimEnd(':')
        r.proposed > 0 -> "Memory: waiting for your approval (see above)."
        r.blocked > 0 -> "Memory: not saved. It looked like a secret."
        r.full -> "Memory: full. Delete some memories in Settings."
        r.duplicates > 0 -> "Memory: already saved."
        r.askedToSave || r.correctionAsked || r.referenceAsked -> "Memory: nothing was saved. It was not clear what to keep."
        else -> null
    }
}
