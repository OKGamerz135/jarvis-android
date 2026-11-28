package dev.jarvis.assistant.net

/**
 * The only host names the guarded transport accepts (H1 design, section 1).
 *
 * A host is accepted only if it is a canonical ASCII DNS name with at least two labels whose last
 * label starts with a letter. IP literals in any notation, single-label names, trailing dots,
 * non-ASCII text and characters outside a-z, 0-9, '.' and '-' are rejected, so a name can never
 * be an address in disguise.
 *
 * Pure logic: no OkHttp type, no android.net import.
 */
internal object HostRule {
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

    // Defence in depth only. Matches strings that OkHttp's own parser would treat as an IP
    // address: anything with a colon, or digits and dots only.
    private val IP_LITERAL_SHAPE = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")

    /** Returns the canonical (lowercase) host, or null if the input is rejected. */
    fun validate(raw: String): String? {
        if (raw.isEmpty() || raw.any { it.code > 0x7f }) return null
        val h = raw.lowercase()
        if (h.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') }) return null
        if (h.length > 253 || h.endsWith(".")) return null
        val labels = h.split('.')
        if (labels.size < 2) return null
        for (l in labels) {
            if (l.isEmpty() || l.length > 63 || !LABEL.matches(l)) return null
            val dd = l.indexOf("--")
            if (dd >= 0 && !(dd == 2 && l.startsWith("xn--") && l.length > 4)) return null
        }
        if (labels.last()[0] !in 'a'..'z') return null
        if (IP_LITERAL_SHAPE.matches(h)) return null
        return h
    }
}
