package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.ChatRole
import dev.jarvis.assistant.ai.LlmProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ChatRunnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = FileChatStorage(Files.createTempDirectory("runner").toFile())
    private val calls = AtomicInteger(0)
    private val requests = java.util.concurrent.CopyOnWriteArrayList<ChatRequest>()
    private val upstreamCancelled = CompletableDeferred<Unit>()

    @After
    fun tearDown() = scope.cancel()

    private val target = ChatTarget("Groq", 10_000, "m", "KEY", "system")

    private fun runner(llm: LlmProvider) =
        ChatRunner(storage, ChatEngine(storage), scope, Dispatchers.IO, { Resolved.Ready(target, llm) })

    /** A provider whose reply is controlled by the test. */
    private fun provider(block: suspend kotlinx.coroutines.flow.FlowCollector<String>.(Int) -> Unit) = object : LlmProvider {
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> {
            val n = calls.incrementAndGet()
            requests += request
            return flow { block(n) }
        }
    }

    private suspend fun ChatRunner.awaitIdle() = withTimeout(5000) { state.first { it.loaded && !it.generating } }
    private suspend fun ChatRunner.awaitState(cond: (ChatUiState) -> Boolean) = withTimeout(5000) { state.first(cond) }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun stopAfterPartialTextCancelsUpstreamKeepsTextAndPersistsInterrupted() = runBlocking {
        val llm = provider {
            try {
                emit("part one ")
                emit("part two")
                awaitCancellation()
            } finally {
                upstreamCancelled.complete(Unit)
            }
        }
        val r = runner(llm)
        r.awaitIdle()
        assertEquals(SendResult.ACCEPTED, r.send("hello"))
        r.awaitState { it.streaming == "part one part two" }
        r.stop()
        withTimeout(5000) { upstreamCancelled.await() } // the upstream operation really was cancelled
        val s = r.awaitIdle()
        val last = s.conversation.messages.last()
        assertEquals("part one part two", last.text)
        assertTrue(last.interrupted)
        assertEquals("Response interrupted. The text received so far was kept.", s.problem!!.message)
        assertEquals(null, s.streaming)
        // Persisted, not just on screen.
        val saved = storage.load(s.conversation.id)!!.messages.last()
        assertEquals("part one part two", saved.text)
        assertTrue(saved.interrupted)
        assertEquals("Groq", saved.provider)
    }

    @Test
    fun stopBeforeAnyTextStillShowsInterruptedAndAllowsRetry() = runBlocking {
        val llm = provider { n ->
            if (n == 1) {
                try { awaitCancellation() } finally { upstreamCancelled.complete(Unit) }
            } else emit("answer")
        }
        val r = runner(llm)
        r.awaitIdle()
        r.send("hello")
        r.awaitState { it.generating }
        delay(100)
        r.stop()
        withTimeout(5000) { upstreamCancelled.await() }
        val s = r.awaitIdle()
        assertEquals("Response interrupted before any text arrived.", s.problem!!.message)
        assertEquals(1, s.conversation.messages.size)
        assertTrue(s.conversation.awaitingReply)
        // Retry answers the same message without adding it twice.
        assertEquals(SendResult.ACCEPTED, r.retry())
        val done = r.awaitState { !it.generating && it.conversation.messages.size == 2 }
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), done.conversation.messages.map { it.role })
    }

    @Test
    fun nothingIsAppendedAfterStop() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val llm = provider {
            emit("kept")
            try { awaitCancellation() } finally {
                // A chunk that "arrives" during teardown must be ignored.
                release.complete(Unit)
            }
        }
        val r = runner(llm)
        r.awaitIdle()
        r.send("x")
        r.awaitState { it.streaming == "kept" }
        r.stop()
        withTimeout(5000) { release.await() }
        val s = r.awaitIdle()
        delay(200)
        assertEquals("kept", r.state.value.conversation.messages.last().text)
        assertEquals("kept", storage.load(s.conversation.id)!!.messages.last().text)
    }

    @Test
    fun stopUpdatesTheUiImmediately() = runBlocking {
        val llm = provider { emit("a"); awaitCancellation() }
        val r = runner(llm)
        r.awaitIdle()
        r.send("x")
        r.awaitState { it.streaming == "a" }
        r.stop()
        assertTrue(r.state.value.stopping)
        r.awaitIdle()
        assertFalse(r.state.value.stopping)
    }

    // ------------------------------------------------------------------ duplicate submissions

    @Test
    fun aSecondMessageWhileGeneratingIsRejectedAndNeverQueued() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = provider { n -> gate.await(); emit("reply $n") }
        val r = runner(llm)
        r.awaitIdle()
        assertEquals(SendResult.ACCEPTED, r.send("first"))
        assertEquals(SendResult.BUSY, r.send("second"))
        assertEquals(SendResult.BUSY, r.send("third"))
        assertEquals(ChatRunner.REJECTION, r.state.value.rejection)
        gate.complete(Unit)
        val s = r.awaitState { !it.generating && it.conversation.messages.size == 2 }
        delay(400) // long enough for any hidden queue to fire
        assertEquals(1, calls.get())
        assertEquals(listOf("first", "reply 1"), r.state.value.conversation.messages.map { it.text })
        assertTrue(s.conversation.messages.none { it.text == "second" || it.text == "third" })
        assertTrue(requests.single().messages.none { it.text.contains("second") })
    }

    @Test
    fun aNewMessageWorksNormallyAfterGenerationFinishes() = runBlocking {
        val llm = provider { n -> emit("reply $n") }
        val r = runner(llm)
        r.awaitIdle()
        assertEquals(SendResult.ACCEPTED, r.send("one"))
        r.awaitState { !it.generating && it.conversation.messages.size == 2 }
        assertEquals(SendResult.ACCEPTED, r.send("two"))
        val s = r.awaitState { !it.generating && it.conversation.messages.size == 4 }
        assertEquals(listOf("one", "reply 1", "two", "reply 2"), s.conversation.messages.map { it.text })
        assertEquals(2, calls.get())
        assertEquals(null, s.rejection)
    }

    @Test
    fun manyConcurrentSendsLetExactlyOneThrough() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = provider { n -> gate.await(); emit("reply $n") }
        val r = runner(llm)
        r.awaitIdle()
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger(0)
        val busy = AtomicInteger(0)
        val done = CountDownLatch(50)
        repeat(50) { i ->
            pool.execute {
                start.await()
                when (r.send("msg $i")) {
                    SendResult.ACCEPTED -> accepted.incrementAndGet()
                    SendResult.BUSY -> busy.incrementAndGet()
                    SendResult.EMPTY -> {}
                }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals(1, accepted.get())
        assertEquals(49, busy.get())
        gate.complete(Unit)
        r.awaitState { !it.generating && it.conversation.messages.size == 2 }
        delay(300)
        assertEquals(1, calls.get())
    }

    @Test
    fun retryWhileBusyIsRejectedToo() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = provider { gate.await(); emit("x") }
        val r = runner(llm)
        r.awaitIdle()
        r.send("one")
        // The last message is the user's, so retry looks possible, but the lock still refuses.
        assertEquals(SendResult.BUSY, r.retry())
        gate.complete(Unit)
        r.awaitState { !it.generating && it.conversation.messages.size == 2 }
        assertEquals(1, calls.get())
    }

    @Test
    fun blankMessagesAreIgnoredWithoutTouchingTheLock() = runBlocking {
        val r = runner(provider { emit("x") })
        r.awaitIdle()
        assertEquals(SendResult.EMPTY, r.send("   \n "))
        assertEquals(0, calls.get())
        assertEquals(SendResult.ACCEPTED, r.send("real"))
    }

    @Test
    fun theLockIsReleasedAfterAFailureToo() = runBlocking {
        val llm = provider { n ->
            if (n == 1) throw dev.jarvis.assistant.ai.LlmException(dev.jarvis.assistant.ai.LlmError.Offline) else emit("ok")
        }
        val r = runner(llm)
        r.awaitIdle()
        r.send("one")
        val failed = r.awaitState { !it.generating && it.problem != null }
        assertTrue(failed.problem!!.message.contains("internet"))
        assertEquals(SendResult.ACCEPTED, r.send("two"))
        val finished = r.awaitState { !it.generating && it.conversation.messages.last().role == ChatRole.ASSISTANT }
        assertEquals(4, finished.conversation.messages.size)
    }
}
