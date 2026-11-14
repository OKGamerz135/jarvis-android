package dev.jarvis.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {
    private fun x(s: String) = MemoryExtractor.extract(s)

    @Test fun favouriteIsExtractedWithAStableKey() {
        val c = x("My favourite colour is orange.").single()
        assertEquals(MemoryCategory.FAVOURITE, c.category)
        assertEquals("favourite colour", c.key)
        assertTrue(c.content.contains("orange", ignoreCase = true))
        assertFalse(c.uncertain)
    }

    @Test fun explicitRememberIsExtracted() {
        val c = x("Remember that I prefer short answers").single()
        assertTrue(c.explicit)
    }

    @Test fun deviceIsExtracted() {
        assertEquals(MemoryCategory.TECH, x("I use an iQOO 13 as my phone").first().category)
    }

    @Test fun hedgedStatementIsUncertain() {
        assertTrue(x("I think my favourite colour is green maybe").single().uncertain)
    }

    @Test fun questionsAndTemporaryThingsAreSkipped() {
        assertTrue(x("What is my favourite colour?").isEmpty())
        assertTrue(x("I'm tired right now").isEmpty())
        assertTrue(x("ok").isEmpty())
        assertTrue(x("Tell me a joke").isEmpty())
    }
}
