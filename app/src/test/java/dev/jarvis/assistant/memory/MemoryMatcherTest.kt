package dev.jarvis.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMatcherTest {
    private fun mem(content: String, key: String? = null) =
        Memory("i", MemoryCategory.OTHER, key, content, MemorySource.AUTO, 0.8, MemoryStatus.ACTIVE, 0, 0)

    @Test fun identicalTextIsFullySimilar() = assertEquals(1.0, MemoryMatcher.similarity("I use Kotlin", "I use Kotlin"), 1e-9)

    @Test fun unrelatedTextIsNotSimilar() = assertTrue(MemoryMatcher.similarity("favourite colour orange", "phone battery drains") < 0.2)

    @Test fun stopwordsAreIgnored() = assertTrue("the" !in MemoryMatcher.tokens("the colour of the sky"))

    @Test fun overlappingMemoryScoresAndUnrelatedDoesNot() {
        val q = MemoryMatcher.tokens("what colour should I paint my room")
        assertTrue(MemoryMatcher.score(mem("Favourite colour is orange", "favourite colour"), q) >= 1.0)
        assertEquals(0.0, MemoryMatcher.score(mem("Uses an iQOO 13 phone", "my phone"), q), 1e-9)
    }
}
