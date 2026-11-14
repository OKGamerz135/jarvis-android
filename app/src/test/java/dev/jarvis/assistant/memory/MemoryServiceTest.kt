package dev.jarvis.assistant.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryServiceTest {
    private var now = 1000L
    private var n = 0
    private val storage = InMemoryMemoryStorage()
    private val svc = MemoryService({ storage }, clock = { now++ }, newId = { "id${n++}" }, io = Dispatchers.Unconfined)

    private fun all() = runBlocking { svc.active() }
    private fun texts() = all().map { it.content }

    @Test fun createdMemoryIsRetrievedWhenRelevant() = runBlocking {
        svc.addManual(MemoryCategory.FAVOURITE, "Favourite colour is orange")
        val ctx = svc.contextFor(listOf("What colour should I pick for my wallpaper?"))
        assertTrue(ctx, ctx.contains("orange"))
    }

    @Test fun irrelevantMemoriesAreNotInjected() = runBlocking {
        svc.addManual(MemoryCategory.FAVOURITE, "Favourite colour is orange")
        assertEquals("", svc.contextFor(listOf("How do I fix a leaking tap?")))
    }

    @Test fun relevanceIsCappedByItemsAndCharacters() = runBlocking {
        repeat(12) { svc.addManual(MemoryCategory.PROJECT, "Project number$it uses kotlin gradle ${"x".repeat(150)}$it") }
        val ctx = svc.contextFor(listOf("kotlin gradle project"))
        val lines = ctx.lines().drop(1)
        assertTrue(lines.size <= MemoryService.MAX_ITEMS)
        assertTrue(lines.sumOf { it.length } <= MemoryService.MAX_CHARS + 20)
    }

    @Test fun autoExtractionSavesAndDuplicatesAreNotRepeated() = runBlocking {
        val a = svc.observe("My favourite colour is orange.")
        val b = svc.observe("My favourite colour is orange.")
        assertEquals(1, a.saved)
        assertEquals(1, b.duplicates)
        assertEquals(1, all().size)
    }

    @Test fun correctionUpdatesTheOldRecord() = runBlocking {
        svc.observe("My favourite colour is blue.")
        val id = all().single().id
        val r = svc.observe("My favourite colour is orange now.")
        assertEquals(1, r.updated)
        val m = all().single()
        assertEquals(id, m.id)
        assertTrue(m.content.contains("orange", ignoreCase = true))
        assertFalse(m.content.contains("blue", ignoreCase = true))
    }

    @Test fun uncertainStatementNeverSavesOrOverwrites() = runBlocking {
        svc.observe("I think my favourite colour is green maybe")
        assertTrue(all().isEmpty())
        svc.observe("My favourite colour is blue.")
        val r = svc.observe("I think my favourite colour is green maybe")
        assertEquals(1, r.proposed)
        assertTrue(texts().single().contains("blue", ignoreCase = true))
        assertEquals(1, svc.pending().size)
    }

    @Test fun approvingAReplaceProposalUpdatesInsteadOfDuplicating() = runBlocking {
        svc.observe("My favourite colour is blue.")
        svc.observe("I think my favourite colour is green maybe")
        svc.approve(svc.pending().single().id)
        assertTrue(svc.pending().isEmpty())
        assertTrue(texts().single().contains("green", ignoreCase = true))
    }

    @Test fun secretsAreNeverSavedAutomaticallyOrManually() = runBlocking {
        val r = svc.observe("Remember that my password is hunter22")
        assertEquals(0, r.saved + r.proposed)
        assertTrue(svc.addManual(MemoryCategory.OTHER, "my api key is sk-abcdefghijklmnopqrstuvwxyz123456") is AddResult.Blocked)
        assertTrue(storage.all().isEmpty())
    }

    @Test fun sensitiveFactsWaitForApproval() = runBlocking {
        svc.observe("Remember that I have asthma")
        assertTrue(all().isEmpty())
        assertEquals(1, svc.pending().size)
        assertEquals("", svc.contextFor(listOf("asthma")))
        svc.approve(svc.pending().single().id)
        assertEquals(1, all().size)
    }

    @Test fun dismissedProposalIsGone() = runBlocking {
        svc.observe("Remember that I have asthma")
        svc.dismiss(svc.pending().single().id)
        assertTrue(svc.pending().isEmpty())
        assertTrue(all().isEmpty())
    }

    @Test fun editDeleteAndClear() = runBlocking {
        val m = (svc.addManual(MemoryCategory.GOAL, "Ship build 25") as AddResult.Saved).memory
        svc.edit(m.id, "Ship build 26")
        assertEquals("Ship build 26", all().single().content)
        assertTrue(all().single().updatedAt > m.updatedAt)
        svc.delete(m.id)
        assertTrue(all().isEmpty())
        svc.addManual(MemoryCategory.GOAL, "One"); svc.addManual(MemoryCategory.GOAL, "Two things")
        svc.clearAll()
        assertTrue(storage.all().isEmpty())
        assertTrue(svc.memories.value.isEmpty())
    }

    @Test fun manualDuplicateIsRecognised() = runBlocking {
        svc.addManual(MemoryCategory.TECH, "Uses an iQOO 13")
        assertEquals(AddResult.Duplicate, svc.addManual(MemoryCategory.TECH, "Uses an iQOO 13"))
        assertEquals(1, all().size)
    }

    @Test fun standingInstructionsApplyToEveryMessage() = runBlocking {
        svc.addManual(MemoryCategory.INSTRUCTION, "Always keep answers short")
        assertTrue(svc.contextFor(listOf("hello there")).contains("short"))
    }

    @Test fun brokenStorageNeverThrowsAndRecoversLater() = runBlocking {
        var broken = true
        val s = MemoryService({ if (broken) error("disk gone") else storage }, io = Dispatchers.Unconfined)
        assertTrue(s.observe("My favourite colour is orange.").failed)
        assertEquals("", s.contextFor(listOf("colour")))
        assertTrue(s.addManual(MemoryCategory.OTHER, "something useful") is AddResult.Failed)
        s.delete("x"); s.clearAll(); s.refresh()
        broken = false
        assertTrue(s.addManual(MemoryCategory.OTHER, "something useful") is AddResult.Saved)
    }
}
