package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.CallOptions
import dev.jarvis.assistant.ai.ChatRequest
import dev.jarvis.assistant.ai.LlmProvider
import dev.jarvis.assistant.providers.ProviderDescriptor
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val llmBuilt = CopyOnWriteArrayList<AiProvider>()
    private val registry = ProviderRegistry.withBuiltIns()
    private var settings = JarvisSettings(provider = C, acknowledged = setOf(G, Q, C))
    private var keys = mapOf(G.keyName to "gem-key", Q.keyName to "groq-key", C.keyName to "cf-key")

    /** Keys are stored by credential name, as on the phone; the registry's descriptor says which name an id uses. */
    private fun source() = SettingsProviderSource(
        registry = registry,
        settings = { settings },
        readKey = { id -> val name = registry.get(id)?.keyName ?: return@SettingsProviderSource null; keyReads += name; keys[name] },
        hasKey = { id -> registry.get(id)?.keyName?.let { keys[it] } != null },
        llmFor = { llmBuilt += it; Llm(it.name) },
        ledger = newLedger(),
        savePreferred = { saved += it },
    )

    @Test fun resolvesTheRequestedProviderWithItsOwnKeyAndModelNotThePreferredOnes() = runBlocking {
        settings = settings.withModel(Q, "groq-model-x").withModel(C, "cf-model-y")
        val r = source().resolve(Q.toId()) as Resolved.Ready
        assertEquals(Q.toId(), r.target.provider)
        assertEquals("groq-key", r.target.apiKey)
        assertEquals("groq-model-x", r.target.model)
        assertEquals(listOf(Q.keyName), keyReads)           // the preferred provider's key was never even read
        assertEquals("Groq", (r.llm as Llm).name.let { Q.label })
        assertEquals(Q.name, (r.llm as Llm).name)
    }

    @Test fun nullMeansThePreferredProvider() = runBlocking {
        val r = source().resolve(null) as Resolved.Ready
        assertEquals(C.toId(), r.target.provider)
        assertEquals("cf-key", r.target.apiKey)
    }

    @Test fun blankModelFallsBackToTheProvidersOwnDefault() = runBlocking {
        settings = settings.withModel(Q, "   ")
        assertEquals(Q.defaultModel, (source().resolve(Q.toId()) as Resolved.Ready).target.model)
    }

    @Test fun missingKeyRefusesWithAMessageNamingTheProviderAndSayingNothingWasSentOrSwitched() = runBlocking {
        keys = keys - Q.keyName
        val stop = source().resolve(Q.toId()) as Resolved.Stop
        assertTrue(stop.problem.needsSetup)
        assertTrue(stop.problem.message, stop.problem.message.contains("Groq is not configured"))
        assertTrue(stop.problem.message.contains("Nothing was sent"))
        assertTrue(stop.problem.message.contains("did not switch to another provider"))
    }

    @Test fun blankKeyCountsAsMissing() = runBlocking {
        keys = keys + (Q.keyName to "   ")
        assertTrue(source().resolve(Q.toId()) is Resolved.Stop)
    }

    @Test fun missingConsentRefusesWithoutReadingAnyKey() = runBlocking {
        settings = settings.copy(acknowledged = setOf(G, C))
        val stop = source().resolve(Q.toId()) as Resolved.Stop
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
        assertEquals(C.toId(), snap.preferred)
        assertFalse(snap.failoverEnabled)
        val byProvider = snap.candidates.associateBy { it.provider }
        assertTrue(byProvider.getValue(G.toId()).eligible)
        assertEquals("turned off for failover", byProvider.getValue(Q.toId()).whyNot())
        assertEquals("no API key saved", byProvider.getValue(C.toId()).whyNot())
    }

    @Test fun setPreferredHandsTheProviderToTheSaveFunction() = runBlocking {
        source().setPreferred(Q.toId())
        assertEquals(listOf(Q), saved.toList())
        assertNull(keyReads.firstOrNull())
    }

    // ------------------------------------------------------------------ C2d: ids, descriptors and failing closed

    private fun customDescriptor(label: String = "My endpoint", keyName: String = "custom_key_1") =
        ProviderDescriptor(ProviderId.newCustom(), label, keyName, "some-model", "help", "notice")

    @Test fun everyBuiltInIdResolvesToItsOwnAdapterKeyModelAndLabel() = runBlocking {
        for (p in AiProvider.entries) {
            keys = AiProvider.entries.associate { it.keyName to "key-of-${it.name}" }
            settings = JarvisSettings(provider = G, acknowledged = AiProvider.entries.toSet()).withModel(p, "model-of-${p.name}")
            keyReads.clear(); llmBuilt.clear()
            val r = source().resolve(p.toId()) as Resolved.Ready
            assertEquals(p.name, p.toId(), r.target.provider)
            assertEquals(p.name, p.label, r.target.providerLabel)
            assertEquals(p.name, "key-of-${p.name}", r.target.apiKey)
            assertEquals(p.name, "model-of-${p.name}", r.target.model)
            assertEquals(p.name, p.name, (r.llm as Llm).name)
            assertEquals(p.name, listOf(p), llmBuilt.toList())
            assertEquals(p.name, listOf(p.keyName), keyReads.toList())
        }
    }

    @Test fun anUnregisteredIdFailsClosedWithoutReadingAKeyOrBuildingAnAdapter() = runBlocking {
        settings = JarvisSettings(provider = G, acknowledged = AiProvider.entries.toSet())
        keys = AiProvider.entries.associate { it.keyName to "k" }
        val stop = source().resolve(ProviderId.newCustom()) as Resolved.Stop
        assertTrue(stop.problem.needsSetup)
        assertTrue(stop.problem.message, stop.problem.message.contains("Nothing was sent") && stop.problem.message.contains("did not switch"))
        assertTrue(stop.problem.message, stop.problem.message.contains("isn't known"))
        assertTrue("the message must not name any built-in provider", AiProvider.entries.none { stop.problem.message.contains(it.label) })
        assertTrue("no key may be read for an unknown provider", keyReads.isEmpty())
        assertTrue("no adapter may be built for an unknown provider", llmBuilt.isEmpty())
    }

    @Test fun aRegisteredProviderThatCannotRunYetAlsoFailsClosedAndIsNeverMappedToABuiltIn() = runBlocking {
        val d = customDescriptor()
        registry.register(d)
        settings = JarvisSettings(provider = G, acknowledged = AiProvider.entries.toSet())
        keys = AiProvider.entries.associate { it.keyName to "k" } + (d.keyName to "custom-secret")
        val stop = source().resolve(d.id) as Resolved.Stop
        assertTrue(stop.problem.message, stop.problem.message.contains("My endpoint can't be used for chat yet"))
        assertTrue(stop.problem.message.contains("did not switch to another provider"))
        assertTrue("its key was not read either", keyReads.isEmpty())
        assertTrue(llmBuilt.isEmpty())
    }

    @Test fun nothingIsEverSavedAsPreferredForAProviderThatIsNotBuiltIn() = runBlocking {
        val d = customDescriptor(); registry.register(d)
        for (id in listOf(d.id, ProviderId.newCustom())) {
            val failure = try { source().setPreferred(id); null } catch (e: IllegalArgumentException) { e }
            assertNotNull("must refuse $id", failure)
        }
        assertTrue(saved.isEmpty())
    }

    @Test fun labelsComeFromTheDescriptorAndAnUnknownIdNeverBorrowsAnotherProvidersLabel() {
        val s = source()
        for (p in AiProvider.entries) assertEquals(p.label, s.labelOf(p.toId()))
        val d = customDescriptor("Custom One"); registry.register(d)
        assertEquals("Custom One", s.labelOf(d.id))
        val unknown = s.labelOf(ProviderId.newCustom())
        assertEquals(SettingsProviderSource.UNKNOWN_LABEL, unknown)
        assertTrue(AiProvider.entries.none { it.label == unknown })
    }

    @Test fun theSnapshotOnlyListsProvidersThatHaveTheirOwnKeyConsentAndNeverCustomOnes() = runBlocking {
        val d = customDescriptor(); registry.register(d)
        keys = keys + (d.keyName to "secret")
        val snap = source().snapshot()
        assertEquals(AiProvider.entries.map { it.toId() }, snap.candidates.map { it.provider })
        assertFalse(snap.candidates.any { it.provider == d.id })
        assertEquals(true, snap.candidates.first { it.provider == G.toId() }.hasKey)
        assertEquals(false, snap.candidates.first { it.provider == AiProvider.ZAI.toId() }.hasKey)
    }
}
