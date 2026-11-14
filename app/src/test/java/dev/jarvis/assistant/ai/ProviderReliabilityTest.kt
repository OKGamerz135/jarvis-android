package dev.jarvis.assistant.ai

import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ProviderReliabilityTest {
    @Test
    fun aReadTimeoutIsAProviderTimeoutNotOffline() {
        val e = ioFailure(SocketTimeoutException("Read timed out"))
        assertTrue(e is LlmError.Timeout)
        assertFalse(ErrorMessages.of(e).contains("internet"))
        assertTrue(ErrorMessages.of(e).contains("not your internet"))
    }

    @Test
    fun aConnectTimeoutAndRealNetworkErrorsStayOffline() {
        assertEquals(LlmError.Offline, ioFailure(SocketTimeoutException("connect timed out")))
        assertEquals(LlmError.Offline, ioFailure(ConnectException("refused")))
        assertEquals(LlmError.Offline, ioFailure(java.net.UnknownHostException("x")))
    }

    // ------------------------------------------------------------------ removed provider

    @Test
    fun sambaNovaIsGoneFromTheProviderListAndTheCode() {
        assertTrue(AiProvider.entries.none { it.name.contains("SAMBA", ignoreCase = true) || it.label.contains("Samba", ignoreCase = true) })
        assertTrue(AiProvider.entries.none { it.keyName.contains("samba", ignoreCase = true) })
        for (cls in listOf("dev.jarvis.assistant.ai.SambaNovaProvider", "dev.jarvis.assistant.ai.SambaNovaParsing")) {
            try {
                Class.forName(cls)
                throw AssertionError("$cls still exists")
            } catch (e: ClassNotFoundException) {
                // expected
            }
        }
    }

    @Test
    fun theProviderListIsExactlyTheFiveWorkingOnesInOrder() {
        assertEquals(
            listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.CLOUDFLARE, AiProvider.ZAI),
            AiProvider.entries.toList(),
        )
    }

    @Test
    fun aSavedLedgerRecordForTheRemovedProviderIsIgnoredNotACrash() {
        val json = """[{"provider":"SAMBANOVA","until":1,"source":"REPORTED","at":1,"quota":"x"},
            {"provider":"GROQ","until":2,"source":"REPORTED","at":1,"quota":"y"}]"""
        val decoded = dev.jarvis.assistant.providers.LedgerCodec.decode(json)
        assertTrue(decoded.keys.none { it.name == "SAMBANOVA" })
        assertEquals(listOf(AiProvider.GROQ), decoded.keys.toList())
    }
}
