package dev.jarvis.assistant.chat

/** Gives a request the memories that matter for it. Must never throw; "" means none. */
fun interface MemoryContext {
    suspend fun contextFor(recentUserTexts: List<String>): String
}

/** Is told about each user message so it can remember useful facts. Runs in the background; never blocks chat. */
fun interface MemoryObserver {
    suspend fun onUserMessage(text: String)
}

object NoMemory : MemoryContext {
    override suspend fun contextFor(recentUserTexts: List<String>): String = ""
}

object NoObserver : MemoryObserver {
    override suspend fun onUserMessage(text: String) {}
}
