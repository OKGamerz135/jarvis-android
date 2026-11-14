package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class ChatMemoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = FileChatStorage(Files.createTempDirectory("mem").toFile())
    private val requests = CopyOnWriteArrayList<ChatRequest>()

    @After fun tearDown() = scope.cancel()

    private val llm = object : LlmProvider {
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> {
            requests += request
            return flow { emit("Sure thing.") }
        }
    }

    private val block = "Things you remember about the user: - Favourite colour is orange"

    @Test fun everyProviderGetsTheSameMemoryBlockAndHistoryIsUntouched() = runBlocking {
        val engine = ChatEngine(storage, memory = MemoryContext { block })
        for (p in AiProvider.entries) {
            val conv = engine.addUserMessage(Conversation.empty("c-${p.name}", 0), "What colour should my room be?")
            val target = ChatTarget(p, 10_000, "m", "KEY", "SYSTEM")
            engine.generate(conv, target, llm, CallOptions()) { }
            val sent = requests.last()
            assertTrue(p.name, sent.systemPrompt.startsWith("SYSTEM"))
            assertTrue(p.name, sent.systemPrompt.contains("Favourite colour is orange"))
            // The saved conversation never contains the injected memory text.
            val saved = storage.load("c-${p.name}")!!
            assertFalse(p.name, saved.messages.any { it.text.contains("Favourite colour is orange") })
            assertEquals(p.name, 2, saved.messages.size)
        }
        assertEquals(AiProvider.entries.size, requests.size)
    }

    @Test fun noMemoryLeavesThePromptExactlyAsBefore() = runBlocking {
        val engine = ChatEngine(storage, memory = MemoryContext { "" })
        val conv = engine.addUserMessage(Conversation.empty("c1", 0), "hello there friend")
        engine.generate(conv, ChatTarget(AiProvider.GROQ, 10_000, "m", "KEY", "SYSTEM"), llm, CallOptions()) { }
        assertEquals("SYSTEM", requests.last().systemPrompt)
    }

    @Test fun chatStillAnswersWhenMemoryRetrievalThrows() = runBlocking {
        val engine = ChatEngine(storage, memory = MemoryContext { error("database exploded") })
        val conv = engine.addUserMessage(Conversation.empty("c1", 0), "hello there friend")
        val r = engine.generate(conv, ChatTarget(AiProvider.GROQ, 10_000, "m", "KEY", "SYSTEM"), llm, CallOptions()) { }
        assertTrue(r is GenResult.Success)
        assertEquals("SYSTEM", requests.last().systemPrompt)
    }

    @Test fun retrievalSeesTheRecentUserMessages() = runBlocking {
        var seen: List<String> = emptyList()
        val engine = ChatEngine(storage, memory = MemoryContext { seen = it; "" })
        val conv = engine.addUserMessage(Conversation.empty("c1", 0), "what colour is best")
        engine.generate(conv, ChatTarget(AiProvider.GEMINI, 10_000, "m", "KEY", "SYSTEM"), llm, CallOptions()) { }
        assertTrue(seen.any { it.contains("colour") })
    }

    @Test fun chatContinuesWhenTheMemoryObserverFails() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val source = object : ProviderSource {
            override suspend fun resolve(provider: AiProvider?) =
                Resolved.Ready(ChatTarget(AiProvider.GROQ, 10_000, "m", "KEY", "SYSTEM"), llm)
            override suspend fun snapshot() = ProviderSnapshot(
                AiProvider.GROQ, false,
                AiProvider.entries.map { Candidate(it, "m", it == AiProvider.GROQ, it == AiProvider.GROQ, true, false) },
            )
        }
        val runner = ChatRunner(
            storage, ChatEngine(storage), scope, Dispatchers.IO, source, newLedger(), tickMs = 5,
            memoryObserver = MemoryObserver { seen += it; error("observer crashed") },
        )
        withTimeout(5000) { while (runner.send("My favourite colour is orange.") != SendResult.ACCEPTED) delay(5) }
        withTimeout(5000) { while (requests.isEmpty()) delay(5) }
        val s = withTimeout(5000) { runner.state.first { it.loaded && !it.generating } }
        assertTrue(s.conversation.messages.any { it.text.contains("Sure thing") })
        withTimeout(5000) { while (seen.isEmpty()) delay(5) }
        assertEquals("My favourite colour is orange.", seen.first())
    }
}
