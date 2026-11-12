package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.ChatRole
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.LlmProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChatEngineTest {
    private val storage = FileChatStorage(Files.createTempDirectory("engine").toFile())
    private var counter = 0
    private val engine = ChatEngine(storage, clock = { 1000L + counter }, newId = { "id${counter++}" })
    private val target = ChatTarget(dev.jarvis.assistant.settings.AiProvider.GROQ, 10_000, "model-x", "KEY", "system")

    private class Fake(val block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(ChatRequest) -> Unit) : LlmProvider {
        var lastRequest: ChatRequest? = null
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> {
            lastRequest = request
            return flow { block(request) }
        }
    }

    private fun start() = engine.addUserMessage(Conversation.empty("c1", 0), "Hello JARVIS, how are you today?")

    @Test
    fun userMessageIsSavedImmediatelyAndTitlesTheChat() {
        val c = start()
        assertEquals("Hello JARVIS, how are you today?", c.title)
        assertEquals(1, storage.load("c1")!!.messages.size)
    }

    @Test
    fun successSavesTheReplyWithProviderAndModel() = runBlocking {
        val llm = Fake { emit("Good "); emit("evening.") }
        val partials = mutableListOf<String>()
        val r = engine.generate(start(), target, llm, CallOptions()) { partials += it } as GenResult.Success
        assertEquals(listOf("Good ", "Good evening."), partials)
        val last = r.conversation.messages.last()
        assertEquals("Good evening.", last.text)
        assertEquals("Groq", last.provider)
        assertEquals("model-x", last.model)
        assertFalse(last.interrupted)
        assertEquals(2, storage.load("c1")!!.messages.size)
        assertEquals("KEY", llm.lastRequest!!.apiKey)
        assertEquals("system", llm.lastRequest!!.systemPrompt)
    }

    @Test
    fun failureBeforeAnyTextKeepsOnlyTheUserMessage() = runBlocking {
        val llm = Fake { throw LlmException(LlmError.Offline) }
        val r = engine.generate(start(), target, llm, CallOptions()) {} as GenResult.Failure
        assertEquals(LlmError.Offline, r.error)
        assertEquals(1, r.conversation.messages.size)
        assertTrue(r.conversation.awaitingReply)
    }

    @Test
    fun failureAfterSomeTextSavesAnInterruptedReply() = runBlocking {
        val llm = Fake { emit("Partial"); throw LlmException(LlmError.Overloaded(null)) }
        val r = engine.generate(start(), target, llm, CallOptions()) {} as GenResult.Failure
        val last = r.conversation.messages.last()
        assertEquals("Partial", last.text)
        assertTrue(last.interrupted)
        assertTrue(storage.load("c1")!!.messages.last().interrupted)
    }

    @Test
    fun emptyReplyIsAnErrorNotABlankBubble() = runBlocking {
        val llm = Fake { emit("   ") }
        val r = engine.generate(start(), target, llm, CallOptions()) {}
        assertTrue(r is GenResult.Failure)
        assertEquals(1, r.conversation.messages.size)
    }

    @Test
    fun stopSavesThePartialReplyAndCancels() = runBlocking {
        val gotPartial = CompletableDeferred<Unit>()
        val llm = Fake { emit("half an answ"); awaitCancellation() }
        val conv = start()
        val job = launch {
            engine.generate(conv, target, llm, CallOptions()) { gotPartial.complete(Unit) }
        }
        gotPartial.await()
        job.cancelAndJoin()
        val saved = storage.load("c1")!!.messages.last()
        assertEquals("half an answ", saved.text)
        assertTrue(saved.interrupted)
    }

    @Test
    fun retryDoesNotDuplicateTheUserMessageAndOnlyNewestHistoryIsSent() = runBlocking {
        var calls = 0
        val llm = Fake { calls++; if (calls == 1) throw LlmException(LlmError.Offline) else emit("ok") }
        val first = engine.generate(start(), target, llm, CallOptions()) {} as GenResult.Failure
        val second = engine.generate(first.conversation, target, llm, CallOptions()) {} as GenResult.Success
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), second.conversation.messages.map { it.role })
        assertEquals(1, llm.lastRequest!!.messages.size)
    }

    @Test
    fun theWholeHistoryIsSentWithinBudgetOldestTrimmed() = runBlocking {
        var conv = start()
        val llm = Fake { emit("r") }
        val small = ChatTarget(dev.jarvis.assistant.settings.AiProvider.GROQ, 60, "m", "K", "s")
        repeat(4) {
            conv = (engine.generate(conv, small, llm, CallOptions()) {} as GenResult.Success).conversation
            conv = engine.addUserMessage(conv, "next question number $it with some padding")
        }
        engine.generate(conv, small, llm, CallOptions()) {}
        assertTrue(llm.lastRequest!!.messages.sumOf { it.text.length } <= 60 + 60)
        assertEquals(ChatRole.USER, llm.lastRequest!!.messages.first().role)
    }
}
