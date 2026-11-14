package dev.jarvis.assistant.memory

enum class Sensitivity {
    /** Never stored, not even as a proposal: credentials, card and account numbers, government IDs. */
    BLOCKED,

    /** Might be worth remembering but needs the user's explicit yes first. */
    NEEDS_APPROVAL,
    OK,
}

data class Verdict(val level: Sensitivity, val reason: String? = null)

/** Decides whether a piece of text may be remembered. Local rules only; nothing is ever sent anywhere to decide. */
object SensitivityGuard {
    private val SECRET_WORD = Regex(
        """\b(password|passwd|passcode|pass phrase|passphrase|pin code|pin number|pin|otp|cvv|cvc|api[ _-]?key|secret key|access token|auth(?:orization)? token|bearer token|private key|seed phrase|recovery phrase|client secret)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SECRET_VALUE_AFTER_WORD = Regex("""^\s*(?:is|are|was|=|:)\s*\S{3,}""", RegexOption.IGNORE_CASE)
    private val KEY_SHAPES = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{16,}"""),
        Regex("""\bAIza[0-9A-Za-z_-]{20,}"""),
        Regex("""\bgsk_[A-Za-z0-9]{16,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]{16,}""", RegexOption.IGNORE_CASE),
        Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}"""),
        Regex("""\b[A-Fa-f0-9]{32,}\b"""),
        Regex("""\b[A-Za-z0-9+/_-]{40,}={0,2}"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
    )
    private val CARD_CANDIDATE = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val ACCOUNT_NUMBER = Regex("""(?i)\b(account|a/c|acct|iban|routing|ifsc|swift)\b[^.\n]{0,25}?\b[A-Z0-9]{6,}\b""")
    private val GOV_ID = listOf(
        Regex("""\b\d{4}\s\d{4}\s\d{4}\b"""), // Aadhaar
        Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]\b"""), // PAN
        Regex("""\b\d{3}-\d{2}-\d{4}\b"""), // US SSN
        Regex("""(?i)\b(aadhaar|aadhar|passport|pan card|driving licen[sc]e|ssn|social security)\b[^.\n]{0,20}\d{4,}"""),
    )
    private val UPI = Regex("""(?i)\b[\w.-]{2,}@(?:oksbi|okhdfcbank|okicici|okaxis|ybl|ibl|axl|paytm|upi|apl|sbi|hdfcbank|icici)\b""")

    private val HEALTH = Regex(
        """(?i)\b(diagnos\w*|medication|medicine for|prescri\w+|depress\w*|anxiety|bipolar|adhd|autis\w*|cancer|diabet\w*|asthma|epilep\w*|pregnan\w*|therap(?:y|ist)|psychiatr\w*|surgery|disorder|hiv|std|illness|disease|chronic|disabilit\w*|mental health|panic attacks?|self[- ]harm|suicid\w*)\b""",
    )
    private val IDENTITY = Regex(
        """(?i)\b(gay|lesbian|bisexual|transgender|non-binary|queer|sexual orientation|religio\w*|muslim|hindu|christian|jewish|sikh|buddhist|atheist|caste|political(?:ly)?|vote[ds]? for|immigration|visa status|citizenship)\b""",
    )
    private val MONEY = Regex(
        """(?i)\b(salary|income|net worth|savings?|debts?|loans?|mortgage|credit score|bank balance|i earn|i make \d|owe\b)\b""",
    )
    private val LEGAL = Regex("""(?i)\b(arrested|convicted|criminal record|lawsuit|sued|jail|prison)\b""")
    private val ADDRESS = Regex("""(?i)\b(i live at|my (?:home |street |house )?address|my phone number|my number is|my email is|my home is at)\b""")
    private val OTHER_PERSON = Regex(
        """(?i)^\W*(?:remember that |note that )?(?:my |our )?(friend|friends|mom|mother|dad|father|wife|husband|boss|sister|brother|girlfriend|boyfriend|partner|colleague|coworker|co-worker|neighbou?r|roommate|teacher|son|daughter|cousin|uncle|aunt|ex|crush)\b""",
    )

    fun check(text: String): Verdict {
        val t = text.trim()
        if (t.isEmpty()) return Verdict(Sensitivity.OK)
        if (isSecret(t)) return Verdict(Sensitivity.BLOCKED, "looks like a password, key, token or account number")
        if (GOV_ID.any { it.containsMatchIn(t) }) return Verdict(Sensitivity.BLOCKED, "looks like a government ID number")
        if (OTHER_PERSON.containsMatchIn(t)) return Verdict(Sensitivity.NEEDS_APPROVAL, "it is about someone else")
        if (HEALTH.containsMatchIn(t)) return Verdict(Sensitivity.NEEDS_APPROVAL, "it is about health")
        if (IDENTITY.containsMatchIn(t)) return Verdict(Sensitivity.NEEDS_APPROVAL, "it is about identity, beliefs or status")
        if (MONEY.containsMatchIn(t)) return Verdict(Sensitivity.NEEDS_APPROVAL, "it is about money")
        if (LEGAL.containsMatchIn(t)) return Verdict(Sensitivity.NEEDS_APPROVAL, "it is about legal matters")
        if (ADDRESS.containsMatchIn(t)) return Verdict(Sensitivity.NEEDS_APPROVAL, "it is contact or address information")
        return Verdict(Sensitivity.OK)
    }

    private fun isSecret(t: String): Boolean {
        if (KEY_SHAPES.any { it.containsMatchIn(t) }) return true
        if (UPI.containsMatchIn(t)) return true
        if (ACCOUNT_NUMBER.containsMatchIn(t)) return true
        if (CARD_CANDIDATE.findAll(t).any { luhn(it.value.filter(Char::isDigit)) }) return true
        // "my password is hunter2" is blocked; "my favourite password manager is Bitwarden" is not.
        for (m in SECRET_WORD.findAll(t)) {
            val after = t.substring(m.range.last + 1)
            if (SECRET_VALUE_AFTER_WORD.containsMatchIn(after)) return true
        }
        return false
    }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        var alt = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alt) { n *= 2; if (n > 9) n -= 9 }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}
