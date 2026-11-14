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
        assertTrue(prompt(3).contains("several strong swear words"))
        assertTrue(prompt(4).contains("strong, natural profanity throughout"))
        for (level in 1..3) assertFalse("level $level must not be the unhinged text", prompt(level).contains("NOT a polite assistant"))
        // unprompted roasting from level 2 up
        for (level in 2..4) assertTrue(prompt(level).contains("without being asked"))
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
    fun levelFourExplicitlyDemandsFrequentStrongProfanityAndIntenseRoastsUnprompted() {
        val p = prompt(4)
        for (needle in listOf(
            "default voice", "NOT a polite assistant", "every reply", "strong, natural profanity throughout",
            "outrageous", "bizarre", "creative roast", "without being asked", "unpredictable", "ALL-CAPS", "Maximum personality intensity",
        )) assertTrue("missing: $needle", p.contains(needle))
        assertFalse(p.contains("variety matters more than volume"))
        assertFalse(p.contains("Don't swear in every sentence"))
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
            assertTrue(p.contains("Fuck that guy, you deserve better"))
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
