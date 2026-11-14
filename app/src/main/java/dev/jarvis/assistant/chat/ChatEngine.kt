package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.ChatRole
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

/** Everything needed to make one request, already checked for key and consent. */
class ChatTarget(
    val provider: AiProvider,
    val historyBudgetChars: Int,
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
    /** Restates the personality setting on the newest user message of the request (not saved). Empty = none. */
    val styleReminder: String = "",
) {
    val providerLabel: String get() = provider.label
}

sealed interface GenResult {
    val conversation: Conversation

    data class Success(override val conversation: Conversation) : GenResult

    /** [error] is null only for a failure that isn't an LlmError. The conversation may hold a partial reply. */
    data class Failure(
        override val conversation: Conversation,
        val error: LlmError?,
        val exception: LlmException?,
        /** True when some reply text had already arrived (and was saved as an interrupted reply). */
        val partial: Boolean = false,
    ) : GenResult
}

class ChatEngine(
    private val storage: ChatStorage,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Adds the user's message and saves it right away, so it survives a crash or a failed request. */
    fun addUserMessage(conv: Conversation, text: String): Conversation {
        val now = clock()
        val base = if (conv.isEmpty) conv.copy(title = Conversation.titleFrom(text)) else conv
        val updated = base.withMessage(StoredMessage(newId(), ChatRole.USER, text, timeMillis = now))
        storage.save(updated)
        return updated
    }

    /**
     * Asks the provider to answer the conversation as it stands. Saves the reply, or a partial reply marked
     * interrupted. Cancellation (the Stop button) saves the partial reply and then propagates.
     */
    suspend fun generate(
        conv: Conversation,
        target: ChatTarget,
        llm: LlmProvider,
        options: CallOptions,
        onPartial: (String) -> Unit,
    ): GenResult {
        val history = withReminder(HistoryTrimmer.select(conv.messages, target.historyBudgetChars).messages, target.styleReminder)
        val request = ChatRequest(target.model, target.apiKey, target.systemPrompt, history)
        val reply = StringBuilder()
        try {
            llm.stream(request, options).collect { chunk ->
                // After Stop, nothing more may be appended or shown, even if a chunk was already in flight.
                currentCoroutineContext().ensureActive()
                reply.append(chunk)
                onPartial(reply.toString())
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { savePartial(conv, target, reply.toString()) }
            throw e
        } catch (e: LlmException) {
            val saved = savePartial(conv, target, reply.toString())
            return GenResult.Failure(saved, e.error, e, partial = saved.messages.size > conv.messages.size)
        }
        val text = reply.toString()
        if (text.isBlank()) {
            return GenResult.Failure(conv, LlmError.Blocked("the model returned an empty reply"), null)
        }
        val done = conv.withMessage(assistantMessage(target, text, interrupted = false))
        storage.save(done)
        return GenResult.Success(done)
    }

    /** Adds the note to a copy of the newest user message. The saved conversation is never touched. */
    private fun withReminder(messages: List<dev.jarvis.assistant.ai.ChatMessage>, note: String): List<dev.jarvis.assistant.ai.ChatMessage> {
        val last = messages.lastOrNull()
        if (note.isBlank() || last == null || last.role != ChatRole.USER) return messages
        return messages.dropLast(1) + last.copy(text = last.text + "\n\n" + note)
    }

    private fun savePartial(conv: Conversation, target: ChatTarget, text: String): Conversation {
        if (text.isBlank()) return conv
        val updated = conv.withMessage(assistantMessage(target, text, interrupted = true))
        storage.save(updated)
        return updated
    }

    private fun assistantMessage(target: ChatTarget, text: String, interrupted: Boolean) = StoredMessage(
        id = newId(),
        role = ChatRole.ASSISTANT,
        text = text,
        provider = target.providerLabel,
        model = target.model,
        timeMillis = clock(),
        interrupted = interrupted,
    )
}
