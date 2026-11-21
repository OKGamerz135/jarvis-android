package dev.jarvis.assistant.providers

import java.text.Normalizer
import java.util.Locale

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * Rules for provider display labels. A label is what the diagnostics, the cooldown and the usage counts are matched on,
 * so two providers must never be able to share one, even by spelling it differently.
 *
 * ONE policy decides what is invisible ([isInvisible]), and both checks use it: a label needs at least one visible
 * character, and the comparison form ([normalize]) drops every invisible character. So nothing that counts as invisible
 * when validating can be used to make a duplicate look different when comparing.
 */
object ProviderLabels {
    /**
     * Invisible characters that Unicode does not class as "format" or "space": fillers, blank glyphs and invisible
     * joiners/selectors (all letters, symbols or marks by category). NFKC maps U+3164 and U+FFA0 to U+1160, so that is here too.
     */
    private fun isInvisibleLookalike(cp: Int): Boolean =
        cp == 0x115F || cp == 0x1160 || cp == 0x2800 || cp == 0x3164 || cp == 0xFFA0 || // Hangul fillers, blank Braille
            cp == 0x17B4 || cp == 0x17B5 ||                                              // Khmer inherent vowels (draw nothing)
            cp == 0x034F ||                                                              // combining grapheme joiner
            cp in 0x180B..0x180D ||                                                      // Mongolian free variation selectors
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF                              // variation selectors

    private fun isSpaceLike(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    /** True for a code point that draws nothing: spaces, controls, format characters, unassigned/private/surrogate, and the lookalikes. */
    fun isInvisible(cp: Int): Boolean =
        isSpaceLike(cp) || isInvisibleLookalike(cp) || when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
            -> true
            else -> false
        }

    /**
     * True if the label has at least one character that is actually seen. Zero-width-only, filler-only and
     * space-plus-invisible-only labels have none. A real label that merely contains a formatting character (a zero-width
     * joiner in an emoji, say) still has visible ones and passes. Judged on the NFKC form, like [normalize].
     */
    fun hasVisibleCharacter(label: String): Boolean {
        val nfkc = Normalizer.normalize(label, Normalizer.Form.NFKC)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            if (!isInvisible(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** Blank glyphs that draw a gap as wide as a space (unlike zero-width characters). */
    private fun isBlankGlyph(cp: Int): Boolean = cp == 0x115F || cp == 0x1160 || cp == 0x2800 || cp == 0x3164 || cp == 0xFFA0

    private fun key(label: String, blankGlyphsAreSpaces: Boolean): String {
        val nfkc = Normalizer.normalize(label, Normalizer.Form.NFKC)
        val out = StringBuilder()
        var lastSpace = false
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isSpaceLike(cp) || (blankGlyphsAreSpaces && isBlankGlyph(cp)) -> {
                    if (!lastSpace) out.append(' ')
                    lastSpace = true
                }
                isInvisible(cp) -> {} // dropped: it neither separates nor shows
                else -> { out.appendCodePoint(cp); lastSpace = false }
            }
        }
        return out.toString().trim().lowercase(Locale.ROOT)
    }

    /**
     * The canonical form of a label: Unicode NFKC, then every space-like character becomes one space and every other
     * invisible character is dropped, whitespace runs collapse, the result is trimmed and lower-cased with Locale.ROOT.
     * "Groq", " groq ", "Gro\u200Bq" and "Gro\u3164q" all give the same form. Only this form is changed; a label is displayed as typed.
     */
    fun normalize(label: String): String = key(label, blankGlyphsAreSpaces = false)

    /**
     * Every form under which [label] must not match another provider's label. Besides [normalize] there is the form in
     * which a blank glyph (Hangul filler, blank Braille) counts as a space, because those draw a gap and "Home\u3164server"
     * can pass for "Home server". Two labels collide if their sets share a form. Real whitespace is never dropped:
     * "Home server" and "Homeserver" stay different.
     */
    fun comparisonKeys(label: String): Set<String> = setOf(key(label, false), key(label, true))
}
