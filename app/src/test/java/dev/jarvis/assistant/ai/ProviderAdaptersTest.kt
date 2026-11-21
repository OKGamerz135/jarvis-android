package dev.jarvis.assistant.ai

import dev.jarvis.assistant.providers.ProviderDescriptor
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.RegisterResult
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** C3a: only a REGISTERED provider can have an adapter; an id that merely parses never does. */
class ProviderAdaptersTest {
    private class Llm(val name: String) : LlmProvider {
        override fun stream(request: ChatRequest, options: CallOptions): Flow<String> = emptyFlow()
    }
    private class Diag(val name: String) : ProviderDiagnostics {
        override suspend fun checkModel(apiKey: String, model: String) = ModelInfo(model, null, emptyList())
        override suspend fun generateOnce(request: ChatRequest) = ""
    }

    private val llms = AiProvider.entries.associateWith { Llm(it.name) }
    private val diags = AiProvider.entries.associateWith { Diag(it.name) }
    private val registry = ProviderRegistry.withBuiltIns()

    private fun custom(label: String = "Custom One") =
        ProviderDescriptor(ProviderId.newCustom(), label, "custom_key_" + java.util.UUID.randomUUID(), "m", "h", "n")

    private fun adapters(source: CustomAdapterSource = CustomAdapterSource.NONE, reg: ProviderRegistry = registry) =
        ProviderAdapters(reg, { llms.getValue(it) }, { diags.getValue(it) }, source)

    private fun profileAdapter() = OpenAiCompatibleProvider(EndpointProfile.parse("Custom One", "https://api.example.test/v1")!!)

    @Test fun everyBuiltInGetsItsOwnAdaptersAndNeverAnotherProvidersOne() {
        val a = adapters()
        for (p in AiProvider.entries) {
            assertSame(p.name, llms.getValue(p), a.llmFor(p.toId()))
            assertSame(p.name, diags.getValue(p), a.diagnosticsFor(p.toId()))
            for (other in AiProvider.entries - p) assertNotSame(llms.getValue(other), a.llmFor(p.toId()))
        }
    }

    @Test fun aWellFormedCustomIdThatIsNotRegisteredHasNoAdapterAndTheSourceIsNeverAsked() {
        val asked = CopyOnWriteArrayList<ProviderDescriptor>()
        val a = adapters(source = { d -> asked += d; profileAdapter() })
        for (i in 1..5) {
            val id = ProviderId.newCustom()
            assertNull(a.llmFor(id)); assertNull(a.diagnosticsFor(id))
        }
        assertTrue("the source must not be consulted for an unregistered id", asked.isEmpty())
    }

    @Test fun aRegisteredCustomProviderWithNoAdapterSourceHasNoAdapter() {
        val d = custom(); assertEquals(RegisterResult.Added, registry.register(d))
        val a = adapters()
        assertNull(a.llmFor(d.id)); assertNull(a.diagnosticsFor(d.id))
    }

    @Test fun aRegisteredCustomProviderGetsAnAdapterOnlyFromItsSourceForItsOwnDescriptor() {
        val d = custom(); registry.register(d)
        val other = custom("Custom Two"); registry.register(other)
        val mine = profileAdapter()
        val a = adapters(source = { desc -> if (desc.id == d.id) mine else null })
        assertSame(mine, a.llmFor(d.id)); assertSame(mine, a.diagnosticsFor(d.id))
        assertNull("another registered provider does not get it", a.llmFor(other.id))
    }

    @Test fun aRemovedCustomProviderLosesItsAdapter() {
        val d = custom(); registry.register(d)
        val a = adapters(source = { profileAdapter() })
        assertTrue(a.llmFor(d.id) != null)
        registry.remove(d.id)
        assertNull(a.llmFor(d.id)); assertNull(a.diagnosticsFor(d.id))
    }

    @Test fun aSeparateRegistryDoesNotKnowAnotherRegistrysCustomProviders() {
        val d = custom(); registry.register(d)
        val a = adapters({ profileAdapter() }, ProviderRegistry.withBuiltIns())
        assertNull(a.llmFor(d.id))
    }

    @Test fun nothingFallsBackToABuiltInAdapter() {
        val a = adapters(source = { null })
        val d = custom(); registry.register(d)
        val got = a.llmFor(d.id)
        assertNull(got)
        assertTrue(llms.values.none { it === got })
    }
}
