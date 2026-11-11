package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatStorageTest {
    private fun dir(): File = Files.createTempDirectory("chats").toFile().also { it.deleteOnExit() }

    private fun conv(id: String, updated: Long, vararg texts: String) = Conversation(
        id, "Title $id", 1, updated,
        texts.mapIndexed { i, t ->
            StoredMessage("m$i", if (i % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT, t,
                if (i % 2 == 1) "Groq" else null, if (i % 2 == 1) "m" else null, 10L + i, i == 3)
        },
    )

    @Test
    fun codecRoundTripsIncludingUnicodeAndQuotes() {
        val c = conv("a", 5, "héllo \"quoted\"\nnew line 🙂", "reply", "again", "cut")
        assertEquals(c, ChatCodec.decode(ChatCodec.encode(c)))
    }

    @Test
    fun damagedDataIsIgnoredNotFatal() {
        assertNull(ChatCodec.decode("not json"))
        assertNull(ChatCodec.decode("""{"id":"x"}"""))
    }

    @Test
    fun saveLoadSurvivesANewStorageObject() {
        val d = dir()
        FileChatStorage(d).save(conv("a", 5, "hi", "hello"))
        val loaded = FileChatStorage(d).load("a")
        assertNotNull(loaded)
        assertEquals(2, loaded!!.messages.size)
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun listIsNewestFirstAndSkipsEmptyAndBrokenFiles() {
        val d = dir()
        val s = FileChatStorage(d)
        s.save(conv("old", 1, "x"))
        s.save(conv("new", 9, "y", "z"))
        s.save(Conversation.empty("blank", 3))
        File(d, "broken.json").writeText("{{{")
        assertEquals(listOf("new", "old"), s.list().map { it.id })
        assertEquals(2, s.list().first().messageCount)
    }

    @Test
    fun deleteRemovesTheFile() {
        val s = FileChatStorage(dir())
        s.save(conv("a", 1, "x"))
        s.delete("a")
        assertNull(s.load("a"))
    }

    @Test
    fun oldestConversationsArePrunedPastTheCap() {
        val s = FileChatStorage(dir(), maxConversations = 2)
        s.save(conv("c1", 1, "x"))
        s.save(conv("c2", 2, "x"))
        s.save(conv("c3", 3, "x"))
        assertEquals(listOf("c3", "c2"), s.list().map { it.id })
    }

    @Test
    fun idsCannotEscapeTheFolder() {
        val d = dir()
        FileChatStorage(d).save(conv("../evil", 1, "x"))
        assertTrue(d.listFiles()!!.all { it.parentFile == d })
    }

    @Test
    fun titlesAreShortAndOneLine() {
        assertEquals("Hello there", Conversation.titleFrom("  Hello\n there "))
        assertTrue(Conversation.titleFrom("x".repeat(100)).length <= 41)
    }
}
