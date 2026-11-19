package dev.jarvis.assistant.providers

import dev.jarvis.assistant.ai.LlmError
import dev.jarvis.assistant.ai.ResetSource
import dev.jarvis.assistant.settings.AiProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The quota ledger file. [legacy] is real output of the Build 46 / Phase B encoder (LedgerCodec.encode, which has not
 * changed since Build 46), captured before this stage and pasted here. It is not produced by the new code.
 */
class QuotaLedgerMigrationTest {

    private val legacy = """[{"provider":"GEMINI","until":1763000000000,"source":"REPORTED","at":1762990000123,"quota":"generate_content_free_tier_requests"},""" +
        """{"provider":"GROQ","until":null,"source":"UNKNOWN","at":1762991000000,"quota":null},""" +
        """{"provider":"OPENROUTER","until":1763078400000,"source":"DOCUMENTED","at":1762992000000,"quota":"free-models-per-day"},""" +
        """{"provider":"CLOUDFLARE","until":1763078400000,"source":"DOCUMENTED","at":1762993000000,"quota":"neurons"},""" +
        """{"provider":"ZAI","until":1763001000000,"source":"SIMULATED","at":1762994000000,"quota":null}]"""

    private val uuidA = "123e4567-e89b-42d3-a456-426614174000"
    private val uuidB = "9b2e7c1a-4d5f-4e6a-8b3c-1a2b3c4d5e6f"

    private class MemStore(var text: String = "") : QuotaStore {
        override fun load() = text
        override fun save(text: String) { this.text = text }
    }

    private val now = 1_763_000_500_000L

    private fun rows(text: String): List<JSONObject> = JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun assertSameRow(expected: JSONObject, actual: JSONObject) {
        for (k in listOf("provider", "until", "source", "at", "quota")) {
            assertEquals("field $k", expected.isNull(k), actual.isNull(k))
            if (!expected.isNull(k)) assertEquals("field $k", expected.get(k).toString(), actual.get(k).toString())
        }
    }

    // ---- existing data -------------------------------------------------------------------------------------------

