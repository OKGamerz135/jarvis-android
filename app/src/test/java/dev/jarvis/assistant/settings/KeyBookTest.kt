package dev.jarvis.assistant.settings

import dev.jarvis.assistant.providers.ProviderDescriptor
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.RegisterResult
import dev.jarvis.assistant.providers.toId
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * C2c: saved API keys by provider. The credential names are what Build 46 and earlier already stored, so they are
 * written out here as literals on purpose: if one of them ever changed, every user's saved key would silently vanish.
 */
class KeyBookTest {

    /** A vault that records every call, so "nothing was touched" can be asserted. */
    private class FakeVault(initial: Map<String, String> = emptyMap()) : KeyVault {
        val data = initial.toMutableMap()
        val unreadable = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        var failSave = false
        override fun has(name: String): Boolean { calls += "has:$name"; return name in data }
        override fun save(name: String, secret: String): Boolean {
            calls += "save:$name"
            if (failSave) return false
            data[name] = secret
            return true
        }
        override fun read(name: String): String? { calls += "read:$name"; return if (name in unreadable) null else data[name] }
        override fun remove(name: String): Boolean { calls += "remove:$name"; data.remove(name); return true }
        override fun state(name: String): KeyState {
            calls += "state:$name"
            return when {
                name !in data -> KeyState.MISSING
                name in unreadable -> KeyState.UNREADABLE_NOW
                else -> KeyState.USABLE
            }
        }
    }

    private val legacy = mapOf(
        AiProvider.GEMINI to "gemini_api_key",
        AiProvider.GROQ to "groq_api_key",
        AiProvider.OPENROUTER to "openrouter_api_key",
        AiProvider.CLOUDFLARE to "cloudflare_api_key",
        AiProvider.ZAI to "zai_api_key",
    )
    private val registry = ProviderRegistry.withBuiltIns()
    private val vault = FakeVault()
    private val book = KeyBook(registry, vault)

    // ------------------------------------------------------------------ credential names are exactly the legacy ones

    @Test fun everyBuiltInProviderMapsToItsExactLegacyCredentialName() {
        assertEquals("every built-in is covered by this test", AiProvider.entries.toSet(), legacy.keys)
        for ((provider, name) in legacy) assertEquals(provider.name, name, book.keyNameOf(provider.toId()))
    }

    @Test fun theEnumAndTheRegistryAgreeOnEveryName() {
        for (p in AiProvider.entries) assertEquals(p.name, p.keyName, book.keyNameOf(p.toId()))
    }

    @Test fun savingForABuiltInProviderWritesOnlyUnderItsLegacyName() {
        for ((provider, name) in legacy) {
            val v = FakeVault(); val b = KeyBook(registry, v)
            assertTrue(b.save(provider.toId(), "secret-for-${provider.name}"))
            assertEquals(provider.name, mapOf(name to "secret-for-${provider.name}"), v.data)
        }
    }

    @Test fun keysSavedByEarlierBuildsAreReadBackForTheSameProviders() {
        val v = FakeVault(legacy.entries.associate { (p, n) -> n to "old-key-of-${p.name}" })
        val b = KeyBook(registry, v)
        for (p in AiProvider.entries) {
            assertTrue(p.name, b.has(p.toId()))
            assertEquals(p.name, "old-key-of-${p.name}", b.read(p.toId()))
            assertEquals(p.name, KeyState.USABLE, b.state(p.toId()))
        }
        assertTrue("reading never writes", v.calls.none { it.startsWith("save:") || it.startsWith("remove:") })
    }

    // ------------------------------------------------------------------ no cross-provider contamination

    @Test fun oneProvidersKeyIsNeverVisibleThroughAnother() {
        val v = FakeVault(mapOf("groq_api_key" to "groq-secret")); val b = KeyBook(registry, v)
        assertEquals("groq-secret", b.read(AiProvider.GROQ.toId()))
        for (p in AiProvider.entries - AiProvider.GROQ) {
            assertFalse(p.name, b.has(p.toId())); assertNull(p.name, b.read(p.toId())); assertEquals(p.name, KeyState.MISSING, b.state(p.toId()))
        }
    }

    @Test fun savingOrRemovingOneProviderLeavesEveryOtherKeyAlone() {
        val v = FakeVault(legacy.entries.associate { (p, n) -> n to "key-${p.name}" }); val b = KeyBook(registry, v)
        assertTrue(b.save(AiProvider.GEMINI.toId(), "new-gemini"))
        assertEquals("new-gemini", v.data["gemini_api_key"])
        assertTrue(b.remove(AiProvider.ZAI.toId()))
        for ((p, n) in legacy) if (p != AiProvider.GEMINI && p != AiProvider.ZAI) assertEquals(p.name, "key-${p.name}", v.data[n])
        assertFalse(v.data.containsKey("zai_api_key"))
    }

    @Test fun aFailedSaveIsReportedAndStoresNothing() {
        vault.failSave = true
        assertFalse(book.save(AiProvider.GROQ.toId(), "x".repeat(30)))
        assertTrue(vault.data.isEmpty())
    }

    // ------------------------------------------------------------------ an unregistered provider has no credential

    private fun stranger(): ProviderId = ProviderId.newCustom()

    @Test fun anUnregisteredProviderHasNoNameAndNoKey() {
        val id = stranger()
        assertNull(book.keyNameOf(id))
        assertFalse(book.has(id)); assertNull(book.read(id)); assertEquals(KeyState.MISSING, book.state(id))
    }

