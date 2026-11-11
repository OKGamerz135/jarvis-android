package dev.jarvis.assistant.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun removesGoogleAndGroqKeys() {
        val out = Redactor.clean("bad key AIzaSyA1234567890abcdefghijklmnopqrstuv and gsk_abcdefghijklmnop1234")
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
}
