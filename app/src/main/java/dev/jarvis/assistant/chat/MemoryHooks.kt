package dev.jarvis.assistant.chat

import dev.jarvis.assistant.memory.ChatTurn

/** What memory did with one message: [note] is for the AI, [notice] is a short line the app itself shows to the user. */
data class MemoryOutcome(val note: String, val notice: String? = null)

/** Gives a request the memories that matter for it. Must never throw; "" means none. */
fun interface MemoryContext {
    suspend fun contextFor(recentUserTexts: List<String>): String
}

/**
 * Is told about each user message (and the recent messages, so corrections like "make it green" can be resolved) so it can remember useful facts. It runs BEFORE the reply is requested and returns an
 * honest status note for the AI ("saved", "awaiting approval", "nothing saved"...), or "" for none. Must never throw.
 */
fun interface MemoryObserver {
    suspend fun onUserMessage(text: String, recent: List<ChatTurn>): MemoryOutcome
}

object NoMemory : MemoryContext {
    override suspend fun contextFor(recentUserTexts: List<String>): String = ""
}

object NoObserver : MemoryObserver {
    override suspend fun onUserMessage(text: String, recent: List<ChatTurn>): MemoryOutcome = MemoryOutcome("")
}
