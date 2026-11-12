package dev.jarvis.assistant.providers

import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.chat.MemoryStore
import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class QuotaLedgerTest {
    private var now = 1_000_000L
    private val store = MemoryStore()
    private fun ledger() = QuotaLedger(store) { now }
    private val G = AiProvider.GEMINI

    @Test fun exhaustedUntilResetThenAvailable() {
        val l = ledger()
        l.markExhausted(G, LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 60_000), "PerDay", ResetSource.REPORTED))
        assertTrue(l.isExhausted(G))
        now += 60_001
        assertFalse(l.isExhausted(G))
    }

    @Test fun unknownResetStaysExhaustedUntilClearedNeverGuessed() {
        val l = ledger()
        l.markExhausted(G, LlmError.QuotaExhausted(null, null, ResetSource.UNKNOWN))
        now += 10L * 24 * 3600 * 1000
        assertTrue(l.isExhausted(G))
        assertNull(l.record(G)!!.exhaustedUntil)
        l.clear(G)
        assertFalse(l.isExhausted(G))
    }

    @Test fun persistsAcrossInstances() {
        ledger().markExhausted(G, LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 5000), "q", ResetSource.DOCUMENTED))
        val r = ledger().record(G)!!
        assertEquals(ResetSource.DOCUMENTED, r.source)
        assertEquals(now + 5000, r.exhaustedUntil)
        assertEquals("q", r.quotaId)
    }

    @Test fun corruptStoreIsIgnored() {
        store.text = "not json {"
        assertFalse(ledger().isExhausted(G))
    }

    @Test fun fileStoreRoundTrips() {
        val f = Files.createTempDirectory("q").resolve("ledger.json").toFile()
        val a = QuotaLedger(FileQuotaStore(f)) { now }
        a.markExhausted(AiProvider.GROQ, LlmError.QuotaExhausted(null, null, ResetSource.UNKNOWN))
        assertTrue(QuotaLedger(FileQuotaStore(f)) { now }.isExhausted(AiProvider.GROQ))
    }

    @Test fun statusTextDistinguishesSources() {
        val zone = ZoneId.of("UTC")
        val at = Instant.ofEpochMilli(now)
        fun text(src: ResetSource, until: Long?) = ProviderStatus.describe(
            G, true, true, QuotaRecord(G, until, src, now, null), at, zone,
        )
        assertTrue(text(ResetSource.REPORTED, now + 3_600_000).contains("reported by"))
        assertTrue(text(ResetSource.DOCUMENTED, now + 3_600_000).contains("documentation"))
        assertTrue(text(ResetSource.UNKNOWN, null).contains("unknown"))
        assertEquals("Needs an API key", ProviderStatus.describe(G, true, false, null, at, zone))
        assertTrue(ProviderStatus.describe(G, false, true, null, at, zone).contains("consent"))
        assertTrue(ProviderStatus.describe(G, true, true, null, at, zone).startsWith("Ready"))
    }
}
