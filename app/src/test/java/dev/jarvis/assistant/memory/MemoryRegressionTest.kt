package dev.jarvis.assistant.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

/** The exact sentences from the phone test that exposed the bug, on the real SQL schema, plus the honesty notes. */
class MemoryRegressionTest {
    private val file: File = Files.createTempFile("reg", ".db").toFile()
    private fun open() = SqlMemoryStorage(JdbcSql(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")))
    private fun service() = MemoryService({ open() }, io = Dispatchers.Unconfined)
    private fun contents(s: MemoryService) = runBlocking { s.active().map { it.content } }

    @Test fun colourCorrectionWithChattyPrefixUpdatesTheOldRecord() = runBlocking {
        val s = service()
        assertEquals(1, s.observe("My favourite colour is orange now.").saved)
        val r = s.observe("You know what my favourite colour is blue now.")
        assertEquals(1, r.updated)
        assertEquals(0, r.saved)
        // Reopened database (a new service on the same file): one record, blue, no orange, no duplicate.
        val again = contents(service())
        assertEquals(1, again.size)
        assertTrue(again.single().contains("blue", ignoreCase = true))
        assertFalse(again.single().contains("orange", ignoreCase = true))
    }

    @Test fun saveThisInMemoryThatIHaveAnIqoo16IsStored() = runBlocking {
        val s = service()
        val r = s.observe("Save this in memory that I've a iQOO 16.")
        assertEquals(1, r.saved)
        assertTrue(r.askedToSave)
        val saved = contents(service())
        assertEquals(1, saved.size)
        assertTrue(saved.single(), saved.single().contains("iQOO 16"))
    }

    @Test fun theProperSpellingWorksAndRepeatsDoNotDuplicate() = runBlocking {
        val s = service()
        s.observe("Save this in memory that I have an iQOO 16.")
        val again = s.observe("Save this in memory that I have an iQOO 16.")
        assertEquals(1, again.duplicates)
        assertEquals(1, contents(service()).size)
    }

    @Test fun aNewPhoneReplacesTheOldPhoneInsteadOfDuplicating() = runBlocking {
        val s = service()
        s.observe("Remember that my phone is an iQOO 13")
        val r = s.observe("Save this in memory that I have an iQOO 16")
        assertEquals(1, r.updated)
        val list = contents(service())
        assertEquals(1, list.size)
        assertTrue(list.single().contains("16"))
    }

    @Test fun explicitFormsAreUnderstood() {
        for (t in listOf(
            "Remember that I use Kotlin", "Please remember I use Kotlin", "Can you remember that I use Kotlin?",
            "Save this in memory: I use Kotlin", "Store that to your memory that I use Kotlin",
        )) {
            assertTrue(t, MemoryExtractor.extract(t).isNotEmpty())
            assertTrue(t, MemoryExtractor.asksToSave(t))
        }
        assertFalse(MemoryExtractor.asksToSave("Do you remember my favourite colour?"))
        assertTrue(MemoryExtractor.extract("Add milk to the shopping list").isEmpty())
    }

    @Test fun secretsStayBlockedEvenWhenExplicitlyAsked() = runBlocking {
        val s = service()
        for (t in listOf(
            "Save this in memory that my password is hunter22",
            "Remember that my api key is sk-abcdefghijklmnopqrstuvwxyz123456",
            "Save this in memory that my card number is 4111 1111 1111 1111",
        )) {
            val r = s.observe(t)
            assertEquals(t, 0, r.saved + r.updated + r.proposed)
            assertTrue(t, MemoryStatusNote.of(r).contains("REFUSED"))
        }
        assertTrue(contents(service()).isEmpty())
    }

    @Test fun sensitiveExplicitFactsWaitForApprovalAndTheNoteSaysSo() = runBlocking {
        val s = service()
        val r = s.observe("Save this in memory that I have asthma")
        assertEquals(1, r.proposed)
        assertTrue(contents(service()).isEmpty())
        assertTrue(MemoryStatusNote.of(r).contains("AWAITING APPROVAL"))
        assertFalse(MemoryStatusNote.of(r).contains("SAVED:"))
    }

    @Test fun noteIsHonestAboutWhatHappened() = runBlocking {
        val s = service()
        assertTrue(MemoryStatusNote.of(s.observe("My favourite colour is orange")).contains("SAVED:"))
        assertTrue(MemoryStatusNote.of(s.observe("My favourite colour is blue now")).contains("UPDATED:"))
        assertTrue(MemoryStatusNote.of(s.observe("My favourite colour is blue now")).contains("ALREADY SAVED"))
        val none = MemoryStatusNote.of(s.observe("What's the weather like?"))
        assertTrue(none.contains("Nothing was saved"))
        val asked = MemoryStatusNote.of(s.observe("Please remember the thing"))
        assertTrue(asked, asked.contains("NOT SAVED"))
    }

    @Test fun aFailedDatabaseIsNeverReportedAsSaved() = runBlocking {
        val s = MemoryService({ error("db down") }, io = Dispatchers.Unconfined)
        val r = s.observe("Save this in memory that I have an iQOO 16")
        assertTrue(r.failed)
        val note = MemoryStatusNote.of(r)
        assertTrue(note.contains("FAILED"))
        assertFalse(note.contains("SAVED:") || note.contains("UPDATED:"))
    }

    @Test fun aWriteThatDoesNotStickIsReportedAsFailure() = runBlocking {
        val lossy = object : MemoryStorage by InMemoryMemoryStorage() {
            override fun upsert(m: Memory) { /* silently drops the write */ }
        }
        val r = MemoryService({ lossy }, io = Dispatchers.Unconfined).observe("My favourite colour is blue")
        assertTrue(r.failed)
        assertEquals(0, r.saved)
    }

    @Test fun theMemoryScreenFlowReflectsChangesImmediately() = runBlocking {
        val s = service()
        s.observe("My favourite colour is orange")
        assertEquals(1, s.memories.value.size)
        s.observe("You know what my favourite colour is blue now")
        assertTrue(s.memories.value.single().content.contains("blue", ignoreCase = true))
        assertNull(s.memories.value.firstOrNull { it.content.contains("orange", ignoreCase = true) })
    }
}
