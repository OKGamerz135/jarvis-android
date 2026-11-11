package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimmerTest {
    private fun m(i: Int, role: ChatRole, text: String) = StoredMessage("m$i", role, text, timeMillis = i.toLong())
    private val u = ChatRole.USER
    private val a = ChatRole.ASSISTANT

    @Test
    fun keepsEverythingWhenItFits() {
        val r = HistoryTrimmer.select(listOf(m(0, u, "hi"), m(1, a, "yo"), m(2, u, "ok")), 1000)
        assertEquals(3, r.messages.size)
        assertEquals(0, r.droppedCount)
    }

    @Test
    fun dropsOldestFirstAndStartsWithAUserMessage() {
        val all = listOf(m(0, u, "a".repeat(50)), m(1, a, "b".repeat(50)), m(2, u, "c".repeat(50)), m(3, a, "d".repeat(50)), m(4, u, "e".repeat(10)))
        val r = HistoryTrimmer.select(all, 120)
        // newest three (110 chars) fit; the list starts at a user message.
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), r.messages.map { it.role })
        assertEquals(2, r.droppedCount)
    }

    @Test
    fun theNewestMessageIsAlwaysSentEvenIfHuge() {
        val r = HistoryTrimmer.select(listOf(m(0, u, "x"), m(1, a, "y"), m(2, u, "z".repeat(5000))), 100)
        assertEquals(1, r.messages.size)
        assertTrue(r.messages[0].text.length == 5000)
    }

    @Test
    fun consecutiveSameRoleMessagesAreMerged() {
        val r = HistoryTrimmer.select(listOf(m(0, u, "first"), m(1, u, "second")), 1000)
        assertEquals(1, r.messages.size)
        assertEquals("first\n\nsecond", r.messages[0].text)
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(0, HistoryTrimmer.select(emptyList(), 100).messages.size)
    }
}
