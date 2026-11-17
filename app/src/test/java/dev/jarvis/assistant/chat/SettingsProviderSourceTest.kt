package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The single place that decides what a request is sent to: consent, key and model, for exactly the provider asked. */
class SettingsProviderSourceTest {
    private val G = AiProvider.GEMINI
    private val Q = AiProvider.GROQ
    private val C = AiProvider.CLOUDFLARE

    private class Llm(val name: String) : LlmProvider {
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = flowOf(name)
    }

    private val keyReads = CopyOnWriteArrayList<String>()
    private val saved = CopyOnWriteArrayList<AiProvider>()
    private var settings = JarvisSettings(provider = C, acknowledged = setOf(G, Q, C))
    private var keys = mapOf(G.keyName to "gem-key", Q.keyName to "groq-key", C.keyName to "cf-key")

    private fun source() = SettingsProviderSource(
        settings = { settings },
        readKey = { name -> keyReads += name; keys[name] },
        hasKey = { name -> keys[name] != null },
        llmFor = { Llm(it.name) },
        ledger = newLedger(),
        savePreferred = { saved += it },
    )

    @Test fun resolvesTheRequestedProviderWithItsOwnKeyAndModelNotThePreferredOnes() = runBlocking {
        settings = settings.withModel(Q, "groq-model-x").withModel(C, "cf-model-y")
        val r = source().resolve(Q) as Resolved.Ready
        assertEquals(Q, r.target.provider)
        assertEquals("groq-key", r.target.apiKey)
        assertEquals("groq-model-x", r.target.model)
        assertEquals(listOf(Q.keyName), keyReads)           // the preferred provider's key was never even read
        assertEquals("Groq", (r.llm as Llm).name.let { Q.label })
        assertEquals(Q.name, (r.llm as Llm).name)
    }

    @Test fun nullMeansThePreferredProvider() = runBlocking {
        val r = source().resolve(null) as Resolved.Ready
        assertEquals(C, r.target.provider)
        assertEquals("cf-key", r.target.apiKey)
    }

    @Test fun blankModelFallsBackToTheProvidersOwnDefault() = runBlocking {
        settings = settings.withModel(Q, "   ")
        assertEquals(Q.defaultModel, (source().resolve(Q) as Resolved.Ready).target.model)
    }

    @Test fun missingKeyRefusesWithAMessageNamingTheProviderAndSayingNothingWasSentOrSwitched() = runBlocking {
        keys = keys - Q.keyName
        val stop = source().resolve(Q) as Resolved.Stop
        assertTrue(stop.problem.needsSetup)
        assertTrue(stop.problem.message, stop.problem.message.contains("Groq is not configured"))
        assertTrue(stop.problem.message.contains("Nothing was sent"))
        assertTrue(stop.problem.message.contains("did not switch to another provider"))
    }

    @Test fun blankKeyCountsAsMissing() = runBlocking {
        keys = keys + (Q.keyName to "   ")
        assertTrue(source().resolve(Q) is Resolved.Stop)
    }

    @Test fun missingConsentRefusesWithoutReadingAnyKey() = runBlocking {
        settings = settings.copy(acknowledged = setOf(G, C))
        val stop = source().resolve(Q) as Resolved.Stop
        assertTrue(stop.problem.needsSetup)
        assertTrue(stop.problem.message.contains("Groq"))
        assertTrue(stop.problem.message.contains("did not switch to another provider"))
        assertTrue("no key may be read before consent", keyReads.isEmpty())
    }

    @Test fun anUnconfiguredPreferredProviderNeverFallsBackToAConfiguredOne() = runBlocking {
        settings = settings.copy(provider = Q)
        keys = keys - Q.keyName                               // preferred has no key, the others are ready
        val stop = source().resolve(null) as Resolved.Stop
        assertTrue(stop.problem.message.contains("Groq"))
        assertEquals("only the preferred provider's key was looked at", listOf(Q.keyName), keyReads)
    }

    @Test fun snapshotReportsKeyAndConsentPerProvider() = runBlocking {
        settings = settings.copy(acknowledged = setOf(G, Q), failoverEnabled = false, disabledProviders = setOf(Q))
        keys = keys - C.keyName
        val snap = source().snapshot()
        assertEquals(C, snap.preferred)
        assertFalse(snap.failoverEnabled)
        val byProvider = snap.candidates.associateBy { it.provider }
        assertTrue(byProvider.getValue(G).eligible)
        assertEquals("turned off for failover", byProvider.getValue(Q).whyNot())
        assertEquals("no API key saved", byProvider.getValue(C).whyNot())
    }

    @Test fun setPreferredHandsTheProviderToTheSaveFunction() = runBlocking {
        source().setPreferred(Q)
        assertEquals(listOf(Q), saved.toList())
        assertNull(keyReads.firstOrNull())
    }
}