    @Test fun anUnregisteredProviderNeverWritesOrDeletesAnything() {
        val v = FakeVault(legacy.values.associateWith { "k" }); val b = KeyBook(registry, v)
        val id = stranger()
        assertFalse(b.save(id, "x".repeat(30)))
        assertFalse(b.remove(id))
        assertEquals(legacy.values.associateWith { "k" }, v.data)
        assertTrue("the vault was not even asked: ${v.calls}", v.calls.isEmpty())
    }

    @Test fun anUnregisteredProviderNeverFallsBackToABuiltInsKey() {
        val v = FakeVault(legacy.values.associateWith { "k" }); val b = KeyBook(registry, v)
        repeat(20) { val id = stranger(); assertNull(b.read(id)); assertFalse(b.has(id)) }
        assertTrue(v.calls.isEmpty())
    }

    @Test fun aRegisteredCustomProviderUsesItsOwnNameAndNeverABuiltInOne() {
        val id = ProviderId.custom(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
        val d = ProviderDescriptor(id, "Mine", "custom_key_a1", "m", "help", "notice")
        assertEquals(RegisterResult.Added, registry.register(d))
        assertTrue(book.save(id, "custom-secret"))
        assertEquals(mapOf("custom_key_a1" to "custom-secret"), vault.data)
        for (p in AiProvider.entries) assertNull(book.read(p.toId()))
        // and removing the provider from the registry makes its key unreachable through the book (the key itself is not deleted here)
        registry.remove(id)
        assertNull(book.read(id)); assertEquals(KeyState.MISSING, book.state(id))
        assertEquals("custom-secret", vault.data["custom_key_a1"])
    }

    // ------------------------------------------------------------------ missing and unreadable keys

    @Test fun anUnreadableKeyIsNotUsableAndIsNotMistakenForMissing() {
        vault.data["groq_api_key"] = "secret"; vault.unreadable += "groq_api_key"
        assertEquals(KeyState.UNREADABLE_NOW, book.state(AiProvider.GROQ.toId()))
        assertNull(book.read(AiProvider.GROQ.toId()))
        assertTrue("it is stored, and was kept", book.has(AiProvider.GROQ.toId()))
        assertTrue(vault.data.containsKey("groq_api_key"))
    }

    @Test fun statesCoverEveryRegisteredProviderAndSayWhatIsTrueForEach() {
        vault.data["gemini_api_key"] = "a"
        vault.data["groq_api_key"] = "b"; vault.unreadable += "groq_api_key"
        val s = book.states()
        assertEquals(AiProvider.entries.map { it.toId() }.toSet(), s.keys)
        assertEquals(KeyState.USABLE, s.getValue(AiProvider.GEMINI.toId()))
        assertEquals(KeyState.UNREADABLE_NOW, s.getValue(AiProvider.GROQ.toId()))
        assertEquals(KeyState.MISSING, s.getValue(AiProvider.OPENROUTER.toId()))
    }

    @Test fun presenceReadsNothingAndOnlyTellsStoredFromNotStored() {
        vault.data["zai_api_key"] = "z"
        val p = book.presence()
        assertEquals(KeyState.USABLE, p.getValue(AiProvider.ZAI.toId()))
        assertEquals(KeyState.MISSING, p.getValue(AiProvider.GEMINI.toId()))
        assertTrue(vault.calls.all { it.startsWith("has:") })
    }

    // ------------------------------------------------------------------ the removed SambaNova key

    @Test fun retiredKeysAreRemovedAndOnlyThose() {
        val v = FakeVault(legacy.values.associateWith { "k" } + ("sambanova_api_key" to "old")); val b = KeyBook(registry, v)
        b.removeRetired()
        assertEquals(legacy.values.associateWith { "k" }, v.data)
        assertEquals(listOf("sambanova_api_key"), KeyBook.RETIRED_KEY_NAMES)
    }

    @Test fun aFailingRetiredKeyCleanupNeverEscapes() {
        val boom = object : KeyVault {
            override fun has(name: String) = false
            override fun save(name: String, secret: String) = false
            override fun read(name: String): String? = null
            override fun remove(name: String): Boolean = throw IllegalStateException("keystore down")
            override fun state(name: String) = KeyState.MISSING
        }
        KeyBook(registry, boom).removeRetired() // must not throw
    }

    // ------------------------------------------------------------------ key-change events

    @Test fun lostReportsOnlyKeysThatWereUsableAndNoLongerAre() {
        val g = AiProvider.GEMINI.toId(); val q = AiProvider.GROQ.toId(); val c = AiProvider.CLOUDFLARE.toId(); val o = AiProvider.OPENROUTER.toId()
        val before = mapOf(g to KeyState.USABLE, q to KeyState.USABLE, c to KeyState.MISSING, o to KeyState.UNREADABLE_NOW)
        val now = mapOf(g to KeyState.USABLE, q to KeyState.UNREADABLE_NOW, c to KeyState.USABLE, o to KeyState.MISSING)
        assertEquals(listOf(q), KeyChanges.lost(before, now))
    }

    @Test fun lostTreatsAProviderMissingFromNowAsNotUsable() {
        val g = AiProvider.GEMINI.toId()
        assertEquals(listOf(g), KeyChanges.lost(mapOf(g to KeyState.USABLE), emptyMap()))
        assertTrue(KeyChanges.lost(emptyMap(), mapOf(g to KeyState.USABLE)).isEmpty())
    }

    @Test fun changedEventsCarryTheProviderIdThatChanged() = runBlocking {
        val got = mutableListOf<ProviderId>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { KeyChanges.events.collect { got += it } }
        KeyChanges.changed(AiProvider.GROQ.toId())
        KeyChanges.changed(AiProvider.ZAI.toId())
        job.cancel()
        assertEquals(listOf(AiProvider.GROQ.toId(), AiProvider.ZAI.toId()), got)
    }
}
