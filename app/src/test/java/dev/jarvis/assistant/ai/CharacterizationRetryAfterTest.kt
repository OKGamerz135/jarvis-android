package dev.jarvis.assistant.ai

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * H1a-0 group A: Retry-After on 429 and 503, for every provider operation (302 cases).
 * Records what today's HttpURLConnection providers send and return. It says nothing about what they should do.
 */
internal object RetryAfterCases {
    private const val BODY = """{"error":{"message":"characterization body"}}"""

    class Variant(val id: String, val headers: List<String>)

    val variants = listOf(
        Variant("A1-absent", emptyList()),
        Variant("A2-7", listOf("Retry-After: 7")),
        Variant("A3-0", listOf("Retry-After: 0")),
        Variant("A4-00", listOf("Retry-After: 00")),
        Variant("A5-1.5", listOf("Retry-After: 1.5")),
        Variant("A6-abc", listOf("Retry-After: abc")),
        Variant("A7-httpdate", listOf("Retry-After: Wed, 21 Oct 2015 07:28:00 GMT")),
        Variant("A8-minus1", listOf("Retry-After: -1")),
        Variant("A9-empty", listOf("Retry-After:")),
        Variant("A10-99999999999", listOf("Retry-After: 99999999999")),
        Variant("A11-padded", listOf("Retry-After:   7   ")),
        Variant("A12-0then7", listOf("Retry-After: 0", "Retry-After: 7")),
        Variant("A13-7then0", listOf("Retry-After: 7", "Retry-After: 0")),
    )

    private val reduced = setOf("A1-absent", "A2-7", "A3-0", "A12-0then7", "A13-7then0")

    val all: List<CharCase> by lazy {
        val out = ArrayList<CharCase>()
        for (op in CharOps.all) {
            if (op.kind == CharOp.Kind.QUOTA) continue
            val chosen = if (op.kind == CharOp.Kind.STREAM) variants else variants.filter { it.id in reduced }
            for (status in listOf(429, 503)) {
                for (v in chosen) {
                    out += CharCases.wire("A", op, "$status/${v.id}") { _, _ ->
                        CharReply.Bytes(CharHttp.response(status, v.headers, BODY))
                    }
                }
            }
        }
        out
    }
}

/** Group A: each case records today's behavior against the baseline. */
@RunWith(Parameterized::class)
internal class CharacterizationRetryAfterTest(private val case: CharCase) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = RetryAfterCases.all.map { arrayOf<Any>(it) }
    }

    @Test fun recordsTodaysBehavior() = CharCheck.assertRecorded(case)
}
