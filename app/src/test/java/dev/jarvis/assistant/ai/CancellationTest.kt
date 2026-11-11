package dev.jarvis.assistant.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proves the HTTP request itself is torn down on cancel, using a server that goes silent mid-stream, like a model
 * that is "thinking". Before the fix the cancel only completed when the 60 second read timeout expired.
 */
class CancellationTest {
    private enum class Mode { NOTHING, HEADERS_ONLY, ONE_CHUNK }

    private lateinit var server: ServerSocket
    private val clientClosed = AtomicBoolean(false)
    @Volatile private var mode = Mode.ONE_CHUNK
    @Volatile private var chunk = ""

    @Before
    fun start() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) { break }
                Thread {
                    try {
                        val input = socket.getInputStream()
                        // Read the request head, then go on to answer (or not) and finally stay silent.
                        val tail = StringBuilder()
                        while (tail.toString() != "\r\n\r\n") {
                            val c = input.read()
                            if (c < 0) return@Thread
                            tail.append(c.toChar())
                            if (tail.length > 4) tail.deleteCharAt(0)
                        }
                        Thread.sleep(150) // let the request body arrive
                        val out = socket.getOutputStream()
                        if (mode != Mode.NOTHING) {
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            if (mode == Mode.ONE_CHUNK) out.write(chunk.toByteArray())
                            out.flush()
                        }
                        // Silence. Wait until the client hangs up.
                        socket.soTimeout = 20_000
                        while (input.read() >= 0) { /* drain */ }
                        clientClosed.set(true)
                    } catch (e: Exception) {
                        clientClosed.set(true)
                    } finally {
                        socket.close()
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun stop() = server.close()

    private val request = ChatRequest("openai/gpt-oss-120b", "KEY", "sys", listOf(ChatMessage(ChatRole.USER, "hi")))
    private val base get() = "http://127.0.0.1:${server.localPort}"

    private fun runCancelTest(provider: LlmProvider, waitForChunk: Boolean) = runBlocking {
        val gotChunk = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            provider.stream(request, CallOptions()).collect { gotChunk.complete(Unit) }
        }
        if (waitForChunk) withTimeout(5000) { gotChunk.await() } else kotlinx.coroutines.delay(600)
        val started = System.currentTimeMillis()
        withTimeout(5000) { job.cancelAndJoin() } // would take ~60 s without the fix
        val took = System.currentTimeMillis() - started
        assertTrue("cancel took $took ms", took < 2000)
        // The server sees the socket close: the request is really gone, not merely ignored.
        val deadline = System.currentTimeMillis() + 3000
        while (!clientClosed.get() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(25)
        assertTrue("the HTTP connection was not closed", clientClosed.get())
    }

    @Test
    fun groqCancelDuringSilentThinkingClosesTheConnection() {
        mode = Mode.HEADERS_ONLY
        runCancelTest(GroqProvider(baseUrl = base), waitForChunk = false)
    }

    @Test
    fun groqCancelAfterPartialTextClosesTheConnection() {
        mode = Mode.ONE_CHUNK
        chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
        runCancelTest(GroqProvider(baseUrl = base), waitForChunk = true)
    }

    @Test
    fun geminiCancelDuringSilentThinkingClosesTheConnection() {
        mode = Mode.HEADERS_ONLY
        runCancelTest(GeminiProvider(baseUrl = base), waitForChunk = false)
    }

    @Test
    fun geminiCancelAfterPartialTextClosesTheConnection() {
        mode = Mode.ONE_CHUNK
        chunk = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}\n\n"
        runCancelTest(GeminiProvider(baseUrl = base), waitForChunk = true)
    }

    @Test
    fun cancelBeforeTheServerEvenAnswersClosesTheConnection() {
        mode = Mode.NOTHING
        runCancelTest(GroqProvider(baseUrl = base), waitForChunk = false)
    }
}
