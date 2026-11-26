package dev.jarvis.assistant.ai

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * H1a-0 group B: statuses an HTTP client may act on by itself (122 cases).
 * B1-B6 are 401, 401 with a challenge, 407, 407 with a challenge, 408 and 421. B7 covers the redirect statuses that
 * ProviderRedirectTest does not run: Z.ai checkModel and all of OpenAiCompatibleProvider.
 * Records what today's code does. It says nothing about what it should do.
 */
internal object FollowUpCases {
    private const val BODY = """{"error":{"message":"characterization body"}}"""

    class Variant(val id: String, val status: Int, val headers: List<String>)

    private val b = listOf(
        Variant("B1-401", 401, emptyList()),
        Variant("B2-401-challenge", 401, listOf("WWW-Authenticate: Basic realm=\"x\"")),
        Variant("B3-407", 407, emptyList()),
        Variant("B4-407-challenge", 407, listOf("Proxy-Authenticate: Basic realm=\"x\"")),
        Variant("B5-408", 408, emptyList()),
        Variant("B6-421", 421, emptyList()),
    )
    private val reduced = setOf("B1-401", "B3-407", "B5-408", "B6-421")

    private fun uncoveredRedirectOp(op: CharOp) =
        (op.provider == "Z.ai" && op.op == "checkModel") || op.provider == "OpenAiCompatible"

    val all: List<CharCase> by lazy {
        val out = ArrayList<CharCase>()
        for (op in CharOps.all) {
            val chosen = if (op.kind == CharOp.Kind.STREAM) b else b.filter { it.id in reduced }
            for (v in chosen) {
                out += CharCases.wire("B", op, v.id) { _, _ -> CharReply.Bytes(CharHttp.response(v.status, v.headers, BODY)) }
            }
            if (uncoveredRedirectOp(op)) {
                for (status in listOf(300, 301, 302, 303, 307, 308)) {
                    out += CharCases.wire("B", op, "B7-$status", showOther = true) { _, bPort ->
                        CharReply.Bytes(CharHttp.response(status, listOf("Location: http://127.0.0.1:$bPort/stolen?x=1"), BODY))
                    }
                }
                out += CharCases.wire("B", op, "B7-302-noloc", showOther = true) { _, _ ->
                    CharReply.Bytes(CharHttp.response(302, emptyList(), BODY))
                }
            }
        }
        out
    }
}

/** Group B: each case records today's behavior against the baseline. */
@RunWith(Parameterized::class)
internal class CharacterizationFollowUpStatusTest(private val case: CharCase) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = FollowUpCases.all.map { arrayOf<Any>(it) }
    }

    @Test fun recordsTodaysBehavior() = CharCheck.assertRecorded(case)
}
