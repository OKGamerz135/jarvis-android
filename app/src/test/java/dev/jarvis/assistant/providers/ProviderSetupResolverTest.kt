package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2e: which provider ids get a working setup screen. A non-built-in id never reaches another provider's data. */
class ProviderSetupResolverTest {
    private fun custom(label: String = "Home server") = ProviderDescriptor(
        ProviderId.newCustom(), label, "custom_key_" + java.util.UUID.randomUUID(), "some-model", "help", "notice",
    )

    @Test fun everyBuiltInProviderIsEditableAsItself() {
        val registry = ProviderRegistry.withBuiltIns()
        for (p in AiProvider.entries) {
            val setup = ProviderSetupResolver.resolve(p.toId(), registry)
            assertEquals(ProviderSetup.Editable(p, ProviderDescriptor.of(p)), setup)
        }
    }

    @Test fun aRegisteredCustomProviderIsUnavailableNotEditable() {
        val registry = ProviderRegistry.withBuiltIns()
        val d = custom()
        registry.register(d)
        assertEquals(ProviderSetup.Unavailable(d), ProviderSetupResolver.resolve(d.id, registry))
    }

    @Test fun anUnregisteredCustomIdIsUnknown() {
        val registry = ProviderRegistry.withBuiltIns()
        assertEquals(ProviderSetup.Unknown, ProviderSetupResolver.resolve(ProviderId.newCustom(), registry))
    }

    @Test fun configureRowsKeepTheBuiltInOrderThenCustomProvidersReadOnly() {
        val registry = ProviderRegistry.withBuiltIns()
        val a = custom("A"); val b = custom("B")
        registry.register(a); registry.register(b)
        val rows = ProviderSetupResolver.configureRows(registry)
        assertEquals(AiProvider.entries.map { it.toId() } + listOf(a.id, b.id), rows.map { it.descriptor.id })
        assertEquals(AiProvider.entries.toList(), rows.take(AiProvider.entries.size).map { it.provider })
        assertTrue(rows.drop(AiProvider.entries.size).all { it.provider == null })
    }

    @Test fun withNoCustomProvidersTheRowsAreExactlyTheFiveBuiltIns() {
        val rows = ProviderSetupResolver.configureRows(ProviderRegistry.withBuiltIns())
        assertEquals(AiProvider.entries.toList(), rows.map { it.provider })
        assertNull(rows.firstOrNull { it.provider == null })
    }
}
