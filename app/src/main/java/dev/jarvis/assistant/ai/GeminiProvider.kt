package dev.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class GeminiProvider(private val clock: () -> Instant = { Instant.now() }) : LlmProvider {

    override fun stream(request: ChatRequest): Flow<String> = flow {
        val model = URLEncoder.encode(request.model, "UTF-8")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
        val conn = url.openConnection() as HttpURLConnection
        // If the collector goes away while we're blocked reading, closing the socket unblocks us.
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            // The key goes in a header, never in the URL, so it can't end up in logs or history.
            conn.setRequestProperty("x-goog-api-key", request.apiKey)
            conn.outputStream.use { it.write(GeminiParsing.buildBody(request).toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw LlmException(GeminiParsing.parseError(code, body, request.model, clock()))
            }

            var gotText = false
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val text = GeminiParsing.textOf(payload)
                if (text.isNotEmpty()) {
                    gotText = true
                    emit(text)
                }
            }
            if (!gotText) throw LlmException(LlmError.Blocked("the model returned no text"))
        } catch (e: IOException) {
            throw LlmException(LlmError.Offline)
        } finally {
            cancelHandle?.dispose()
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

object Providers {
    private val gemini = GeminiProvider()

    /** Null means "no adapter for this provider yet". */
    fun forProvider(provider: dev.jarvis.assistant.settings.AiProvider): LlmProvider? = when (provider) {
        dev.jarvis.assistant.settings.AiProvider.GEMINI -> gemini
        dev.jarvis.assistant.settings.AiProvider.GROQ -> null
    }
}
