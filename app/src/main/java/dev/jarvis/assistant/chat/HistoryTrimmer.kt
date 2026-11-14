package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.ChatMessage
import dev.jarvis.assistant.ai.ChatRole
import dev.jarvis.assistant.settings.AiProvider

/** How much conversation each provider is sent. Groq's free tier allows about 8,000 tokens a minute. */
object ChatLimits {
    fun historyBudgetChars(provider: AiProvider): Int = when (provider) {
        AiProvider.GROQ -> 10_000
        AiProvider.GEMINI -> 60_000
        AiProvider.OPENROUTER -> 24_000 // free models vary a lot in context size; stay conservative
        AiProvider.CLOUDFLARE -> 24_000 // the free allowance is counted in neurons (tokens), so keep requests modest
        AiProvider.SAMBANOVA -> 24_000 // 200,000 tokens a day and only 20 requests: modest requests keep the allowance useful
    }
}

object HistoryTrimmer {
    data class Result(val messages: List<ChatMessage>, val droppedCount: Int)

    /**
     * Keeps the newest messages that fit in [budgetChars] (the newest is always kept), makes the list start with a
     * user message, and merges neighbours with the same role, which can occur after a failed turn.
     */
    fun select(all: List<StoredMessage>, budgetChars: Int): Result {
        val kept = ArrayDeque<StoredMessage>()
        var used = 0
        for (m in all.asReversed()) {
            if (kept.isNotEmpty() && used + m.text.length > budgetChars) break
            kept.addFirst(m)
            used += m.text.length
        }
        while (kept.isNotEmpty() && kept.first().role != ChatRole.USER) kept.removeFirst()
        val merged = ArrayList<ChatMessage>()
        for (m in kept) {
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role) {
                merged[merged.size - 1] = ChatMessage(m.role, last.text + "\n\n" + m.text)
            } else {
                merged += ChatMessage(m.role, m.text)
            }
        }
        return Result(merged, all.size - kept.size)
    }
}
