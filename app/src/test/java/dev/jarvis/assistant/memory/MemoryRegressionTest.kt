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

    // ---- Build 26b: "OK, I take it back, it's really orange." names no topic

    private fun turns(vararg s: String) = s.mapIndexed { i, x -> ChatTurn(i % 2 == 0, x) }
    private val recentColourChat = turns("My favourite colour is blue.", "Noted, sir. Blue it is.")

    @Test fun takeItBackCorrectionUpdatesTheSingleRecordAndEverythingShowsOrange() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        val r = s.observe("OK, I take it back, it's really orange.", recentColourChat)
        assertEquals(1, r.updated)
        assertTrue(MemoryStatusNote.of(r), MemoryStatusNote.of(r).contains("UPDATED:"))
        // The screen's flow (what the UI renders) ...
        val ui = s.memories.value
        assertEquals(1, ui.size)
        assertTrue(ui.single().content, ui.single().content.contains("orange", ignoreCase = true))
        // ... and the database file read through a brand new connection and service.
        val db = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        val rows = JdbcSql(db).query("SELECT content FROM memories") { it.string(0) }
        db.close()
        assertEquals(1, rows.size)
        assertTrue(rows.single().orEmpty(), rows.single().orEmpty().contains("orange", ignoreCase = true))
        assertFalse(rows.single().orEmpty().contains("blue", ignoreCase = true))
        val reopened = contents(service())
        assertEquals(1, reopened.size)
        assertTrue(reopened.single().contains("orange", ignoreCase = true))
    }

    @Test fun otherPhrasingsOfTheSameCorrectionWork() = runBlocking {
        for (t in listOf("make it green", "it's green now", "I meant green", "No, it's green", "Actually it's really green", "change it to green", "scratch that, green")) {
            val s = MemoryService({ InMemoryMemoryStorage() }, io = Dispatchers.Unconfined)
            s.observe("My favourite colour is blue.")
            val r = s.observe(t, recentColourChat)
            assertEquals(t, 1, r.updated)
            assertTrue(t, s.memories.value.single().content.contains("green", ignoreCase = true))
        }
    }

    @Test fun aCorrectionWithNoMatchingTopicChangesNothingAndSaysSo() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        val r = s.observe("OK, I take it back, it's really orange.", turns("What's the weather like?", "Sunny."))
        assertEquals(0, r.updated)
        assertTrue(r.correctionAsked)
        val note = MemoryStatusNote.of(r)
        assertTrue(note, note.contains("NOT UPDATED"))
        assertFalse(note.replace("NOT UPDATED:", "").contains("UPDATED:"))
        assertTrue(contents(service()).single().contains("blue", ignoreCase = true))
    }

    @Test fun anAmbiguousCorrectionIsNeverGuessed() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        s.observe("My favourite food is pizza.")
        val r = s.observe("I take it back, it's really orange", turns("My favourite colour is blue.", "My favourite food is pizza."))
        assertEquals(0, r.updated)
        assertEquals(setOf("Favourite colour is blue", "Favourite food is pizza"), contents(s).toSet())
    }

    @Test fun onlyTheTopicOfTheRecentChatIsChanged() = runBlocking {
        val s = service()
        s.observe("My phone is an iQOO 13")
        s.observe("My favourite colour is blue.")
        s.observe("I take it back, it's really orange", recentColourChat)
        val c = contents(s)
        assertTrue(c.any { it.contains("iQOO 13") })
        assertTrue(c.any { it.contains("orange", ignoreCase = true) })
        assertEquals(2, c.size)
    }

    @Test fun aBlockedCorrectionIsRefusedNotApplied() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        val r = s.observe("I meant 4111 1111 1111 1111", recentColourChat)
        assertEquals(0, r.updated)
        assertTrue(contents(s).single().contains("blue", ignoreCase = true))
    }

    @Test fun correctionValueOnlyMatchesRealCorrections() {
        assertEquals("orange", MemoryExtractor.correctionValue("OK, I take it back, it's really orange."))
        assertEquals("green", MemoryExtractor.correctionValue("make it green"))
        for (t in listOf("It's raining outside", "No thanks", "Actually I love tea", "what is it really?", "it's really nice to meet you today", "I think it's really orange")) {
            assertNull(t, MemoryExtractor.correctionValue(t))
        }
    }

    @Test fun aBrokenDatabaseNeverReportsACorrectionAsDone() = runBlocking {
        val base = InMemoryMemoryStorage()
        base.upsert(Memory("a", MemoryCategory.FAVOURITE, "favourite colour", "Favourite colour is blue", MemorySource.AUTO, 0.75, MemoryStatus.ACTIVE, 1, 1))
        val lossy = object : MemoryStorage by base {
            override fun upsert(m: Memory) { /* write silently lost */ }
        }
        val r = MemoryService({ lossy }, io = Dispatchers.Unconfined).observe("I take it back, it's really orange", recentColourChat)
        assertTrue(r.failed)
        assertFalse(MemoryStatusNote.of(r).contains("UPDATED:"))
    }

    // ---- Build 26c: the real-device failures (phone test of build #38)

    @Test fun iChangeMyMindItsOrangeNowUpdatesTheRecordEverywhere() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        val r = s.observe("I change my mind. it's orange now", recentColourChat)
        assertEquals(1, r.updated)
        val note = MemoryStatusNote.of(r)
        assertTrue(note, note.contains("UPDATED:"))
        assertEquals("Memory updated: Favourite colour is orange", MemoryStatusNote.notice(r))
        assertEquals(1, s.memories.value.size)
        assertTrue(s.memories.value.single().content.contains("orange"))
        val db = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        val rows = JdbcSql(db).query("SELECT content FROM memories") { it.string(0) }
        db.close()
        assertEquals(listOf("Favourite colour is orange"), rows)
        assertEquals(listOf("Favourite colour is orange"), contents(service()))
    }

    @Test fun threeCorrectionPhrasingsAllEndWithExactlyOneOrangeRecord() = runBlocking {
        for (t in listOf("I change my mind. it's orange now", "OK, I take it back, it's really orange", "Make it orange now")) {
            val s = MemoryService({ InMemoryMemoryStorage() }, io = Dispatchers.Unconfined)
            s.observe("My favourite colour is blue.")
            val r = s.observe(t, recentColourChat)
            assertEquals(t, 1, r.updated)
            assertEquals(t, listOf("Favourite colour is orange"), contents(s))
        }
    }

    @Test fun aManuallyAddedFavouriteCanBeCorrectedToo() = runBlocking {
        val s = service()
        s.addManual(MemoryCategory.FAVOURITE, "Favourite colour is blue")
        val r = s.observe("I change my mind. it's orange now", recentColourChat)
        assertEquals(1, r.updated)
        assertEquals(listOf("Favourite colour is orange"), contents(service()))
    }

    @Test fun saveThatInMemoryStoresTheEarlierStatement() = runBlocking {
        val s = service()
        val r = s.observe("Save that in memory", turns("Remember I use Kotlin.", "Okay."))
        assertEquals(1, r.saved)
        assertTrue(r.referenceAsked)
        assertTrue(MemoryStatusNote.of(r).contains("SAVED:"))
        assertEquals(1, contents(service()).size)
        assertTrue(contents(service()).single().contains("Kotlin"))
    }

    @Test fun saveThatAfterAFailedCorrectionAppliesItAndNeverDuplicates() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        val r = s.observe("Save that in memory", turns("My favourite colour is blue.", "Noted.", "I change my mind. it's orange now", "Orange it is."))
        assertEquals(1, r.updated)
        assertEquals(listOf("Favourite colour is orange"), contents(service()))
        val again = s.observe("Save that in memory", turns("I change my mind. it's orange now", "Done."))
        assertEquals(listOf("Favourite colour is orange"), contents(service()))
        assertEquals(0, again.saved)
    }

    @Test fun saveThatWithNothingToSaveIsReportedHonestlyAndNeverAsAFeatureOutage() = runBlocking {
        val s = service()
        val r = s.observe("Save that in memory", turns("What's the capital of France?", "Paris."))
        assertEquals(0, r.saved + r.updated)
        assertFalse(r.failed)
        val note = MemoryStatusNote.of(r)
        assertTrue(note, note.contains("NOT SAVED"))
        assertTrue(note, note.contains("unless this note says FAILED, memory is working normally"))
        assertEquals("Memory: nothing was saved. It was not clear what to keep.", MemoryStatusNote.notice(r))
        assertTrue(contents(service()).isEmpty())
    }

    @Test fun rememberThatMyFavouriteColourIsOrangeUpdatesTheBlueRecord() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        val r = s.observe("Remember that my favourite colour is orange", recentColourChat)
        assertEquals(1, r.updated)
        assertEquals(listOf("Favourite colour is orange"), contents(service()))
    }

    @Test fun thatsMyFavouritePhoneUsesTheEarlierMessageAndIsSavedAndVisible() = runBlocking {
        val s = service()
        s.observe("My favourite colour is blue.")
        s.observe("OK. I've an iQOO 13")
        val r = s.observe("That's my favourite phone", turns("OK. I've an iQOO 13", "Nice phone, sir."))
        assertEquals(1, r.saved)
        assertEquals("Memory saved: Favourite phone is iQOO 13", MemoryStatusNote.notice(r))
        val reopened = contents(service())
        assertEquals(setOf("Favourite colour is blue", "Favourite phone is iQOO 13"), reopened.toSet())
        val ui = s.memories.value.first { it.content.contains("phone") }
        assertEquals(MemoryCategory.FAVOURITE, ui.category)
        assertEquals(MemoryStatus.ACTIVE, ui.status)
    }

    @Test fun favouriteWithTheThingInTheSameSentenceWorks() = runBlocking {
        val s = service()
        s.observe("My iQOO 13 is my favourite phone")
        assertEquals(listOf("Favourite phone is iQOO 13"), contents(s))
    }

    @Test fun thatsMyFavouriteWithNothingEarlierIsNotInvented() = runBlocking {
        val s = service()
        val r = s.observe("That's my favourite phone", turns("What's the weather like?", "Sunny."))
        assertEquals(0, r.saved)
        assertTrue(contents(s).isEmpty())
    }

    @Test fun theUiNoticeIsNullForOrdinaryChatAndTruthfulOtherwise() = runBlocking {
        val s = service()
        assertNull(MemoryStatusNote.notice(s.observe("What's the weather like?")))
        assertTrue(MemoryStatusNote.notice(ObserveResult(failed = true))!!.contains("could not save"))
    }

    @Test fun aTimeOrWeatherWordIsNotTakenAsACorrection() {
        assertNull(MemoryExtractor.correctionValue("it's late now"))
        assertNull(MemoryExtractor.correctionValue("it's raining now"))
    }
}
