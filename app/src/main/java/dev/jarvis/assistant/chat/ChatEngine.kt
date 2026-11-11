package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.ChatRole
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** Everything needed to make one request, already checked for key and consent. */
class ChatTarget(
    val providerLabel: String,
    val historyBudgetChars: Int,
    val model: String,
    val apiKey: String,
    val systemPrompt: String,
)

sealed interface GenResult {
    val conversation: Conversation

    data class Success(override val conversation: Conversation) : GenResult

    /** [error] is null only for a failure that isn't an LlmError. The conversation may hold a partial reply. */
    data class Failure(override val conversation: Conversation, val error: LlmError?, val exception: LlmException?) : GenResult
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
        val history = HistoryTrimmer.select(conv.messages, target.historyBudgetChars).messages
        val request = ChatRequest(target.model, target.apiKey, target.systemPrompt, history)
        val reply = StringBuilder()
        try {
            llm.stream(request, options).collect { chunk ->
                reply.append(chunk)
                onPartial(reply.toString())
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { savePartial(conv, target, reply.toString()) }
            throw e
        } catch (e: LlmException) {
            return GenResult.Failure(savePartial(conv, target, reply.toString()), e.error, e)
        }
        val text = reply.toString()
        if (text.isBlank()) {
            return GenResult.Failure(conv, LlmError.Blocked("the model returned an empty reply"), null)
        }
        val done = conv.withMessage(assistantMessage(target, text, interrupted = false))
        storage.save(done)
        return GenResult.Success(done)
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
