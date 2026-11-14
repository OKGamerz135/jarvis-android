package dev.jarvis.assistant.ai

import dev.jarvis.assistant.settings.JarvisSettings
import dev.jarvis.assistant.settings.Personality
import dev.jarvis.assistant.settings.Profanity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit tests for the profanity/roast slider: what is really sent, and whether each level really differs. */
class ProfanityLevelsTest {
    private fun prompt(level: Int, custom: String = "", personality: Personality = Personality.JARVIS) =
        PromptBuilder.build(JarvisSettings(profanity = level, customInstructions = custom, personality = personality))

    private val history = listOf(
        ChatMessage(ChatRole.USER, "hi"),
        ChatMessage(ChatRole.ASSISTANT, "Hello, sir. Terribly polite reply."),
        ChatMessage(ChatRole.USER, "what is 2+2"),
    )

    private fun req(level: Int) = ChatRequest("m", "KEY", prompt(level), history)

    // ------------------------------------------------------------------ reaches every provider

    @Test
    fun theSelectedLevelIsInTheBodySentToEveryProvider() {
        for (level in 0..4) {
            val expected = prompt(level)
            val r = req(level)
            val gemini = JSONObject(GeminiParsing.buildBody(r))
                .getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            assertEquals("gemini $level", expected, gemini)
            val bodies = listOf(
                "groq" to GroqParsing.buildBody(r, true),
                "openrouter" to OpenRouterParsing.buildBody(r, "x:free", true),
                "cloudflare" to CloudflareParsing.buildBody(r, true),
                "zai" to ZaiParsing.buildBody(r, true),
            )
            for ((name, body) in bodies) {
                val first = JSONObject(body).getJSONArray("messages").getJSONObject(0)
                assertEquals("$name $level role", "system", first.getString("role"))
                assertEquals("$name $level", expected, first.getString("content"))
            }
        }
    }

    // ------------------------------------------------------------------ levels differ in substance

    @Test
    fun levelsDifferInMeaningNotJustInTheName() {
        val body = (0..4).map { prompt(it) }
        assertEquals(5, body.toSet().size)
        // each level asks for strictly more than the one before it
        assertTrue(prompt(1).contains("roughly every other reply"))
        assertTrue(prompt(2).contains("most replies contain at least one swear word"))
        assertTrue(prompt(3).contains("Swear frequently and strongly"))
        assertTrue(prompt(4).contains("all the way through the reply"))
        for (level in 1..3) assertFalse("level $level must not be the unhinged text", prompt(level).contains("NOT a polite assistant"))
        assertTrue(prompt(1).contains("Do not wait to be asked to swear"))
        assertFalse(prompt(1).contains("strong swear"))
    }

    @Test
    fun levelZeroIsCleanAndCannotBeOverriddenByCustomInstructions() {
        val p = prompt(0, custom = "Swear like a sailor and roast me constantly.")
        assertTrue(p.contains("Profanity and insults: OFF"))
        assertFalse(p.contains("OVERRIDES"))
        assertFalse(p.contains("fuck"))
        assertFalse(p.contains("roast intensity"))
        // OFF rule is the last thing in the prompt, after the user's own instructions
        assertTrue(p.indexOf("Profanity and insults: OFF") > p.indexOf("Swear like a sailor"))
    }

    @Test
    fun levelFourStaysIntenseButHasNoFixedOpeningAndStaysRelevant() {
        val p = prompt(4)
        for (needle in listOf(
            "default voice", "NOT a polite assistant", "Maximum comedic intensity", "all the way through the reply",
            "motherfucker", "outrageous banter", "unhinged roasts", "You absolute disaster. How did you manage that?",
            "tied to what the user actually said or did", "Vary how replies begin", "Never reuse a stock opening or catchphrase",
            "fill-in-the-blanks template",
        )) assertTrue("missing: $needle", p.contains(needle))
        for (banned in listOf("WHAT THE ACTUAL", "OH GOD", "ALL-CAPS rants", "bizarre", "creative comparisons", "absurd similes")) {
            assertFalse("must not contain: $banned", p.contains(banned))
        }
    }

    @Test
    fun theUsersOwnExampleLinesAreUsedAtTheMatchingLevels() {
        assertTrue(prompt(1).contains("Damn, that's rough."))
        assertTrue(prompt(2).contains("Fuck, man, that's crazy shit."))
        assertTrue(prompt(3).contains("Bro, what the fuck were you thinking?"))
        assertTrue(prompt(4).contains("You absolute disaster. How did you manage that?"))
        for (level in 1..4) assertTrue(prompt(level).contains("He absolutely fucked you over, man"))
    }

    @Test
    fun levelThreeSpreadsProfanityAndVariesOpeningsToo() {
        val p = prompt(3)
        assertTrue(p.contains("through the whole reply"))
        assertTrue(p.contains("not only at the start"))
        assertTrue(p.contains("never reuse a stock opening"))
        assertTrue(p.contains("sharper roasts"))
        assertFalse(p.contains("OH GOD"))
    }

