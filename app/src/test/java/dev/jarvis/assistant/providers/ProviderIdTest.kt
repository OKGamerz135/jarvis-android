package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProviderIdTest {
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"

    @Test fun builtInIdsAreTheEnumNamesSoStoredValuesKeepTheirMeaning() {
        for (p in AiProvider.entries) {
            assertEquals(p.name, p.toId().value)
            assertEquals(p.name, p.toId().toString())
            assertTrue(p.toId().isBuiltIn)
            assertFalse(p.toId().isCustom)
        }
        // The values Build 46 and Build 47 already store, spelled out so a rename of an enum entry cannot slip through.
        assertEquals(listOf("GEMINI", "GROQ", "OPENROUTER", "CLOUDFLARE", "ZAI"), AiProvider.entries.map { it.toId().value })
    }

    @Test fun builtInIdsMapBothWays() {
        for (p in AiProvider.entries) {
            assertEquals(p, p.toId().toAiProviderOrNull())
            assertEquals(p.toId(), ProviderId.parse(p.name))
            assertEquals(p, ProviderId.parse(p.name)!!.toAiProviderOrNull())
        }
    }

    @Test fun aCustomIdIsNeverAnEnumEntry() {
        val id = ProviderId.parse("custom:$uuid")!!
        assertTrue(id.isCustom)
        assertFalse(id.isBuiltIn)
        assertNull(id.toAiProviderOrNull())
        assertNull(ProviderId.newCustom().toAiProviderOrNull())
        // Not even when the UUID text happens to spell a built-in name's letters.
        assertNull(ProviderId.parse("custom:00000000-0000-0000-0000-000000000000")!!.toAiProviderOrNull())
        // And no custom id equals a built-in one.
        for (p in AiProvider.entries) assertNotEquals(p.toId(), id)
    }

    @Test fun validCustomIdsAreAccepted() {
        assertEquals("custom:$uuid", ProviderId.parse("custom:$uuid")!!.value)
        assertEquals("custom:$uuid", ProviderId.custom(UUID.fromString(uuid)).value)
    }

    @Test fun aCustomIdIsStoredInLowerCaseSoOneUuidIsOneId() {
        val lower = ProviderId.parse("custom:123e4567-e89b-42d3-a456-426614174abc")
        val upper = ProviderId.parse("custom:123E4567-E89B-42D3-A456-426614174ABC")
        assertNotNull(lower)
        assertEquals(lower, upper)
        assertEquals("custom:123e4567-e89b-42d3-a456-426614174abc", upper!!.value)
    }

    @Test fun invalidIdsAreRejectedWithNullAndNeverThrow() {
        val bad = listOf(
            null, "", " ", "\n", "custom", "custom:", "custom: ", "custom:123", "custom:not-a-uuid",
            "custom:123e4567-e89b-42d3-a456-42661417400",          // one character short
            "custom:123e4567-e89b-42d3-a456-4266141740000",        // one character long
            "custom:123e4567e89b42d3a456426614174000",             // no hyphens
            "custom:123e4567-e89b-42d3-a456-42661417400g",         // not hex
            "custom:{123e4567-e89b-42d3-a456-426614174000}",       // braces
            "custom:123e4567-e89b-42d3-a456-426614174000 ",        // trailing space
            " custom:123e4567-e89b-42d3-a456-426614174000",        // leading space
            "custom:123e4567-e89b-42d3-a456-426614174000:extra",   // extra text
            "custom:123e4567-e89b-42d3-a456-426614174000\n",       // trailing newline
            "CUSTOM:123e4567-e89b-42d3-a456-426614174000",         // the prefix is exact
            "Custom:123e4567-e89b-42d3-a456-426614174000",
            "custom:custom:123e4567-e89b-42d3-a456-426614174000",
            "custom:1-1-1-1-1",                                    // short forms that UUID.fromString would accept
            "123e4567-e89b-42d3-a456-426614174000",                // a bare UUID is not an id
            "groq", "Groq", "gemini", "GROQ ", " GROQ", "GROQ\n", "UNKNOWN", "OPENAI", "NONE",
            "GEMINI,GROQ", "custom:\u0000", "x".repeat(10_000),
        )
        for (text in bad) assertNull("\"$text\" must be rejected", ProviderId.parse(text))
    }

    @Test fun generatedCustomIdsAreValidDistinctAndSurviveAStoreAndReloadUnchanged() {
        val ids = List(200) { ProviderId.newCustom() }
        assertEquals(200, ids.toSet().size)
        for (id in ids) {
            assertTrue(id.isCustom)
            assertEquals("stable across restart", id, ProviderId.parse(id.value))
            assertEquals(id.value, ProviderId.parse(id.value)!!.value)
        }
    }

    @Test fun idsAreComparedByValueAndUsableAsMapKeys() {
        val a = ProviderId.parse("custom:$uuid")!!
        val b = ProviderId.custom(UUID.fromString(uuid))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(1, mapOf(a to 1, b to 2).size)
        assertNotEquals(AiProvider.GROQ.toId(), AiProvider.GEMINI.toId())
    }
}
