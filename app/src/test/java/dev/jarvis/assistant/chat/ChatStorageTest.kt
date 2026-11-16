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

    // ------------------------------------------------------------------ Build 41: crash-safe writes (audit CHAT-02)

    @Test fun aLeftoverTempFileFromACrashNeverReplacesTheRealConversation() {
        val d = dir()
        val st = FileChatStorage(d)
        st.save(conv("a", 5, "hi", "hello"))
        File(d, "a.json.tmp").writeText("{\"id\":\"a\",\"messages\":[")       // half-written file left by a crash
        File(d, "zzz.json.tmp").writeText("garbage")
        assertEquals(2, FileChatStorage(d).load("a")!!.messages.size)
        assertEquals(1, FileChatStorage(d).list().size)
        FileChatStorage(d).save(conv("a", 6, "hi", "hello", "again"))
        assertEquals(3, FileChatStorage(d).load("a")!!.messages.size)
        assertTrue("stale temp files are cleaned up", d.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
    }

    @Test fun aReaderNeverSeesAHalfWrittenFileWhileSaving() {
        val d = dir()
        val st = FileChatStorage(d)
        st.save(conv("a", 1, "start"))
        val target = File(d, "a.json")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val bad = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = Thread {
            while (!stop.get()) {
                val text = try { target.readText() } catch (e: java.io.IOException) { null }
                if (text != null && ChatCodec.decode(text) == null) bad.incrementAndGet()
            }
        }
        reader.start()
        for (i in 2..150) st.save(conv("a", i.toLong(), *Array(1 + i % 20) { "message $it ".repeat(50) }))
        stop.set(true); reader.join()
        assertEquals("the real file was readable and complete every single time", 0, bad.get())
    }

    @Test fun concurrentSavesLeaveOneValidFile() {
        val d = dir()
        val st = FileChatStorage(d)
        val threads = (1..12).map { t -> Thread { for (i in 1..25) st.save(conv("a", (t * 100 + i).toLong(), "from thread $t", "reply $i")) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertNotNull(FileChatStorage(d).load("a"))
        assertEquals(1, FileChatStorage(d).list().size)
        assertTrue(d.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
    }

    @Test fun aDamagedConversationFileDoesNotHideTheOthers() {
        val d = dir()
        val st = FileChatStorage(d)
        st.save(conv("good", 5, "hi", "hello"))
        File(d, "bad.json").writeText("{\"id\":\"bad\",\"messages\":[{\"id\":")   // truncated
        assertNull(st.load("bad"))
        assertEquals(listOf("good"), st.list().map { it.id })
    }
}
