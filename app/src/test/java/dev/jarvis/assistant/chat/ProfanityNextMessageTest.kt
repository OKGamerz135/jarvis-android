package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * The slider must change the very next message, in the open conversation and in new ones, with no restart.
 * [LiveSettingsSource] does what AndroidProviderSource.resolve does: read the CURRENT settings and build the prompt per request.
 */
class ProfanityNextMessageTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = FileChatStorage(Files.createTempDirectory("prof").toFile())
    private val requests = CopyOnWriteArrayList<ChatRequest>()

    @After
    fun tearDown() = scope.cancel()

    @Volatile private var settings = JarvisSettings(profanity = 0)

    private val llm = object : LlmProvider {
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> {
            requests += request
            return flow { emit("Hello, sir. A very polite reply number ${requests.size}.") }
        }
    }

    private inner class LiveSettingsSource : ProviderSource {
        override suspend fun resolve(provider: AiProvider?): Resolved {
            val p = provider ?: AiProvider.GROQ
            return Resolved.Ready(ChatTarget(p, 10_000, "m", "KEY", PromptBuilder.build(settings)), llm)
        }

        override suspend fun snapshot() = ProviderSnapshot(
            AiProvider.GROQ, false,
            AiProvider.entries.map { Candidate(it, "m", it == AiProvider.GROQ, it == AiProvider.GROQ, true, false) },
        )
    }

    private val runner = ChatRunner(storage, ChatEngine(storage), scope, Dispatchers.IO, LiveSettingsSource(), newLedger(), tickMs = 5)

    private suspend fun idle() = withTimeout(5000) { runner.state.first { it.loaded && !it.generating } }

    private suspend fun sendAndWait(text: String) {
        idle()
        assertEquals(SendResult.ACCEPTED, runner.send(text))
        withTimeout(5000) { runner.state.first { it.generating || requests.size > 0 } }
        idle()
    }

    private val mild = "Profanity and insults: OFF"
    private val unhinged = "level 4 of 4 (Unhinged)"

    @Test
    fun changingTheSliderMidConversationAffectsTheNextMessageWithoutRestartOrNewChat() = runBlocking {
        sendAndWait("first")
        sendAndWait("second") // still level 0
        settings = settings.copy(profanity = 4) // the user drags the slider
        sendAndWait("third") // same conversation, same runner, no restart
        assertEquals(3, requests.size)
        assertTrue(requests[0].systemPrompt.contains(mild))
        assertTrue(requests[1].systemPrompt.contains(mild))
        assertTrue(requests[2].systemPrompt.contains(unhinged))
        assertFalse(requests[2].systemPrompt.contains(mild))
        // history is carried, so the earlier polite assistant replies ARE in the request...
        assertTrue(requests[2].messages.any { it.text.contains("A very polite reply number 1") })
        // ...but they are only messages; the system prompt is rebuilt, and tells the model not to copy their tone
        assertTrue(requests[2].systemPrompt.contains("ignore their tone and follow the current level"))
    }

    @Test
    fun aNewConversationAlsoUsesTheCurrentLevelAndSwitchingBackToZeroGoesClean() = runBlocking {
        settings = settings.copy(profanity = 3)
        sendAndWait("a")
        runner.newChat()
        idle()
        settings = settings.copy(profanity = 4)
        sendAndWait("b")
        settings = settings.copy(profanity = 0)
        sendAndWait("c")
        assertTrue(requests[0].systemPrompt.contains("level 3 of 4"))
        assertTrue(requests[1].systemPrompt.contains(unhinged))
        assertTrue(requests[2].systemPrompt.contains(mild))
        assertFalse(requests[2].systemPrompt.contains("Unhinged"))
        // the old conversation's history never injects a system prompt of its own
        assertTrue(requests.all { r -> r.messages.none { it.text.contains("Profanity and") } })
    }
}
