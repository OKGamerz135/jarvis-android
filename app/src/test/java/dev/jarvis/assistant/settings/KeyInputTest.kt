package dev.jarvis.assistant.settings

import dev.jarvis.assistant.providers.ProviderDescriptor
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.toId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** B6: key validation, model-field notes and the typed save/remove notice. */
class KeyInputTest {
    private val G = AiProvider.GEMINI
    private val GD = ProviderDescriptor.of(G)
    private val GID = G.toId()
    private val good = "AIzaSyExampleExampleExample123456" // 33 characters, not a real key

    // ------------------------------------------------------------------ key validation

    @Test fun emptyInputIsUntouchedNotAnError() {
        val c = KeyInputCheck.check("", GD)
        assertEquals(KeyInputCheck.Untouched, c)
        assertFalse(c.canSave)
        assertNull(KeyInputCheck.message(c, GD))
    }

    @Test fun whitespaceOnlyInputIsReportedAsBlank() {
        for (raw in listOf(" ", "   ", "\n", " \t \r\n ")) {
            val c = KeyInputCheck.check(raw, GD)
            assertEquals("for '${raw.replace("\n", "\\n")}'", KeyInputCheck.BlankInput, c)
            assertFalse(c.canSave)
            assertTrue(KeyInputCheck.message(c, GD)!!.contains("only spaces or line breaks"))
        }
    }

    @Test fun tooShortKeysAreRejectedWithTheLengthAndTheProvidersOwnHelp() {
        val c = KeyInputCheck.check("abc123", GD) as KeyInputCheck.TooShort
        assertEquals(6, c.length); assertEquals(MIN_KEY_LENGTH, c.min)
        assertFalse(c.canSave)
        val m = KeyInputCheck.message(c, GD)!!
        assertTrue(m, m.contains("6 characters") && m.contains(G.label) && m.contains(G.keyHelp))
    }

    @Test fun theMinimumLengthRuleIsUnchanged() {
        assertEquals(20, MIN_KEY_LENGTH)
        assertTrue(KeyInputCheck.check("a".repeat(19), GD) is KeyInputCheck.TooShort)
        assertTrue(KeyInputCheck.check("a".repeat(20), GD) is KeyInputCheck.Valid)
    }

    @Test fun lengthIsCountedAfterWhitespaceIsRemoved() {
        // 19 real characters plus lots of spaces must not slip past the rule.
        assertTrue(KeyInputCheck.check("a".repeat(19) + "     \n", GD) is KeyInputCheck.TooShort)
        assertTrue(KeyInputCheck.check("a".repeat(10) + " \n " + "b".repeat(10), GD) is KeyInputCheck.Valid)
    }

    @Test fun surroundingAndInnerWhitespaceIsRemovedAndSaidSo() {
        val c = KeyInputCheck.check("  $good \n", GD) as KeyInputCheck.Valid
        assertEquals(good, c.key)
        assertTrue(c.removedWhitespace)
        assertTrue(c.canSave)
        assertTrue(KeyInputCheck.message(c, GD)!!.contains("removed"))
        assertEquals(good, (KeyInputCheck.check("${good.take(10)} ${good.drop(10)}", GD) as KeyInputCheck.Valid).key)
    }

    @Test fun aCleanKeyIsSavedAsIsWithNoMessage() {
        val c = KeyInputCheck.check(good, GD) as KeyInputCheck.Valid
        assertEquals(good, c.key); assertFalse(c.removedWhitespace)
        assertNull(KeyInputCheck.message(c, GD))
    }

    @Test fun messagesNeverEchoTheKey() {
        val secret = "SECRET" + "x".repeat(3)
        for (raw in listOf(secret, "  $secret  ", secret + " ")) {
            val c = KeyInputCheck.check(raw, GD)
            assertFalse(KeyInputCheck.message(c, GD).orEmpty().contains(secret))
        }
    }

    @Test fun everyProviderHasGuidanceForAShortKey() {
        for (p in ProviderRegistry.withBuiltIns().all()) assertTrue(p.label, KeyInputCheck.message(KeyInputCheck.check("short", p), p)!!.contains(p.keyHelp))
    }

    // ------------------------------------------------------------------ model field

    @Test fun blankModelFieldSaysTheDefaultIsUsed() {
        for (raw in listOf("", "   ")) {
            val n = ModelInputNote.of(raw, GD)
            assertTrue(n.usingDefault); assertEquals(G.defaultModel, n.effective)
            assertTrue(n.message.contains("blank") && n.message.contains(G.defaultModel))
        }
    }

    @Test fun theEffectiveModelIsExactlyWhatSettingsUse() {
        for (p in AiProvider.entries) for (raw in listOf("", "  ", "my-model", "  padded-model  ", p.defaultModel)) {
            val settings = JarvisSettings().withModel(p, raw)
            assertEquals("$p '$raw'", settings.modelOf(p), ModelInputNote.of(raw, ProviderDescriptor.of(p)).effective)
        }
    }

    @Test fun theDefaultModelIsDescribedAsTheDefault() {
        val n = ModelInputNote.of(G.defaultModel, GD)
        assertTrue(n.usingDefault)
        assertTrue(n.message.startsWith("Using the default model"))
    }

    @Test fun aCustomModelIsAcceptedAsIsAndTheUserIsPointedToTheCheck() {
        val n = ModelInputNote.of("some-new-model:free", GD)
        assertFalse(n.usingDefault); assertEquals("some-new-model:free", n.effective)
        assertTrue(n.message.contains("availability check"))
        assertFalse(n.message.contains("invalid", ignoreCase = true)) // no invented restrictions
    }

    @Test fun surroundingSpacesInTheModelAreNotedAsIgnored() {
        val n = ModelInputNote.of("  custom  ", GD)
        assertEquals("custom", n.effective)
        assertTrue(n.message.contains("Spaces at the start or end are ignored"))
        assertFalse(ModelInputNote.of("custom", GD).message.contains("Spaces"))
    }

    // ------------------------------------------------------------------ typed notice

    @Test fun successIsNotAnErrorAndFailureIs() {
        assertFalse(KeyNotice.saved(GID, true).isError)
        assertFalse(KeyNotice.removed(GID, true).isError)
        assertTrue(KeyNotice.saved(GID, false).isError)
        assertTrue(KeyNotice.removed(GID, false).isError)
        assertTrue(KeyNotice.saveFailed(GID, IOException("disk full")).isError)
        assertTrue(KeyNotice.removeFailed(GID, IllegalStateException("x")).isError)
    }

    @Test fun classificationDoesNotDependOnTheWording() {
        // The old code looked for the text "Couldn't". The flag is now data, so a changed message can't flip it.
        val n = KeyNotice(GID, "Something went wrong", isError = true)
        assertTrue(n.isError)
        assertFalse(KeyNotice(GID, "Couldn't believe how well that went", isError = false).isError)
    }

    @Test fun failureMessagesNameTheCauseAndSuccessMessagesAreShort() {
        assertTrue(KeyNotice.saveFailed(GID, IOException("disk full")).message.contains("IOException: disk full"))
        assertEquals("Key saved.", KeyNotice.saved(GID, true).message)
        assertEquals("Key removed.", KeyNotice.removed(GID, true).message)
        assertTrue(KeyNotice.saved(GID, false).message.contains("Nothing was changed"))
    }

    @Test fun aNoticeBelongsToOneProvider() {
        val n = KeyNotice.saved(GID, true)
        assertEquals(GID, n.provider)
        assertTrue(n.provider != AiProvider.GROQ.toId()) // so the Groq screen does not show Gemini's "Key saved."
    }
}
