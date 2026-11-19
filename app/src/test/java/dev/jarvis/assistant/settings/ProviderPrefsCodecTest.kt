package dev.jarvis.assistant.settings

import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.toId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The stored provider references. The fixtures below are values as Build 46 and the Phase B builds store them: the
 * old code wrote every provider as its enum name (names joined with "," for the order, string sets for the others).
 * They are written out by hand here, not produced by the new code.
 */
class ProviderPrefsCodecTest {
    private val default = AiProvider.GEMINI
    private val defaultOrder = AiProvider.entries

    private val uuidA = "123e4567-e89b-42d3-a456-426614174000"
    private val uuidB = "9b2e7c1a-4d5f-4e6a-8b3c-1a2b3c4d5e6f"
    private val customA = "custom:$uuidA"
    private val customB = "custom:$uuidB"

    // ---- legacy fixtures --------------------------------------------------------------------------------------

    /** A settings file after the first save of a default install. */
    private val defaults = RawProviderPrefs("GEMINI", emptySet(), "GEMINI,GROQ,OPENROUTER,CLOUDFLARE,ZAI", emptySet())

    /** A typical user: Groq preferred, three providers acknowledged, Z.ai kept out of failover. */
    private val typical = RawProviderPrefs("GROQ", setOf("GEMINI", "GROQ", "CLOUDFLARE"), "GROQ,GEMINI,CLOUDFLARE,OPENROUTER,ZAI", setOf("ZAI"))

    /** Written by an early build that only knew two providers. */
    private val early = RawProviderPrefs("GEMINI", setOf("GEMINI"), "GEMINI,GROQ", null)

    /** Names of a provider that an earlier build had and a later one removed. They no longer exist. */
    private val withRemoved = RawProviderPrefs("SAMBANOVA", setOf("SAMBANOVA", "GROQ"), "GEMINI,SAMBANOVA,GROQ", setOf("MISTRAL"))

    private val nothingStored = RawProviderPrefs(null, null, null, null)

    private fun decode(raw: RawProviderPrefs) = ProviderPrefsCodec.decode(raw, default, defaultOrder)
    private fun encode(raw: RawProviderPrefs, new: ProviderPrefs = decode(raw)) = ProviderPrefsCodec.encode(new, decode(raw), raw)
    private fun asRaw(s: StoredProviderPrefs) = RawProviderPrefs(s.provider, s.acknowledged, s.order, s.disabled)

    // ---- existing data reads as before ------------------------------------------------------------------------

    @Test fun aDefaultInstallReadsAsItAlwaysDid() {
        val p = decode(defaults)
        assertEquals(AiProvider.GEMINI, p.provider)
        assertEquals(emptySet<AiProvider>(), p.acknowledged)
        assertEquals(AiProvider.entries, p.order)
        assertEquals(emptySet<AiProvider>(), p.disabled)
    }

