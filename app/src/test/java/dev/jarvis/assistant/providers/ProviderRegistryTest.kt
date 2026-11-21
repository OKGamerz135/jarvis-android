package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ProviderRegistryTest {

    private fun custom(label: String = "My endpoint", keyName: String = "custom_key_" + java.util.UUID.randomUUID(), id: ProviderId = ProviderId.newCustom()) =
        ProviderDescriptor(id, label, keyName, "some-model", "help", "notice")

    // ------------------------------------------------------------------ built-ins

    @Test fun everyExistingBuiltInProviderIsRegisteredInEnumOrderWithItsOwnMetadata() {
        val registry = ProviderRegistry.withBuiltIns()
        val all = registry.all()
        assertEquals(AiProvider.entries.map { it.toId() }, all.map { it.id })
        for (p in AiProvider.entries) {
            val d = registry.get(p.toId())!!
            assertEquals(p.label, d.label)
            assertEquals(p.keyName, d.keyName)
            assertEquals(p.defaultModel, d.defaultModel)
            assertEquals(p.keyHelp, d.keyHelp)
            assertEquals(p.notice, d.notice)
            assertTrue(d.isBuiltIn)
            assertTrue(registry.contains(p.toId()))
        }
        assertTrue(registry.custom().isEmpty())
    }

    @Test fun builtInKeyNamesAreAllDifferent() {
        val names = ProviderRegistry.withBuiltIns().all().map { it.keyName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun aBuiltInCannotBeOverwrittenOrReRegistered() {
        val registry = ProviderRegistry.withBuiltIns()
        val original = registry.get(AiProvider.GROQ.toId())!!
        val impostor = ProviderDescriptor(AiProvider.GROQ.toId(), "Not Groq", "other_key", "m", "h", "n")

        val result = registry.register(impostor)

        assertEquals(RegisterResult.AlreadyRegistered(original), result)
        assertSame(original, registry.get(AiProvider.GROQ.toId()))
        assertEquals(AiProvider.entries.size, registry.all().size)
    }

    @Test fun aBuiltInCannotBeRemoved() {
        val registry = ProviderRegistry.withBuiltIns()
        for (p in AiProvider.entries) assertEquals(RemoveResult.BuiltInProtected, registry.remove(p.toId()))
        assertEquals(AiProvider.entries.map { it.toId() }, registry.all().map { it.id })
    }

    // ------------------------------------------------------------------ custom providers

    @Test fun aCustomProviderCanBeRegisteredResolvedListedAndRemoved() {
        val registry = ProviderRegistry.withBuiltIns()
        val d = custom("Home server")

        assertEquals(RegisterResult.Added, registry.register(d))
        assertSame(d, registry.get(d.id))
        assertTrue(registry.contains(d.id))
        assertEquals(listOf(d), registry.custom())
        assertEquals(AiProvider.entries.size + 1, registry.all().size)
        assertEquals(d, registry.all().last())
        assertFalse(d.isBuiltIn)
        assertNull("a custom id never maps to an enum entry", d.id.toAiProviderOrNull())

        assertEquals(RemoveResult.Removed, registry.remove(d.id))
        assertNull(registry.get(d.id))
        assertFalse(registry.contains(d.id))
        assertTrue(registry.custom().isEmpty())
        assertEquals(AiProvider.entries.size, registry.all().size)
    }

    @Test fun listingPutsBuiltInsFirstThenCustomProvidersInRegistrationOrder() {
        val registry = ProviderRegistry.withBuiltIns()
        val a = custom("A"); val b = custom("B"); val c = custom("C")
        registry.register(b); registry.register(a); registry.register(c)
        assertEquals(AiProvider.entries.map { it.toId() } + listOf(b.id, a.id, c.id), registry.all().map { it.id })
        assertEquals(listOf(b, a, c), registry.custom())
    }

    @Test fun registeringTheSameIdTwiceKeepsTheFirstRegistrationUnchanged() {
        val registry = ProviderRegistry.withBuiltIns()
        val first = custom("First")
        val second = ProviderDescriptor(first.id, "Second", "another_key", "m2", "h2", "n2")
        assertEquals(RegisterResult.Added, registry.register(first))

        val result = registry.register(second)

        assertEquals(RegisterResult.AlreadyRegistered(first), result)
        assertSame(first, registry.get(first.id))
        assertEquals(1, registry.custom().size)
        // The refused one left no trace: its key name is free again.
        assertEquals(RegisterResult.Added, registry.register(custom("Third", keyName = "another_key")))
    }

    @Test fun registeringTheExactSameDescriptorAgainIsAlsoReportedAsADuplicate() {
        val registry = ProviderRegistry.withBuiltIns()
        val d = custom()
        registry.register(d)
        assertEquals(RegisterResult.AlreadyRegistered(d), registry.register(d))
        assertEquals(1, registry.custom().size)
    }

    @Test fun aKeyNameAlreadyInUseIsRefusedSoNoProviderCanReadAnothersKey() {
        val registry = ProviderRegistry.withBuiltIns()
        // A custom provider pointed at Groq's saved key.
        val steal = custom("Looks harmless", keyName = AiProvider.GROQ.keyName)
        assertEquals(RegisterResult.KeyNameInUse(AiProvider.GROQ.toId()), registry.register(steal))
        assertNull(registry.get(steal.id))

        // Two custom providers cannot share one either.
        val first = custom(keyName = "shared_slot")
        registry.register(first)
        assertEquals(RegisterResult.KeyNameInUse(first.id), registry.register(custom(keyName = "shared_slot")))
        assertEquals(1, registry.custom().size)
    }

    @Test fun aKeyNameIsFreeAgainAfterItsProviderIsRemoved() {
        val registry = ProviderRegistry.withBuiltIns()
        val first = custom(keyName = "slot")
        registry.register(first)
        registry.remove(first.id)
        assertEquals(RegisterResult.Added, registry.register(custom(keyName = "slot")))
    }

    @Test fun removingOneProviderLeavesEveryOtherRegistrationAndTheirOrderAlone() {
        val registry = ProviderRegistry.withBuiltIns()
        val a = custom("A"); val b = custom("B"); val c = custom("C")
        listOf(a, b, c).forEach { registry.register(it) }
        val groq = registry.get(AiProvider.GROQ.toId())

        assertEquals(RemoveResult.Removed, registry.remove(b.id))

        assertEquals(listOf(a, c), registry.custom())
        assertSame(a, registry.get(a.id))
        assertSame(c, registry.get(c.id))
        assertNull(registry.get(b.id))
        assertSame(groq, registry.get(AiProvider.GROQ.toId()))
        assertEquals(AiProvider.entries.size + 2, registry.all().size)
    }

    @Test fun anIdCanBeRegisteredAgainAfterItWasRemovedAndItKeepsItsIdentity() {
        val registry = ProviderRegistry.withBuiltIns()
        val d = custom("One")
        registry.register(d); registry.remove(d.id)
        val again = ProviderDescriptor(d.id, "One, renamed", d.keyName, "m", "h", "n")
        assertEquals(RegisterResult.Added, registry.register(again))
        assertSame(again, registry.get(d.id))
    }

    // ------------------------------------------------------------------ unknown and invalid ids

    @Test fun anUnknownIdGivesNullFalseAndNotFoundAndChangesNothing() {
        val registry = ProviderRegistry.withBuiltIns()
        val unknown = ProviderId.newCustom()
        assertNull(registry.get(unknown))
        assertFalse(registry.contains(unknown))
        assertEquals(RemoveResult.NotFound, registry.remove(unknown))
        assertEquals(AiProvider.entries.size, registry.all().size)
    }

    @Test fun anInvalidStoredIdNeverReachesTheRegistryBecauseItCannotBeBuilt() {
        // The only way to get a ProviderId from stored text is parse(), and it refuses these.
        val registry = ProviderRegistry.withBuiltIns()
        for (text in listOf("", "groq", "custom:", "custom:oops", "UNKNOWN", null)) {
            val id = ProviderId.parse(text)
            assertNull("\"$text\"", id)
        }
        assertEquals(AiProvider.entries.size, registry.all().size)
    }

    @Test fun aDescriptorNeedsALabelAndAKeyName() {
        for ((label, key) in listOf("" to "k", "  " to "k", "L" to "", "L" to " ")) {
            try { ProviderDescriptor(ProviderId.newCustom(), label, key, "m", "h", "n"); fail("accepted label=\"$label\" key=\"$key\"") } catch (e: IllegalArgumentException) { }
        }
    }

    // ------------------------------------------------------------------ independence, snapshots, threads

    @Test fun eachRegistryIsIndependentThereIsNoSharedGlobal() {
        val a = ProviderRegistry.withBuiltIns()
        val b = ProviderRegistry.withBuiltIns()
        assertNotSame(a, b)
        val d = custom()
        a.register(d)
        assertNull(b.get(d.id))
        assertEquals(AiProvider.entries.size, b.all().size)
    }

    @Test fun aListThatWasReturnedDoesNotChangeWhenTheRegistryChangesLater() {
        val registry = ProviderRegistry.withBuiltIns()
        val before = registry.all()
        val d = custom()
        registry.register(d)
        assertEquals(AiProvider.entries.size, before.size)
        registry.remove(d.id)
        assertEquals(AiProvider.entries.size, before.size)
    }

    @Test fun manyThreadsRegisteringTheSameIdAddItExactlyOnceAndLoseNoOtherRegistration() {
        repeat(30) { round ->
            val registry = ProviderRegistry.withBuiltIns()
            val shared = ProviderId.newCustom()
            val results = CopyOnWriteArrayList<RegisterResult>()
            val errors = CopyOnWriteArrayList<Throwable>()
            val start = CyclicBarrier(8)
            val own = List(8) { custom("own $it") }
            val threads = List(8) { i ->
                thread {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        results += registry.register(ProviderDescriptor(shared, "shared $i", "shared_key_$i", "m", "h", "n"))
                        registry.register(own[i])
                    } catch (t: Throwable) { errors += t }
                }
            }
            threads.forEach { it.join(10_000) }
            assertTrue("round $round: $errors", errors.isEmpty())
            assertEquals("round $round", 1, results.count { it == RegisterResult.Added })
            // 1 shared registration + the 8 providers of their own: nothing lost, no duplicates.
            assertEquals("round $round", AiProvider.entries.size + 1 + 8, registry.all().size)
            for (d in own) assertSame("round $round", d, registry.get(d.id))
        }
    }

    // ------------------------------------------------------------------ C3-pre: label uniqueness

    private fun registryWith(vararg labels: String): ProviderRegistry {
        val r = ProviderRegistry.withBuiltIns()
        for (l in labels) assertEquals(RegisterResult.Added, r.register(custom(l)))
        return r
    }

    @Test fun aLabelThatMatchesABuiltInIsRefusedHoweverItIsSpelled() {
        for (l in listOf("Groq", "groq", " GROQ ", "G r o q".replace(" ", ""), "Google  Gemini", "google gemini", "\uFF27roq", "Gro\u200Bq", "Z.ai\tGLM", "cloudflare   workers ai")) {
            val r = ProviderRegistry.withBuiltIns()
            val result = r.register(custom(l))
            assertTrue("'$l' gave $result", result is RegisterResult.LabelInUse)
            assertTrue(r.custom().isEmpty())
        }
        assertEquals(RegisterResult.LabelInUse(AiProvider.GROQ.toId()), ProviderRegistry.withBuiltIns().register(custom("  gRoQ")))
    }

    @Test fun aLabelThatMatchesACustomOneIsRefusedAndNothingChanges() {
        val r = registryWith("Home server")
        val first = r.custom().single()
        for (l in listOf("home server", "HOME  SERVER", " Home server ", "Home\u00A0server")) {
            assertEquals("for '$l'", RegisterResult.LabelInUse(first.id), r.register(custom(l)))
        }
        assertEquals(listOf(first), r.custom())
    }

    @Test fun differentLabelsAreStillAccepted() {
        val r = registryWith("Home server", "Home server 2", "Work", "Groq proxy")
        assertEquals(4, r.custom().size)
    }

    @Test fun aLabelCanBeReusedOnceItsOwnerIsRemoved() {
        val r = registryWith("Temp")
        val gone = r.custom().single()
        assertEquals(RemoveResult.Removed, r.remove(gone.id))
        assertEquals(RegisterResult.Added, r.register(custom("temp")))
    }

    @Test fun idThenKeyNameThenLabelDecideWhichRefusalIsReported() {
        val r = registryWith("Taken")
        val taken = r.custom().single()
        // id first
        assertEquals(RegisterResult.AlreadyRegistered(taken), r.register(custom("Taken", id = taken.id)))
        // key name before label
        assertEquals(RegisterResult.KeyNameInUse(AiProvider.GROQ.toId()), r.register(custom("taken", keyName = AiProvider.GROQ.keyName)))
        // label last
        assertEquals(RegisterResult.LabelInUse(taken.id), r.register(custom("TAKEN")))
    }

    @Test fun aLabelWithNoVisibleCharacterIsRejected() {
        for (l in listOf("", " ", "\t\n", "\u200B", "\u200B\u200D\uFEFF", " \u200B ", "\u3164", "\u2800", "\u00A0\u2060")) {
            try { custom(l); fail("accepted '${l.map { it.code }}'") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun aVisibleLabelThatContainsFormattingCharactersIsAccepted() {
        for (l in listOf("Pro\u200Dvider", "\u200BHome", "Home\u200B", "\uD83D\uDC68\u200D\uD83D\uDCBB dev box", "\uD83D\uDE00", "家庭服务器")) {
            assertEquals("for '$l'", l, custom(l).label)
        }
    }

    @Test fun normalizingTreatsInvisibleCharactersCaseAndSpacingAsTheSameLabel() {
        assertEquals(ProviderLabels.normalize("Groq"), ProviderLabels.normalize(" G\u200Broq  "))
        assertEquals(ProviderLabels.normalize("a b"), ProviderLabels.normalize("A\u00A0\u00A0 B"))
        assertTrue(ProviderLabels.normalize("Groq") != ProviderLabels.normalize("Groq2"))
    }

    // ------------------------------------------------------------------ C3-pre review fix: invisible fillers inside visible labels

    /** Characters the label policy calls invisible that are NOT Unicode "format" characters: fillers and blank glyphs. */
    private val fillers = listOf("\u115F", "\u1160", "\u2800", "\u3164", "\uFFA0", "\u17B4", "\u17B5", "\u034F", "\uFE0F", "\u180B")

    @Test fun aFillerInsideAVisibleLabelCannotDisguiseADuplicateOfABuiltIn() {
        for (f in fillers) for (l in listOf("Gro${f}q", "${f}Groq", "Groq$f", "G${f}${f}r${f}o${f}q", "Google${f} Gemini", "Google $f Gemini")) {
            val r = ProviderRegistry.withBuiltIns()
            val result = r.register(custom(l))
            assertTrue("'${l.map { it.code.toString(16) }}' gave $result", result is RegisterResult.LabelInUse)
            assertTrue(r.custom().isEmpty())
        }
    }

    @Test fun aFillerInsideAVisibleLabelCannotDisguiseADuplicateOfACustomProvider() {
        for (f in fillers) {
            val r = registryWith("Home server")
            val first = r.custom().single()
            for (l in listOf("Home${f} server", "Home ${f} server", "${f}Home server${f}", "H${f}ome${f} s${f}erver")) {
                assertEquals("'${l.map { it.code.toString(16) }}'", RegisterResult.LabelInUse(first.id), r.register(custom(l)))
            }
            assertEquals(listOf(first), r.custom())
        }
    }

    @Test fun normalizationLeavesNoInvisibleCharacterBehindForAnyCodePoint() {
        // Policy consistency, over every code point: anything the label policy calls invisible must vanish from the
        // comparison form (or become a plain space, for whitespace), so it can never make a duplicate look different.
        var invisible = 0
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp !in 0xD800..0xDFFF) {
                val one = String(Character.toChars(cp))
                if (!ProviderLabels.hasVisibleCharacter(one)) {
                    invisible++
                    val key = ProviderLabels.normalize("Gro${one}q")
                    assertTrue("U+%04X left '%s'".format(cp, key.map { it.code.toString(16) }), key == "groq" || key == "gro q")
                }
            }
            cp++
        }
        assertTrue(invisible > 100)
    }

    @Test fun legitimateCharactersAreNotStrippedFromTheComparisonForm() {
        // Real Hangul, CJK, accents, non-blank Braille and plain emoji must still count, or distinct labels would collide.
        assertEquals("\uD55C\uAD6D", ProviderLabels.normalize("\uD55C\uAD6D"))
        assertEquals("\u5BB6\u5EAD", ProviderLabels.normalize("\u5BB6\u5EAD"))
        assertEquals("caf\u00E9", ProviderLabels.normalize("Cafe\u0301"))
        assertEquals("\u2801", ProviderLabels.normalize("\u2801"))
        assertEquals("\u1100", ProviderLabels.normalize("\u1100"))
        assertEquals("\uD83D\uDE00", ProviderLabels.normalize("\uD83D\uDE00"))
        assertTrue(ProviderLabels.normalize("\u5BB6") != ProviderLabels.normalize("\u5BB6\u5EAD"))
        assertTrue(ProviderLabels.normalize("\u2801") != ProviderLabels.normalize("\u2802"))
    }

    @Test fun fullWidthAndWhitespaceFormsStillCollideAndRealDifferencesDoNot() {
        assertEquals(ProviderLabels.normalize("Groq"), ProviderLabels.normalize("\uFF27\uFF52\uFF4F\uFF51"))
        assertEquals("home server", ProviderLabels.normalize("  Home \t\u00A0 Server \n"))
        assertTrue(ProviderLabels.normalize("Home server") != ProviderLabels.normalize("Homeserver"))
        assertTrue(ProviderLabels.normalize("Groq") != ProviderLabels.normalize("Groq 2"))
    }

    @Test fun fillerOnlyAndFillerPlusWhitespaceLabelsAreRejectedButVisibleOnesWithFillersAreNot() {
        for (f in fillers) {
            for (l in listOf(f, " $f ", "$f$f", "\u200B$f")) {
                try { custom(l); fail("accepted '${l.map { it.code.toString(16) }}'") } catch (e: IllegalArgumentException) { }
            }
            assertEquals("A${f}B", custom("A${f}B").label) // a visible label is accepted; only its comparison form drops the filler
        }
        assertEquals("\uD83D\uDE00\uFE0F", custom("\uD83D\uDE00\uFE0F").label)
    }

    @Test fun aBlankGlyphStandingInForASpaceCollidesWithTheSpacedLabelButRealWhitespaceDoesNot() {
        val r = registryWith("Home server")
        val first = r.custom().single()
        for (g in listOf("\u115F", "\u1160", "\u2800", "\u3164", "\uFFA0")) {
            assertEquals(RegisterResult.LabelInUse(first.id), r.register(custom("Home${g}server")))
            assertEquals(RegisterResult.LabelInUse(AiProvider.GROQ.toId()), ProviderRegistry.withBuiltIns().register(custom("Gro${g}q")))
            assertEquals(RegisterResult.LabelInUse(AiProvider.GEMINI.toId()), ProviderRegistry.withBuiltIns().register(custom("Google${g}Gemini")))
        }
        assertEquals(RegisterResult.Added, r.register(custom("Homeserver")))
        assertEquals(listOf(first), r.custom().take(1))
    }

    @Test fun comparisonKeysAlwaysIncludeTheCanonicalForm() {
        for (l in listOf("Groq", " Home\u3164server ", "A\u200BB", "\uFF27\uFF52")) assertTrue(ProviderLabels.normalize(l) in ProviderLabels.comparisonKeys(l))
    }

    @Test fun theCollisionIsDetectedWhicheverLabelWasRegisteredFirst() {
        for (g in listOf("\u115F", "\u1160", "\u2800", "\u3164", "\uFFA0")) {
            val r = ProviderRegistry.withBuiltIns()
            assertEquals(RegisterResult.Added, r.register(custom("Home${g}server")))
            val first = r.custom().single()
            assertEquals(RegisterResult.LabelInUse(first.id), r.register(custom("Home server")))
            assertEquals(RegisterResult.LabelInUse(first.id), r.register(custom("HOME  SERVER")))
            assertEquals(listOf(first), r.custom())
        }
    }
}
