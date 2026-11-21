package dev.jarvis.assistant.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    /**
     * Synthetic, nonfunctional values in the exact length and character shape of Google and Groq keys (so the redaction
     * patterns are exercised realistically). Each is built from two pieces on purpose: no complete credential-shaped
     * string exists in this source file, so secret scanners have nothing to match, and the text says what it is.
     */
    private val syntheticGoogleKey = "AIza" + "SyNOTAREALsyntheticFIXTURE000000000"
    private val syntheticGroqKey = "gsk_" + "NOTAREALsyntheticGroqFIXTURE000000000000000000000000"

    @Test
    fun syntheticFixturesHaveTheRealKeyShapes() {
        assertEquals(39, syntheticGoogleKey.length) // Google API keys: "AIza" + 35 characters
        assertEquals(56, syntheticGroqKey.length)   // Groq keys: "gsk_" + 52 characters
        assertTrue(syntheticGoogleKey.contains("NOTAREAL"))
        assertTrue(syntheticGroqKey.contains("NOTAREAL"))
    }

    @Test
    fun removesGoogleAndGroqKeys() {
        val out = Redactor.clean("bad key $syntheticGoogleKey and $syntheticGroqKey")
        assertEquals("bad key [key] and [key]", out)
        assertFalse(out, out.contains("AIza"))
        assertFalse(out, out.contains("gsk_"))
    }

    @Test
    fun removesTheExactSavedKeyWhateverItLooksLike() {
        val out = Redactor.clean("the key my-weird-secret-value was rejected", listOf("my-weird-secret-value"))
        assertEquals("the key [key] was rejected", out)
    }

    @Test
    fun removesEmailsProjectNumbersAndPhoneLikeNumbers() {
        val out = Redactor.clean("user a.b@example.com in projects/123456789012 call 9876543210")
        assertFalse(out, out.contains("example.com"))
        assertFalse(out, out.contains("123456789012"))
        assertFalse(out, out.contains("9876543210"))
        assertTrue(out, out.contains("projects/[id]"))
    }

    @Test
    fun stripsHtmlAndTruncates() {
        assertEquals("Service Unavailable", Redactor.clean("<html><b>Service</b> Unavailable</html>"))
        val long = Redactor.clean("x ".repeat(500), maxLen = 50)
        assertTrue(long.length <= 51)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun nullAndShortSecretsAreSafe() {
        assertEquals("", Redactor.clean(null))
        assertEquals("abc", Redactor.clean("abc", listOf("ab")))
    }

    // ------------------------------------------------------------------ Build 41: every provider's key shape

    /** Deliberately fake keys in the real providers' shapes. */
    private val shapes = mapOf(
        "google" to "AIzaSyD" + "x9".repeat(16),
        "groq" to "gsk_" + "aB3dE5gH7jK9mN1pQ3rS5tU7vW9",
        "openrouter" to "sk-or-v1-" + "0a1b2c3d4e5f60718293a4b5c6d7e8f9".repeat(2),
        "openai-project" to "sk-proj-AbCdEfGhIjKlMnOpQrStUv1234",
        "cloudflare-user-token" to "cfut_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcd",
        "cloudflare-api-token" to "cfat_" + "AbCdEfGhIjKlMnOpQrStUvWxYz01",
        "zai" to "3f9a1c2b7d4e4a5f8c6d9e0a1b2c3d4e.AbCdEfGhIjKlMnOp",
        "github" to "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2",
        "github-pat" to "github_pat_" + "11ABCDEFG0123456789_abcdefghij",
        "slack" to "xoxb-1234567890-abcdefghij",
        "bearer" to "Bearer abcDEF123456.ghiJKL-789",
    )

    private fun leaks(secret: String, out: String): Boolean {
        // Any run of 8+ characters of the secret surviving in the output counts as a leak.
        val body = secret.removePrefix("Bearer ")
        return (0..body.length - 8).any { out.contains(body.substring(it, it + 8)) }
    }

    @Test
    fun everyProviderKeyShapeIsRemovedFromTypicalErrorTexts() {
        val templates = listOf(
            "Invalid API key: %s",
            "{\"error\":{\"message\":\"Incorrect API key provided: %s.\",\"code\":\"invalid_api_key\"}}",
            "request to https://example.test/v1/chat?key=%s failed",
            "Authorization: %s was rejected",
            "%s",
        )
        for ((name, key) in shapes) for (t in templates) {
            val out = Redactor.clean(t.format(key), maxLen = 600)
            assertFalse("$name leaked in [$t] -> $out", leaks(key, out))
        }
    }

    @Test
    fun theSavedKeyIsRemovedEvenWhenOnlyAPieceIsEchoed() {
        val key = shapes.getValue("zai")
        val half = key.substringAfter('.')
        val first = key.substringBefore('.')
        val out = Redactor.clean("rejected id $first and secret $half", listOf(key))
        assertFalse(out, out.contains(first))
        assertFalse(out, out.contains(half))
    }

    @Test
    fun assignedSecretsAreRemovedButOrdinaryWordsAreKept() {
        assertEquals("api_key=[key]", Redactor.clean("api_key=Zm9vYmFyYmF6cXV4MTIz"))
        assertEquals("token: [key]", Redactor.clean("token: Zm9vYmFyYmF6cXV4MTIz"))
        assertEquals("key not valid for model llama-3.3-70b", Redactor.clean("key not valid for model llama-3.3-70b"))
        assertEquals("key: invalid", Redactor.clean("key: invalid"))
        assertTrue(Redactor.clean("Quota exceeded: GenerateRequestsPerDayPerProjectPerModel-FreeTier").contains("GenerateRequestsPerDayPerProjectPerModel-FreeTier"))
    }

    @Test
    fun sinkCleansEntriesThatAdapterForgotToRedact() {
        val seen = ArrayList<DiagnosticEntry>()
        val sink = RedactingSink(object : DiagnosticsSink { override fun record(entry: DiagnosticEntry) { seen += entry } })
        val key = shapes.getValue("openrouter")
        sink.record(
            DiagnosticEntry(
                timeMillis = 1, kind = CallKind.GENERATE_STREAM, provider = "OpenRouter", model = "m-$key",
                method = "POST", endpoint = "openrouter.ai/api/v1/chat?key=$key", httpStatus = 401, apiStatus = "bad $key",
                message = "Incorrect key $key", durationMs = 5, attempt = 1, ok = false, requestChars = 10,
            ),
        )
        val e = seen.single()
        val all = listOf(e.model, e.endpoint, e.apiStatus.orEmpty(), e.message.orEmpty()).joinToString(" | ")
        assertFalse(all, leaks(key, all))
        assertEquals("OpenRouter", e.provider)
        assertEquals(401, e.httpStatus)
    }
}
