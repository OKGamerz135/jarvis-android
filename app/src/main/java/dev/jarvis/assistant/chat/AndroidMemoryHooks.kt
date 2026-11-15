package dev.jarvis.assistant.chat

import dev.jarvis.assistant.memory.ChatTurn
import dev.jarvis.assistant.memory.MemoryService
import dev.jarvis.assistant.memory.MemoryStatusNote
import dev.jarvis.assistant.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/** Connects the memory service to chat, honouring the "Use memory" switch in settings. */
class AndroidMemoryHooks(
    private val settings: SettingsRepository,
    private val service: MemoryService,
) : MemoryContext, MemoryObserver {
    private suspend fun enabled(): Boolean = try {
        settings.settings.first().memoryEnabled
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    override suspend fun contextFor(recentUserTexts: List<String>): String =
        if (enabled()) service.contextFor(recentUserTexts) else ""

    override suspend fun onUserMessage(text: String, recent: List<ChatTurn>): MemoryOutcome =
        if (enabled()) {
            val r = service.observe(text, recent)
            MemoryOutcome(MemoryStatusNote.of(r), MemoryStatusNote.notice(r))
        } else {
            MemoryOutcome(MemoryStatusNote.OFF)
        }
}
