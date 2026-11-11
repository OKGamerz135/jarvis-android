package dev.jarvis.assistant.diag

/**
 * Strips secrets and personal data from text before it is shown or stored in diagnostics.
 * Used on every error message, endpoint and model name that reaches the diagnostics log.
 */
object Redactor {
    private val googleKey = Regex("AIza[0-9A-Za-z_\\-]{20,}")
    private val groqKey = Regex("gsk_[0-9A-Za-z]{16,}")
    private val bearer = Regex("(?i)bearer\\s+[0-9A-Za-z._\\-]{8,}")
    private val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val project = Regex("projects/\\d+")

    // 10 or more digits, optionally separated by single spaces, dashes or brackets (phone numbers, IDs).
    // Dates such as 2026-11-10 have only 8 digits, so they survive.
    private val phone = Regex("\\+?\\d(?:[\\s\\-()]?\\d){9,}")
    private val longToken = Regex("[A-Za-z0-9_\\-]{32,}")
    private val tags = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")

    fun clean(text: String?, secrets: List<String> = emptyList(), maxLen: Int = 300): String {
        if (text.isNullOrEmpty()) return ""
        var t = tags.replace(text, " ")
        for (secret in secrets) {
            if (secret.length >= 8) t = t.replace(secret, "[key]")
        }
        t = googleKey.replace(t, "[key]")
        t = groqKey.replace(t, "[key]")
        t = bearer.replace(t, "Bearer [key]")
        t = email.replace(t, "[email]")
        t = project.replace(t, "projects/[id]")
        t = phone.replace(t, "[number]")
        // Long random-looking strings that contain digits are probably tokens. Long words without digits
        // (such as Google's quota IDs) are kept because they explain which limit was hit.
        t = longToken.replace(t) { m -> if (m.value.any { it.isDigit() }) "[token]" else m.value }
        t = spaces.replace(t, " ").trim()
        return if (t.length > maxLen) t.take(maxLen) + "…" else t
    }
}