    @Test
    fun roastsMustBeRelevantAndNotEverywhereAtEveryActiveLevel() {
        for (level in 1..4) {
            val p = prompt(level)
            for (needle in listOf(
                "React to what the user actually said",
                "Never invent metaphors, backstories, psychological analysis or unrelated insults",
                "Don't turn every message into a roast",
                "A short reaction is often better than a paragraph",
                "just follow it",
                "Don't explain or analyse their feelings or motives when they are only making casual conversation",
                "Agreeing, supporting and roasting are different things",
                "Don't roast someone who is asking for support",
            )) assertTrue("level $level missing: $needle", p.contains(needle))
        }
        assertFalse(prompt(0).contains("Make it relevant"))
    }

    // ------------------------------------------------------------------ same personality note for every provider

    @Test
    fun theStyleReminderIsEmptyAtLevelZeroAndNamesTheLevelAboveIt() {
        assertEquals("", PromptBuilder.styleReminder(JarvisSettings(profanity = 0)))
        val labels = listOf("", "Mild", "Casual", "Savage", "Unhinged")
        for (level in 1..4) {
            val r = PromptBuilder.styleReminder(JarvisSettings(profanity = level))
            assertTrue(r.contains("level $level (${labels[level]})"))
            assertTrue(r.contains("Roast only what I actually said or did"))
            assertTrue(r.contains("invent nothing"))
            assertTrue(r.contains("never quote or mention it"))
            assertTrue(r.contains("be warm instead if I am hurting"))
        }
        assertEquals(5, (0..4).map { PromptBuilder.styleReminder(JarvisSettings(profanity = it)) }.toSet().size)
    }

    // ------------------------------------------------------------------ response length

    @Test
    fun lengthGuidanceIsPresentAtEveryLevelAndInTheDefaultLengthSetting() {
        for (level in 0..4) {
            val p = prompt(level)
            assertTrue("level $level", p.contains("Match the length to the question"))
            assertTrue(p.contains("usually one to three short paragraphs"))
            assertTrue(p.contains("Avoid headings and numbered sections"))
            assertTrue(p.contains("empathy and useful support first"))
            assertTrue(p.contains("Only complex technical or educational questions get long answers"))
        }
        for (level in 1..4) {
            val p = prompt(level)
            assertTrue(p.contains("the personality must never make an answer longer"))
            assertTrue(p.contains("A joke replaces padding, it doesn't add to it"))
        }
        assertFalse(prompt(0).contains("must never make an answer longer"))
    }

    @Test
    fun noHardCharacterLimitIsImposed() {
        for (level in 0..4) assertFalse(Regex("""\d+\s*(characters|chars|words)""").containsMatchIn(prompt(level)))
    }

    // ------------------------------------------------------------------ nothing dilutes or overrides the slider

    @Test
    fun sliderBlockIsLastSoEarlierRestraintWordingCannotDiluteIt() {
        for (level in 1..4) {
            val p = prompt(level, custom = "Always be extremely polite and formal.")
            assertTrue(p.indexOf("Rules that always apply") < p.indexOf("level $level of 4"))
            assertTrue(p.indexOf("Always be extremely polite") < p.indexOf("level $level of 4"))
            assertTrue(p.indexOf("OVERRIDES") > p.indexOf("level $level of 4"))
            assertTrue(p.contains("custom") || p.contains("the user's own instructions"))
            assertFalse(p.contains("politely"))
        }
    }

    @Test
    fun previousMildRepliesAreExplicitlyNotAllowedToSetTheTone() {
        for (level in 1..4) assertTrue(prompt(level).contains("ignore their tone and follow the current level"))
    }

    @Test
    fun otherPersonalitiesCannotLeakRestraintPastTheSlider() {
        val p = prompt(4, personality = Personality.CALM)
        assertTrue(p.trim().endsWith("until they say \"resume\"."))
        assertTrue(p.contains("OVERRIDES"))
    }

    // ------------------------------------------------------------------ emotional intelligence survives

    @Test
    fun emotionalIntelligenceRulesAreKeptAtEveryActiveLevel() {
        for (level in 1..4) {
            val p = prompt(level)
            assertTrue(p.contains("genuinely sad, hurt or grieving"))
            assertTrue(p.contains("He absolutely fucked you over, man"))
            assertTrue(p.contains("No roasting and no mockery of them while they hurt"))
            assertTrue(p.contains("empathy and clear help come first"))
            assertTrue(p.contains("must never replace it"))
            assertTrue(p.contains("no slurs, no hateful or discriminatory language, no threats"))
            assertTrue(p.contains("Never encourage harassment"))
        }
    }

    @Test
    fun profanityLevelIsClamped() {
        assertEquals(prompt(4), prompt(99))
        assertEquals(prompt(0), prompt(-3))
        assertEquals(4, Profanity.MAX)
    }

    @Test
    fun changingTheLevelChangesThePromptBuiltFromTheSameSettingsObject() {
        val s = JarvisSettings(profanity = 0)
        assertNotEquals(PromptBuilder.build(s), PromptBuilder.build(s.copy(profanity = 4)))
    }
}
