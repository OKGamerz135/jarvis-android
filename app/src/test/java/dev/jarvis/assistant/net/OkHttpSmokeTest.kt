package dev.jarvis.assistant.net

import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * H1a-2b smoke test: the resolved OkHttp artifact runs on a plain JVM in testDebugUnitTest and completes one real
 * HTTP request against a loopback server bound to 127.0.0.1. No TLS, no external network, no app code.
 */
class OkHttpSmokeTest {
    @Test
    fun resolvedOkHttpCompletesOneLoopbackRequest() {
        assertEquals("5.5.1", OkHttp.VERSION)

        val accepted = AtomicInteger(0)
        var requestLine = ""
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            server.use { s ->
                s.accept().use { c ->
                    accepted.incrementAndGet()
                    val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    requestLine = input.readLine().orEmpty()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "ok-smoke"
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nX-Smoke: 1\r\nConnection: close\r\n\r\n"
                    c.getOutputStream().apply { write((head + body).toByteArray(Charsets.ISO_8859_1)); flush() }
                }
            }
        }
        thread.isDaemon = true
        thread.start()

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/smoke").build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("1", response.header("X-Smoke"))
            assertEquals("ok-smoke", response.body.string())
        }
        thread.join(5000)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()

        assertEquals(1, accepted.get())
        assertEquals("GET /smoke HTTP/1.1", requestLine)
    }
}
