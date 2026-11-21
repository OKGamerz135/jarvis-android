package dev.jarvis.assistant.providers

import java.text.Normalizer
import java.util.Locale

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * Rules for provider display labels. A label is what the diagnostics, the cooldown and the usage counts are matched on,
 * so two providers must never be able to share one, even by spelling it differently.
 */
object ProviderLabels {
    /** Characters that draw nothing but are not caught by "is blank": fillers and blank glyphs. */
    private val invisibleLookalikes = setOf('ᅟ', 'ᅠ', '⠀', 'ㅤ', 'ﾠ')

    private fun isInvisible(ch: Char): Boolean =
        ch in invisibleLookalikes || Character.isWhitespace(ch) || Character.isSpaceChar(ch) ||
            when (Character.getType(ch).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
                -> true
                else -> false
            }

    /**
     * True if the label has at least one character that is actually seen. Zero-width-only and space-plus-invisible-only
     * labels have none. A real label that merely contains a formatting character (a zero-width joiner in an emoji, say)
     * still has visible ones and passes.
     */
    fun hasVisibleCharacter(label: String): Boolean {
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            val chars = Character.toChars(cp)
            val invisible = if (chars.size == 1) isInvisible(chars[0]) else {
                // A supplementary character (emoji, rare scripts) is visible unless it is a format/private/unassigned one.
                when (Character.getType(cp).toByte()) {
                    Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.CONTROL -> true
                    else -> false
                }
            }
            if (!invisible) return true
            i += chars.size
        }
        return false
    }

    /**
     * The form two labels are compared in: Unicode NFKC, invisible formatting characters removed, trimmed, whitespace
     * runs collapsed to one space, lower case. "Groq", " groq " and "Gro​q" all give the same key.
     */
    fun normalize(label: String): String {
        val nfkc = Normalizer.normalize(label, Normalizer.Form.NFKC)
        val noFormat = buildString {
            var i = 0
            while (i < nfkc.length) {
                val cp = nfkc.codePointAt(i)
                if (Character.getType(cp).toByte() != Character.FORMAT) appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        return noFormat.replace(Regex("[\\s\\p{Z}]+"), " ").trim().lowercase(Locale.ROOT)
    }
}
