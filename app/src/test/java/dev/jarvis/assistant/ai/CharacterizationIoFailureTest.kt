package dev.jarvis.assistant.ai

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

import java.io.EOFException
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * H1a-0 group C: ioFailure mapping and connection failures (22 cases).
 * C1 calls ioFailure directly with exception types no existing test covers.
 * C2: the server reads the request and closes without replying. C3: the server promises 1000 body bytes, sends 20 and closes.
 * Records what today's code does. Timeouts are not driven over the wire.
 */
internal object IoFailureCases {
    private val c1: List<Pair<String, () -> IOException>> = listOf(
        "SSLHandshakeException" to { SSLHandshakeException("x") },
        "SSLPeerUnverifiedException" to { SSLPeerUnverifiedException("x") },
        "ProtocolException" to { ProtocolException("x") },
        "SocketException-Connection-reset" to { SocketException("Connection reset") },
        "EOFException" to { EOFException("x") },
        "NoRouteToHostException" to { NoRouteToHostException("x") },
        "SocketTimeoutException-null-message" to { SocketTimeoutException() },
        "SocketTimeoutException-Connect-timed-out" to { SocketTimeoutException("Connect timed out") },
    )

    val all: List<CharCase> by lazy {
        val out = ArrayList<CharCase>()
        for ((name, make) in c1) {
            out += CharCase("C1|ioFailure|$name", "C1") { ioFailure(make()).toString() }
        }
        for (op in CharOps.all) {
            if (op.kind != CharOp.Kind.STREAM) continue
            out += CharCases.wire("C", op, "C2-close-no-reply") { _, _ -> CharReply.Close }
            out += CharCases.wire("C", op, "C3-short-body") { _, _ ->
                CharReply.Bytes(CharHttp.response(200, emptyList(), "01234567890123456789", declaredLength = 1000))
            }
        }
        out
    }
}

/** Group C: each case records today's behavior against the baseline. */
@RunWith(Parameterized::class)
internal class CharacterizationIoFailureTest(private val case: CharCase) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = IoFailureCases.all.map { arrayOf<Any>(it) }
    }

    @Test fun recordsTodaysBehavior() = CharCheck.assertRecorded(case)
}