    @Test fun theLegacyFileDecodesToExactlyTheRecordsItWasWrittenFrom() {
        val r = LedgerCodec.decode(legacy)
        assertEquals(listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.CLOUDFLARE, AiProvider.ZAI), r.keys.toList())
        assertEquals(QuotaRecord(AiProvider.GEMINI, 1_763_000_000_000L, ResetSource.REPORTED, 1_762_990_000_123L, "generate_content_free_tier_requests"), r[AiProvider.GEMINI])
        assertEquals(QuotaRecord(AiProvider.GROQ, null, ResetSource.UNKNOWN, 1_762_991_000_000L, null), r[AiProvider.GROQ])
        assertEquals(QuotaRecord(AiProvider.OPENROUTER, 1_763_078_400_000L, ResetSource.DOCUMENTED, 1_762_992_000_000L, "free-models-per-day"), r[AiProvider.OPENROUTER])
        assertEquals(QuotaRecord(AiProvider.CLOUDFLARE, 1_763_078_400_000L, ResetSource.DOCUMENTED, 1_762_993_000_000L, "neurons"), r[AiProvider.CLOUDFLARE])
        assertEquals(QuotaRecord(AiProvider.ZAI, 1_763_001_000_000L, ResetSource.SIMULATED, 1_762_994_000_000L, null), r[AiProvider.ZAI])
    }

    @Test fun writingTheLegacyRecordsBackProducesTheSameRowsInTheSameOrder() {
        val records = LedgerCodec.decode(legacy).values
        val written = LedgerCodec.encode(records)
        val before = rows(legacy)
        val after = rows(written)
        assertEquals(before.size, after.size)
        for (i in before.indices) assertSameRow(before[i], after[i])
        assertEquals("the same field names", before[0].keys().asSequence().toList(), after[0].keys().asSequence().toList())
    }

    @Test fun timestampsResetTimesAndTheUnknownResetSurviveARoundTrip() {
        val again = LedgerCodec.decode(LedgerCodec.encode(LedgerCodec.decode(legacy).values))
        assertEquals(LedgerCodec.decode(legacy), again)
        assertNull("an unknown reset time stays unknown and is never guessed", again[AiProvider.GROQ]!!.exhaustedUntil)
        assertEquals(1_762_990_000_123L, again[AiProvider.GEMINI]!!.recordedAt)
    }

    @Test fun aLedgerOverTheLegacyFileBehavesAsBefore() {
        val store = MemStore(legacy)
        val ledger = QuotaLedger(store) { now }
        assertTrue("Groq has no known reset time, so it stays exhausted", ledger.isExhausted(AiProvider.GROQ))
        assertFalse("Gemini's reset time is in the past", ledger.isExhausted(AiProvider.GEMINI))
        assertTrue("OpenRouter's reset time is in the future", ledger.isExhausted(AiProvider.OPENROUTER))
        assertEquals(Instant.ofEpochMilli(1_763_078_400_000L), ledger.exhaustedUntilInstant(AiProvider.CLOUDFLARE))
        assertEquals(5, ledger.records.value.size)
    }

    @Test fun savingOneProviderLeavesTheOtherRecordsExactlyAsTheyWere() {
        val store = MemStore(legacy)
        val ledger = QuotaLedger(store) { now }
        ledger.markExhausted(AiProvider.GROQ, LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 60_000), "tokens", ResetSource.REPORTED))
        val written = rows(store.text)
        val before = rows(legacy)
        assertEquals(5, written.size)
        for (i in listOf(0, 2, 3, 4)) assertSameRow(before[i], written[i])
        assertEquals(now + 60_000, written[1].getLong("until"))
        assertEquals("tokens", written[1].getString("quota"))
        assertEquals("GROQ", written[1].getString("provider"))
    }

    @Test fun clearingOneProviderLeavesTheOthersAlone() {
        val store = MemStore(legacy)
        QuotaLedger(store) { now }.clear(AiProvider.GEMINI)
        val written = rows(store.text)
        val before = rows(legacy)
        assertEquals(listOf("GROQ", "OPENROUTER", "CLOUDFLARE", "ZAI"), written.map { it.getString("provider") })
        for (i in written.indices) assertSameRow(before[i + 1], written[i])
    }

    // ---- unknown ids are never mapped ----------------------------------------------------------------------------

    @Test fun aRowWhoseProviderIsNotExactlyABuiltInIdIsNeverTurnedIntoABuiltInProvider() {
        val text = """[{"provider":"groq","until":1,"source":"REPORTED","at":2,"quota":null},""" +
            """{"provider":"Groq ","until":1,"source":"REPORTED","at":2,"quota":null},""" +
            """{"provider":"SAMBANOVA","until":1,"source":"REPORTED","at":2,"quota":null},""" +
            """{"provider":"custom:oops","until":1,"source":"REPORTED","at":2,"quota":null},""" +
            """{"provider":"","until":1,"source":"REPORTED","at":2,"quota":null},""" +
            """{"until":1,"source":"REPORTED","at":2,"quota":null},""" +
            """{"provider":5,"until":1,"source":"REPORTED","at":2,"quota":null}]"""
        val c = LedgerCodec.decodeAll(text)
        assertTrue(c.records.isEmpty())
        assertTrue("junk is dropped, not kept", c.foreign.isEmpty())
    }

    // ---- damaged rows ----------------------------------------------------------------------------------------------

    private val damaged = """[{"provider":"GEMINI","until":1763000000000,"source":"REPORTED","at":1762990000123,"quota":"q"},""" +
        """{"provider":"GROQ","until":"soon","source":"REPORTED","at":1,"quota":null},""" +
        """5,"text",null,[],{},""" +
        """{"provider":"OPENROUTER","until":1763078400000,"source":"DOCUMENTED","at":1762992000000,"quota":null}]"""

    @Test fun oneDamagedRowNoLongerEmptiesTheWholeLedger() {
        val r = LedgerCodec.decode(damaged)
        assertEquals(listOf(AiProvider.GEMINI, AiProvider.OPENROUTER), r.keys.toList())
        assertEquals(1_763_000_000_000L, r[AiProvider.GEMINI]!!.exhaustedUntil)
        assertEquals(1_763_078_400_000L, r[AiProvider.OPENROUTER]!!.exhaustedUntil)
    }

    @Test fun savingAfterReadingADamagedFileKeepsEveryValidRecord() {
        val store = MemStore(damaged)
        val ledger = QuotaLedger(store) { now }
        ledger.markExhausted(AiProvider.ZAI, LlmError.QuotaExhausted(null, null, ResetSource.UNKNOWN))
        val saved = LedgerCodec.decode(store.text)
        assertEquals(setOf(AiProvider.GEMINI, AiProvider.OPENROUTER, AiProvider.ZAI), saved.keys)
        assertEquals(1_763_000_000_000L, saved[AiProvider.GEMINI]!!.exhaustedUntil)
    }

    @Test fun textThatIsNotALedgerAtAllReadsAsEmptyAsBefore() {
        for (text in listOf("", "   ", "not json {", "{}", "\"x\"", "42", "[", "null")) {
            val c = LedgerCodec.decodeAll(text)
            assertTrue("\"$text\"", c.records.isEmpty() && c.foreign.isEmpty())
        }
    }

    // ---- ids from a future build -----------------------------------------------------------------------------------

    private val customRow = """{"provider":"custom:$uuidA","until":1763078400000,"source":"REPORTED","at":1762999999999,"quota":"tokens","extra":"kept"}"""

    private fun assertCustomRowKept(text: String, builtInRows: Int) {
        val saved = rows(text)
        val custom = saved.single { it.getString("provider").startsWith("custom:") }
        assertEquals("custom:$uuidA", custom.getString("provider"))
        assertEquals("kept", custom.getString("extra"))
        assertEquals(1762999999999L, custom.getLong("at"))
        assertEquals(builtInRows + 1, saved.size)
    }

    @Test fun aWellFormedCustomRowIsNotAProviderHereButIsNotErasedByASave() {
        val store = MemStore("[" + legacy.removePrefix("[").removeSuffix("]") + "," + customRow + "]")
        val ledger = QuotaLedger(store) { now }
        assertEquals("only built-in providers are visible", 5, ledger.records.value.size)

        ledger.markExhausted(AiProvider.GROQ, LlmError.QuotaExhausted(null, null, ResetSource.UNKNOWN))
        assertCustomRowKept(store.text, builtInRows = 5)
    }

    @Test fun aWellFormedCustomRowIsNotErasedByClearingAProviderEither() {
        val store = MemStore("[" + legacy.removePrefix("[").removeSuffix("]") + "," + customRow + "]")
        QuotaLedger(store) { now }.clear(AiProvider.ZAI)
        assertCustomRowKept(store.text, builtInRows = 4)
    }

    @Test fun repeatedCustomRowsForOneIdKeepTheLastAndMalformedIdsAreDropped() {
        val text = "[" + customRow + "," + customRow.replace("1762999999999", "1762999999000") + "," +
            """{"provider":"custom:${uuidB.uppercase()}","until":null,"source":"UNKNOWN","at":3,"quota":null},""" +
            """{"provider":"custom:bad","until":null,"source":"UNKNOWN","at":3,"quota":null}]"""
        val c = LedgerCodec.decodeAll(text)
        assertEquals(2, c.foreign.size)
        assertEquals(1762999999000L, c.foreign.first { it.getString("provider").endsWith(uuidA) }.getLong("at"))
    }

    // ---- repeating the migration changes nothing -------------------------------------------------------------------

    @Test fun loadingAndSavingRepeatedlyReachesAFixedPoint() {
        for (start in listOf(legacy, damaged, "[" + legacy.removePrefix("[").removeSuffix("]") + "," + customRow + "]", "", "garbage")) {
            val store = MemStore(start)
            val texts = ArrayList<String>()
            repeat(4) {
                val ledger = QuotaLedger(store) { now }
                ledger.markExhausted(AiProvider.ZAI, LlmError.QuotaExhausted(Instant.ofEpochMilli(now + 1000), "q", ResetSource.REPORTED))
                texts += store.text
            }
            assertEquals("stable after the first save: $start", texts[0], texts[1])
            assertEquals(texts[1], texts[3])
        }
    }

    @Test fun anEmptyLedgerWritesAnEmptyArrayAndReadsBackEmpty() {
        assertEquals("[]", LedgerCodec.encode(emptyList()))
        assertTrue(LedgerCodec.decodeAll("[]").records.isEmpty())
    }
}