    @Test fun aTypicalBuild46FileReadsAsItAlwaysDid() {
        val p = decode(typical)
        assertEquals(AiProvider.GROQ, p.provider)
        assertEquals(setOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.CLOUDFLARE), p.acknowledged)
        assertEquals(listOf(AiProvider.GROQ, AiProvider.GEMINI, AiProvider.CLOUDFLARE, AiProvider.OPENROUTER, AiProvider.ZAI), p.order)
        assertEquals(setOf(AiProvider.ZAI), p.disabled)
    }

    @Test fun anEarlyPartialOrderIsKeptAsStored() {
        val p = decode(early)
        assertEquals(listOf(AiProvider.GEMINI, AiProvider.GROQ), p.order)
        assertEquals(emptySet<AiProvider>(), p.disabled) // key absent
    }

    @Test fun everyBuiltInProviderKeepsItsOriginalMeaning() {
        for (p in AiProvider.entries) {
            val raw = RawProviderPrefs(p.name, setOf(p.name), p.name, setOf(p.name))
            val d = decode(raw)
            assertEquals(p, d.provider)
            assertEquals(setOf(p), d.acknowledged)
            assertEquals(listOf(p), d.order)
            assertEquals(setOf(p), d.disabled)
        }
    }

    @Test fun nothingStoredAndPartiallyStoredPreferencesStayValid() {
        val none = decode(nothingStored)
        assertEquals(default, none.provider)
        assertEquals(defaultOrder, none.order)
        assertTrue(none.acknowledged.isEmpty() && none.disabled.isEmpty())

        val onlyProvider = decode(RawProviderPrefs("ZAI", null, null, null))
        assertEquals(AiProvider.ZAI, onlyProvider.provider)
        assertEquals(defaultOrder, onlyProvider.order)

        val emptyOrder = decode(RawProviderPrefs("GROQ", emptySet(), "", emptySet()))
        assertEquals("an empty stored order falls back to the default, as before", defaultOrder, emptyOrder.order)
    }

    // ---- what is written is the same text -----------------------------------------------------------------------

    @Test fun newWritesUseTheEnumNameForEveryBuiltInProviderSoNothingChangesOnDisk() {
        for (p in AiProvider.entries) assertEquals(p.name, p.toId().value)
        val w = encode(nothingStored, ProviderPrefs(AiProvider.ZAI, setOf(AiProvider.GROQ), listOf(AiProvider.ZAI, AiProvider.GROQ), setOf(AiProvider.GEMINI)))
        assertEquals("ZAI", w.provider)
        assertEquals(setOf("GROQ"), w.acknowledged)
        assertEquals("ZAI,GROQ", w.order)
        assertEquals(setOf("GEMINI"), w.disabled)
    }

    @Test fun readingThenWritingLegacyDataChangesNothing() {
        for (raw in listOf(defaults, typical, early.copy(disabled = emptySet()))) {
            val w = encode(raw)
            assertEquals(raw.provider, w.provider)
            assertEquals(raw.acknowledged, w.acknowledged)
            assertEquals("the order text is identical", raw.order, w.order)
            assertEquals(raw.disabled, w.disabled)
        }
    }

    @Test fun repeatedReadAndWriteReachesAFixedPointAndNeverChangesMeaning() {
        val fixtures = listOf(
            defaults, typical, early, withRemoved, nothingStored,
            RawProviderPrefs(customA, setOf("GROQ", customA, customB.uppercase()), "GROQ,$customB,GEMINI,$customA", setOf(customB)),
        )
        for (start in fixtures) {
            var raw = start
            val meanings = ArrayList<ProviderPrefs>()
            val writes = ArrayList<StoredProviderPrefs>()
            repeat(5) {
                val w = encode(raw)
                writes += w
                raw = asRaw(w)
                meanings += decode(raw)
            }
            assertEquals("stable after the first write: $start", writes[0], writes[1])
            assertEquals(writes[1], writes[4])
            assertTrue("meaning never changes: $start", meanings.all { it == meanings[0] })
        }
    }

    // ---- unknown, malformed and future ids ------------------------------------------------------------------------

    @Test fun anIdThatIsNotExactlyABuiltInNameIsNeverMappedToABuiltInProvider() {
        val notBuiltIn = listOf("groq", "Groq", " GROQ", "GROQ ", "gemini", "OPENAI", "SAMBANOVA", "MISTRAL", "", "NONE", "custom:oops", "GROQ\n", "GEM", "GEMINI2")
        for (text in notBuiltIn) {
            val p = decode(RawProviderPrefs(text, setOf(text), text, setOf(text)))
            assertEquals("\"$text\" as the preferred provider falls back to the default, never to another one", default, p.provider)
            assertTrue("\"$text\" acknowledged", p.acknowledged.isEmpty())
            assertEquals("\"$text\" order", defaultOrder, p.order)
            assertTrue("\"$text\" disabled", p.disabled.isEmpty())
        }
    }

    @Test fun removedProvidersAreSkippedAndTheRestOfTheDataIsKept() {
        val p = decode(withRemoved)
        assertEquals(default, p.provider)
        assertEquals(setOf(AiProvider.GROQ), p.acknowledged)
        assertEquals(listOf(AiProvider.GEMINI, AiProvider.GROQ), p.order)
        assertTrue(p.disabled.isEmpty())
    }

    @Test fun writingDropsTextThatIsNotAnIdAtAllAsBefore() {
        val w = encode(withRemoved)
        assertEquals("GEMINI,GROQ", w.order)
        assertEquals(setOf("GROQ"), w.acknowledged)
        assertTrue(w.disabled.isEmpty())
        assertEquals("GEMINI", w.provider)
    }

    @Test fun malformedStoredValuesNeverCrashAndNeverInventAProvider() {
        val odd = listOf(",,,", "GROQ,,GEMINI", ",GROQ", "GROQ,", " , ", "GROQ;GEMINI", "GROQ GEMINI", "\u0000", "GROQ,GEMINI,custom:", "x".repeat(5000))
        for (order in odd) {
            val p = decode(RawProviderPrefs("GROQ", setOf("", " ", "GROQ"), order, setOf("", "ZAI")))
            assertTrue(p.order.all { it in AiProvider.entries })
            assertEquals(setOf(AiProvider.GROQ), p.acknowledged)
            assertEquals(setOf(AiProvider.ZAI), p.disabled)
            encode(RawProviderPrefs("GROQ", setOf("", " ", "GROQ"), order, setOf("", "ZAI"))) // must not throw
        }
        assertEquals(listOf(AiProvider.GROQ, AiProvider.GEMINI), decode(RawProviderPrefs(null, null, "GROQ,,GEMINI", null)).order)
    }

    @Test fun whatIsReadAlwaysAppearsVerbatimInTheStoredText() {
        // Seeded random mixes of real names, near misses and custom ids: a provider only comes out if its exact name went in.
        val pool = AiProvider.entries.map { it.name } + listOf("groq", "Groq ", "SAMBANOVA", "", customA, customB, "custom:", "GEMINI,GROQ")
        val random = Random(46)
        repeat(500) {
            val tokens = List(random.nextInt(0, 7)) { pool[random.nextInt(pool.size)] }
            val parts = tokens.joinToString(",").split(",")
            val d = decode(RawProviderPrefs(tokens.firstOrNull(), tokens.toSet(), tokens.joinToString(","), tokens.toSet()))
            for (p in d.acknowledged + d.disabled) assertTrue("${p.name} came from nowhere: $tokens", p.name in tokens)
            for (p in d.order) assertTrue("${p.name} came from nowhere in the order: $tokens", p.name in parts || d.order == defaultOrder)
            if (d.provider != default) assertEquals(tokens.first(), d.provider.name)
        }
    }

    // ---- ids from a future build are carried, not erased -----------------------------------------------------------

    private val withCustom = RawProviderPrefs(
        provider = "GROQ",
        acknowledged = setOf("GEMINI", customA),
        order = "GEMINI,$customA,GROQ,$customB",
        disabled = setOf(customB, "ZAI"),
    )

    @Test fun wellFormedCustomIdsAreNotVisibleToBuiltInOnlyCodeButDoNotBreakIt() {
        val p = decode(withCustom)
        assertEquals(setOf(AiProvider.GEMINI), p.acknowledged)
        assertEquals(listOf(AiProvider.GEMINI, AiProvider.GROQ), p.order)
        assertEquals(setOf(AiProvider.ZAI), p.disabled)
    }

    @Test fun savingSomethingUnrelatedKeepsEveryCustomIdInTheStoredLists() {
        val w = encode(withCustom)
        assertEquals(setOf("GEMINI", customA), w.acknowledged)
        assertEquals("built-ins first, then the custom ids in their original order", "GEMINI,GROQ,$customA,$customB", w.order)
        assertEquals(setOf("ZAI", customB), w.disabled)
        assertEquals("GROQ", w.provider)
    }

    @Test fun changingABuiltInSettingKeepsTheCustomIdsToo() {
        val before = decode(withCustom)
        val w = encode(withCustom, before.copy(acknowledged = before.acknowledged + AiProvider.GROQ, disabled = emptySet(), order = listOf(AiProvider.GROQ, AiProvider.GEMINI)))
        assertEquals(setOf("GEMINI", "GROQ", customA), w.acknowledged)
        assertEquals("GROQ,GEMINI,$customA,$customB", w.order)
        assertEquals("a custom id the user did not touch stays disabled", setOf(customB), w.disabled)
    }

    @Test fun aStoredCustomPreferredProviderSurvivesUnrelatedSavesButNotAnExplicitChange() {
        val raw = RawProviderPrefs(customA, null, null, null)
        val before = decode(raw)
        assertEquals("until custom providers exist in the app, a custom preferred id reads as the default", default, before.provider)

        assertEquals("an unrelated save keeps it", customA, encode(raw).provider)
        assertEquals("choosing another provider replaces it", "GROQ", encode(raw, before.copy(provider = AiProvider.GROQ)).provider)
    }

    @Test fun customIdsAreStoredInTheirCanonicalFormOnceAndRepeatedSavesDoNotGrowTheLists() {
        val raw = RawProviderPrefs("GEMINI", setOf(customA.uppercase().replace("CUSTOM:", "custom:"), customA), "GEMINI,$customA,$customA,GROQ", setOf(customA))
        val first = encode(raw)
        assertEquals(setOf(customA), first.acknowledged)
        assertEquals("GEMINI,GROQ,$customA", first.order)
        var raw2 = asRaw(first)
        repeat(4) { raw2 = asRaw(encode(raw2)) }
        assertEquals(first, StoredProviderPrefs(raw2.provider!!, raw2.acknowledged!!, raw2.order!!, raw2.disabled!!))
    }

    @Test fun aCustomIdThatIsNotWellFormedIsDroppedLikeAnyOtherJunk() {
        val raw = RawProviderPrefs("GEMINI", setOf("custom:", "custom:xyz", customA + "x"), "GEMINI,custom:,custom:xyz", setOf("CUSTOM:$uuidA"))
        val w = encode(raw)
        assertTrue(w.acknowledged.isEmpty())
        assertEquals("GEMINI", w.order)
        assertTrue(w.disabled.isEmpty())
        assertFalse(ProviderId.parse("custom:xyz") != null)
    }
}
