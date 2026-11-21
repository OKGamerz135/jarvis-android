package dev.jarvis.assistant.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** C3-pre: everything gets the one shared registry from its owner, or fails loudly. There is no private fallback. */
class ProviderRegistryOwnerTest {
    private class FakeOwner : ProviderRegistryOwner {
        override val providerRegistry: ProviderRegistry = ProviderRegistry.withBuiltIns()
    }

    @Test fun anOwnerIsReturnedAsItselfAndKeepsHandingOutTheSameRegistry() {
        val owner = FakeOwner()
        val a = requireRegistryOwner(owner).providerRegistry
        val b = requireRegistryOwner(owner).providerRegistry
        assertSame(owner, requireRegistryOwner(owner))
        assertSame(a, b)
        assertSame(owner.providerRegistry, a)
    }

    @Test fun twoConsumersOfTheSameOwnerSeeTheSameProvidersIncludingLaterOnes() {
        val owner = FakeOwner()
        val chat = requireRegistryOwner(owner).providerRegistry
        val settings = requireRegistryOwner(owner).providerRegistry
        val d = ProviderDescriptor(ProviderId.newCustom(), "Shared", "shared_key", "m", "h", "n")
        assertEquals(RegisterResult.Added, chat.register(d))
        assertEquals(d, settings.get(d.id))
    }

    @Test fun separateOwnersAreIsolatedFromEachOther() {
        val one = FakeOwner(); val two = FakeOwner()
        val d = ProviderDescriptor(ProviderId.newCustom(), "Only one", "only_one_key", "m", "h", "n")
        one.providerRegistry.register(d)
        assertTrue(two.providerRegistry.get(d.id) == null)
    }

    @Test fun somethingThatIsNotAnOwnerFailsLoudly() {
        for (bad in listOf<Any?>(null, "not an owner", Any(), 42)) {
            try { requireRegistryOwner(bad); fail("accepted $bad") } catch (e: NoProviderRegistryOwnerException) { }
        }
    }
}
