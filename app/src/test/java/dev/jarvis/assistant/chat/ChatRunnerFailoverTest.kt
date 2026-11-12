package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.ai.ResetSource
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ChatRunnerFailoverTest {
    private val G = AiProvider.GEMINI
    private val R = AiProvider.GROQ
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = FileChatStorage(Files.createTempDirectory("fo").toFile())
    private var now = 1_000_000L
    private val ledger = newLedger { now }
    private val net = FakeNetwork()

    @After fun tearDown() = scope.cancel()

    private class Fake(val block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) : LlmProvider {
        val calls = AtomicInteger(0)
        val requests = java.util.concurrent.CopyOnWriteArrayList<ChatRequest>()
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> {
            val n = calls.incrementAndGet()
            requests += request
            return flow { block(n) }
        }
    }

    private fun fail(e: LlmError) = LlmException(e)
    private fun quota() = fail(LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 3_600_000), "PerDay", ResetSource.REPORTED))
    private fun ok(text: String) = Fake { emit(text) }
    private fun throwing(e: LlmException) = Fake { throw e }

    private fun runner(source: FakeSource, countdown: Int = 2) =
        ChatRunner(
            storage, ChatEngine(storage), scope, Dispatchers.IO, source.also { it.ledger = ledger }, ledger, net,
            clock = { now }, countdownSeconds = countdown, tickMs = 20,
        )

    private suspend fun ChatRunner.idle() = withTimeout(5000) { state.first { it.loaded && !it.generating } }
    private suspend fun ChatRunner.sendWhenFree(t: String) = withTimeout(5000) { while (send(t) != SendResult.ACCEPTED) delay(5) }
    private suspend fun ChatRunner.until(c: (ChatUiState) -> Boolean) = withTimeout(5000) { state.first(c) }

    @Test fun quotaThenCountdownThenSwitchesOnceWithoutDuplicates() = runBlocking {
        val a = throwing(quota()); val b = ok("from groq")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle()
        assertEquals(SendResult.ACCEPTED, r.send("hello"))
        val offer = r.until { it.failover != null }.failover!!
        assertEquals("Your current AI provider has exhausted its available quota. Switching to ${R.label} in 2 seconds.", offer.announcement)
        assertTrue(offer.disclosure.contains("conversation so far will be sent to ${R.label}"))
        val s = r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(1, b.calls.get())
        assertEquals(listOf("hello", "from groq"), s.conversation.messages.map { it.text })
        assertEquals(R, s.temporaryProvider)
        assertEquals(G, s.preferredProvider)
        assertTrue(ledger.isExhausted(G))
        assertNull(s.problem)
        assertEquals("hello", b.requests.single().messages.last().text)
    }

    @Test fun cancelDuringCountdownStaysAndKeepsMessage() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 50)
        r.idle(); r.send("hi")
        r.until { it.failover != null }
        r.cancelFailover()
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertEquals(listOf("hi"), s.conversation.messages.map { it.text })
        assertNull(s.temporaryProvider)
        assertTrue(s.problem!!.message.startsWith("Switch cancelled"))
    }

    @Test fun stopDuringCountdownAlsoCancelsTheSwitch() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 50)
        r.idle(); r.send("hi")
        r.until { it.failover != null }
        r.stop()
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertNull(s.temporaryProvider)
        assertTrue(s.problem!!.message.startsWith("Switch cancelled"))
    }

    @Test fun failoverDisabledShowsErrorAndNeverCallsOther() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G, failoverEnabled = false))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertTrue(s.problem!!.message.contains("failover is off"))
        assertEquals(listOf("hi"), s.conversation.messages.map { it.text })
    }

    @Test fun partialTextThenQuotaErrorDoesNotSwitch() = runBlocking {
        val a = Fake { emit("half an answ"); throw quota() }; val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(0, b.calls.get())
        assertNull(s.temporaryProvider)
        assertNotNull(s.problem)
        assertNull(s.failover)
    }

    @Test fun noAlternativeListsReasons() = runBlocking {
        val a = throwing(quota())
        val r = runner(FakeSource(mapOf(G to a, R to ok("x")), preferred = G, withoutKey = setOf(R)))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertTrue(s.problem!!.message.contains("No other provider is ready"))
        assertTrue(s.problem!!.message.contains("no API key"))
        assertTrue(s.problem!!.needsSetup)
    }

    @Test fun shortRateLimitWaitsAndRetriesOnceOnSameProvider() = runBlocking {
        val a = Fake { n -> if (n == 1) throw fail(LlmError.RateLimited(1)) else emit("later") }
        val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(2, a.calls.get())
        assertEquals(0, b.calls.get())
        assertEquals(listOf("hi", "later"), s.conversation.messages.map { it.text })
        assertNull(s.temporaryProvider)
    }

    @Test fun rateLimitThatPersistsShowsErrorAfterOneRetry() = runBlocking {
        val a = throwing(fail(LlmError.RateLimited(1))); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(2, a.calls.get())
        assertEquals(0, b.calls.get())
        assertNotNull(s.problem)
    }

    @Test fun longRateLimitDoesNotWaitOrSwitch() = runBlocking {
        val a = throwing(fail(LlmError.RateLimited(120))); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(0, b.calls.get())
    }

    @Test fun offlineWaitsForNetworkThenRetriesOnce() = runBlocking {
        val a = Fake { n -> if (n == 1) throw fail(LlmError.Offline) else emit("back") }
        val r = runner(FakeSource(mapOf(G to a, R to ok("x")), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(1, net.waits)
        assertEquals(2, a.calls.get())
        assertEquals(listOf("hi", "back"), s.conversation.messages.map { it.text })
    }

    @Test fun otherErrorsNeverSwitch() = runBlocking {
        for (e in listOf(LlmError.Overloaded(null), LlmError.InvalidKey)) {
            val a = throwing(fail(e)); val b = ok("x")
            val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
            r.idle(); r.send("hi")
            r.idle()
            assertEquals(0, b.calls.get())
        }
    }

    @Test fun knownExhaustedProviderSkipsStraightToCountdownWithoutCallingIt() = runBlocking {
        ledger.markExhausted(G, LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 3_600_000), "d", ResetSource.REPORTED))
        val a = ok("should not be called"); val b = ok("from groq")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("hi")
        val s = r.idle()
        assertEquals(0, a.calls.get())
        assertEquals(1, b.calls.get())
        assertEquals(R, s.temporaryProvider)
    }

    @Test fun nextMessageStaysOnTemporaryProvider() = runBlocking {
        val a = throwing(quota()); val b = ok("answer")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("one"); r.idle()
        r.sendWhenFree("two")
        val s = r.idle()
        assertEquals(1, a.calls.get())
        assertEquals(2, b.calls.get())
        assertEquals(4, s.conversation.messages.size)
    }

    @Test fun recoveryIsOfferedNotAutomaticAcceptAndDecline() = runBlocking {
        val a = Fake { n -> if (n == 1) throw quota() else emit("gemini again") }; val b = ok("answer")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle(); r.send("one"); r.idle()
        assertNull(r.state.value.recoveryOffer) // still exhausted
        now += 3_700_000 // the reported reset passes
        r.checkRecovery()
        r.until { it.recoveryOffer == G }
        assertEquals(R, r.state.value.temporaryProvider) // not switched silently
        r.declineRecovery()
        assertNull(r.state.value.recoveryOffer)
        r.checkRecovery()
        delay(100)
        assertNull(r.state.value.recoveryOffer) // doesn't nag
        assertEquals(R, r.state.value.temporaryProvider)
        // Sending counts as staying; accepting later still works.
        r.acceptRecovery()
        assertNull(r.state.value.temporaryProvider)
        r.sendWhenFree("two")
        val s = r.idle()
        assertEquals("gemini again", s.conversation.messages.last().text)
    }

    @Test fun countdownPausesWhileAppIsOffScreen() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 1)
        r.idle()
        r.setVisible(false)
        r.send("hi")
        r.until { it.failover != null }
        delay(300)
        assertEquals(0, b.calls.get()) // not switched while hidden
        assertTrue(r.state.value.failover != null)
        r.setVisible(true)
        r.idle()
        assertEquals(1, b.calls.get())
    }

    @Test fun simulationSwitchesWithoutCallingAnyRealProvider() = runBlocking {
        val a = ok("real gemini"); val b = ok("from groq")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G))
        r.idle()
        r.armSimulation()
        assertTrue(r.state.value.simulationArmed)
        r.send("hi")
        val s = r.idle()
        assertEquals(0, a.calls.get())
        assertEquals(1, b.calls.get())
        assertFalse(s.simulationArmed)
        assertEquals(R, s.temporaryProvider)
    }

    @Test fun duplicateSendDuringCountdownIsRefused() = runBlocking {
        val a = throwing(quota()); val b = ok("x")
        val r = runner(FakeSource(mapOf(G to a, R to b), preferred = G), countdown = 50)
        r.idle(); r.send("hi")
        r.until { it.failover != null }
        assertEquals(SendResult.BUSY, r.send("hi again"))
        r.cancelFailover()
        val s = r.idle()
        assertEquals(listOf("hi"), s.conversation.messages.map { it.text })
    }
}
