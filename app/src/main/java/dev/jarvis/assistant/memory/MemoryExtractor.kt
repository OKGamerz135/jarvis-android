package dev.jarvis.assistant.memory

/**
 * Finds useful, fairly stable facts in what the USER wrote. Local rules only: no AI call, so nothing the user says
 * is ever sent to a provider for memory extraction. Questions, one-off or temporary statements and chit-chat are
 * ignored. Anything unsure is flagged [Candidate.uncertain] and never saved on its own.
 */
object MemoryExtractor {
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?])\s+|\n+""")
    private val QUESTION_START = Regex("""(?i)^(what|who|when|where|why|how|which|can|could|would|should|do|does|did|is|are|am|will|tell me|explain)\b""")
    private val TEMPORARY = Regex(
        """(?i)\b(today|tonight|tomorrow|yesterday|right now|at the moment|this (?:morning|afternoon|evening|week|weekend)|later|just now|currently (?:eating|watching|listening|drinking)|for now)\b""",
    )
    private val HEDGE = Regex("""(?i)\b(maybe|might|probably|perhaps|possibly|i think|i guess|i suppose|not sure|sometimes|kind of|sort of|i wonder|could be)\b""")
    private val CORRECTION = Regex("""(?i)\b(now|actually|anymore|any more|no longer|changed|switched|instead)\b""")

    private val VAGUE = setOf("thing", "things", "stuff", "something", "anything", "everything", "one", "this", "that", "it")
    private val VAGUE_TOKENS by lazy { MemoryMatcher.tokens(VAGUE.joinToString(" ")) }
    private val ASK = """(?:(?:can|could|would|will)\s+you\s+)?(?:please\s+)?"""
    /** "remember that ...", "note that ...", "don't forget ...". */
    private val EXPLICIT = Regex("""(?i)^$ASK(?:remember|note(?:\s+down)?|keep in mind|don't forget|dont forget)(?:\s+that)?\s*[:,]?\s+(.{4,})$""")
    /** "save this in memory that ...", "store that to memory: ...", "add it to your memory ...". The memory words are required. */
    private val EXPLICIT_SAVE = Regex(
        """(?i)^$ASK(?:save|store|add|put|write(?:\s+down)?|log|keep|memori[sz]e)(?:\s+(?:this|that|it))?(?:\s+(?:in|into|to|on))?(?:\s+(?:your|my|the))?\s+(?:long[- ]term\s+)?(?:memory|memories)(?:\s+(?:that|:))?\s*[:,\-]?\s*(.{4,})$""",
    )
    private val SAVE_INTENT = Regex("""(?i)\b(remember|save|store|memori[sz]e|note (?:this|that|down)|don't forget|dont forget|keep (?:this |that )?in mind)\b""")
    private val ASKING_TO = Regex("""(?i)^(?:(?:can|could|would|will)\s+you|please)\b""")
    private val LEAD_IN = Regex("""(?i)^(?:hey|hi|hello|ok|okay|so|well|and|but|also|btw|by the way|you know(?: what)?|just so you know|fyi|oh|listen|look|jarvis|sir)\b[,:!]?\s+""")
    private val HAVE = Regex("""(?i)^i(?:'ve|\s+have|\s+own|\s+got)\s+(?:got\s+)?(.{2,100})$""")
    private val AM = Regex("""(?i)^i(?:'m|\s+am)\s+(.{3,100})$""")
    private val PHONE_BRANDS = Regex("""(?i)\b(iqoo|iphone|pixel|galaxy|oneplus|redmi|poco|realme|vivo|oppo|xiaomi|motorola|moto|nothing phone|samsung)\b""")
    private val FAVOURITE = Regex("""(?i)^my\s+(?:all[- ]time\s+)?fav(?:ou?rite)?\s+([a-z][a-z \-]{1,30}?)\s+(?:is|are|was)\s+(?:now\s+)?(.{1,100})$""")
    private val TECH = Regex(
        """(?i)^my\s+(phone|laptop|pc|computer|tablet|watch|tv|router|car|bike|setup|os|editor|ide|keyboard|mouse|monitor|headphones|earbuds|console)\s+(?:is|are)\s+(?:now\s+)?(?:an?\s+|the\s+)?(.{2,100})$""",
    )
    private val USE = Regex("""(?i)^i\s+(?:mostly\s+|usually\s+|mainly\s+)?(?:use|code in|develop in|program in|work with)\s+(.{2,100})$""")
    private val PROJECT = Regex("""(?i)^(?:i'm|i am|we're|we are)\s+(?:currently\s+)?(working on|building|developing|making|creating|designing)\s+(.{3,140})$""")
    private val PROJECT_NAMED = Regex("""(?i)^my\s+(?:main\s+)?project\s+(?:is|called|named)\s+(.{2,120})$""")
    private val DECISION = Regex("""(?i)^(?:i|we)(?:'ve|\s+have)?\s+decided\s+to\s+(.{4,140})$|^(?:we're|we are|i'm|i am)\s+going\s+with\s+(.{3,120})$|^let's\s+go\s+with\s+(.{3,120})$""")
    private val GOAL = Regex("""(?i)^(?:my\s+(?:long[- ]term\s+|main\s+|biggest\s+)?goal\s+is\s+(?:to\s+)?|i\s+want\s+to\s+(?:eventually\s+)?(?=become|learn|build|get into|master|launch|start)|i(?:'m| am)\s+(?:trying|planning|aiming)\s+to\s+|i\s+plan\s+to\s+)(.{4,140})$""")
    private val INSTRUCTION = Regex("""(?i)^(?:from now on|going forward|in future|in the future)[,:]?\s+(.{4,140})$|^(always|never)\s+(.{4,140})$""")
    private val LIKES = Regex("""(?i)^i\s+(?:really\s+|absolutely\s+)?(like|love|enjoy|prefer|hate|dislike|can't stand)\s+(.{2,100})$""")
    private val NAME = Regex("""(?i)^(?:my name is|call me|i go by)\s+([A-Z][\p{L}'-]{1,30})$""")

    fun extract(text: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (raw in text.replace('\u2019', '\'').split(SENTENCE_SPLIT)) {
            val s = raw.trim().trim('"', '\'', '`').trimEnd('.', '!', ',', ';').trim()
            if (s.length < 8 || s.length > 260) continue
            candidateFor(s)?.let { out += it }
        }
        return out
    }

    /** Did the user ask JARVIS to remember something (as opposed to asking whether it remembers)? */
    fun asksToSave(text: String): Boolean {
        val t = text.replace('\u2019', '\'').trim()
        if (!SAVE_INTENT.containsMatchIn(t)) return false
        return !t.endsWith("?") || ASKING_TO.containsMatchIn(t.replace(LEAD_IN, ""))
    }

    private val HEDGE_LEAD = Regex("""(?i)^(?:i think|i guess|i suppose|i believe|maybe|probably|perhaps)[, ]+(?:that\s+)?""")
    private val HEDGE_TAIL = Regex("""(?i)[, ]+(?:maybe|probably|perhaps|i think|i guess)$""")

    private fun candidateFor(original: String, explicitDepth: Int = 0): Candidate? {
        val question = original.endsWith("?")
        val uncertain = HEDGE.containsMatchIn(original)
        // Chatty openers ("you know what", "btw") and hedges are removed so the patterns still apply; the hedge is
        // remembered as "uncertain".
        var s = original.trimEnd('?', ' ').trim()
        repeat(4) { s = s.replace(LEAD_IN, "") }
        s = s.replace(HEDGE_LEAD, "").replace(HEDGE_TAIL, "").trim()
        val conf = if (uncertain) 0.4 else 0.75

        if (explicitDepth == 0) {
            val m = EXPLICIT_SAVE.matchEntire(s) ?: EXPLICIT.matchEntire(s)
            if (m != null) {
                val body = clean(m.groupValues[1])
                if (body.length < 4) return null
                return explicitCandidate(body)
            }
        }
        if (question) return null
        if (QUESTION_START.containsMatchIn(s)) return null
        if (TEMPORARY.containsMatchIn(s)) return null

        FAVOURITE.matchEntire(s)?.let { m ->
            val topic = m.groupValues[1].trim().lowercase()
            val value = clean(m.groupValues[2])
            return Candidate(MemoryCategory.FAVOURITE, "favourite $topic", "Favourite $topic is $value", conf, uncertain)
        }
        TECH.matchEntire(s)?.let { m ->
            val what = m.groupValues[1].lowercase()
            return Candidate(MemoryCategory.TECH, "my $what", "Their $what is ${clean(m.groupValues[2])}", conf, uncertain)
        }
        USE.matchEntire(s)?.let { m ->
            return Candidate(MemoryCategory.TECH, null, "Uses ${clean(m.groupValues[1])}", conf, uncertain)
        }
        PROJECT.matchEntire(s)?.let { m ->
            return Candidate(MemoryCategory.PROJECT, null, "Is ${m.groupValues[1].lowercase()} ${clean(m.groupValues[2])}", conf, uncertain)
        }
        PROJECT_NAMED.matchEntire(s)?.let { m ->
            return Candidate(MemoryCategory.PROJECT, null, "Main project: ${clean(m.groupValues[1])}", conf, uncertain)
        }
        DECISION.matchEntire(s)?.let { m ->
            val body = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
            return Candidate(MemoryCategory.DECISION, null, "Decided to ${clean(body)}", conf, uncertain)
        }
        INSTRUCTION.matchEntire(s)?.let { m ->
            val body = if (m.groupValues[1].isNotBlank()) m.groupValues[1] else m.groupValues[2] + " " + m.groupValues[3]
            return Candidate(MemoryCategory.INSTRUCTION, null, sentenceCase(clean(body)), conf, uncertain)
        }
        GOAL.matchEntire(s)?.let { m ->
            return Candidate(MemoryCategory.GOAL, null, "Goal: ${clean(m.groupValues[1])}", conf, uncertain)
        }
        NAME.matchEntire(s)?.let { m ->
            return Candidate(MemoryCategory.PERSONAL, "name", "Name is ${m.groupValues[1]}", 0.9, false)
        }
        LIKES.matchEntire(s)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val obj = clean(m.groupValues[2])
            val text = when (verb) {
                "prefer" -> "Prefers $obj"
                "hate", "dislike", "can't stand" -> "Dislikes $obj"
                else -> "Likes $obj"
            }
            // The same object with the opposite feeling replaces the old record instead of contradicting it.
            return Candidate(MemoryCategory.PREFERENCE, "feeling about ${obj.lowercase()}", text, conf, uncertain)
        }
        return null
    }

    /**
     * The user said "remember/save ... that X". Treated as a normal statement first (so "remember that my favourite colour is
     * blue" updates the same record a plain statement would); otherwise X is stored as a first-person fact, rephrased.
     */
    private fun explicitCandidate(body: String): Candidate? {
        candidateFor(body, explicitDepth = 1)?.let { return it.copy(confidence = 1.0, uncertain = false, explicit = true) }
        val b = body.replace('\u2019', '\'').trim()
        // Too vague to be worth keeping ("the thing", "it"): better to say nothing was saved than to store junk.
        val meaningful = MemoryMatcher.tokens(b) - VAGUE_TOKENS
        if (meaningful.isEmpty()) return null
        HAVE.matchEntire(b)?.let { m ->
            val what = clean(m.groupValues[1])
            val isPhone = PHONE_BRANDS.containsMatchIn(what) && what.split(' ').size <= 4
            val cat = if (isPhone || infer(b).first == MemoryCategory.TECH) MemoryCategory.TECH else MemoryCategory.OTHER
            return Candidate(cat, if (isPhone) "my phone" else null, sentenceCase("has $what"), 1.0, explicit = true)
        }
        AM.matchEntire(b)?.let { m ->
            return Candidate(infer(b).first, null, sentenceCase("is ${clean(m.groupValues[1])}"), 1.0, explicit = true)
        }
        val (cat, key) = infer(b)
        val text = if (b.startsWith("my ", ignoreCase = true)) "Their " + b.drop(3) else b
        return Candidate(cat, key, sentenceCase(clean(text)), 1.0, explicit = true)
    }

    private val CORRECTION_CUE = Regex(
        """(?i)^(?:i\s+take\s+(?:it|that)\s+back|take\s+(?:it|that)\s+back|scratch\s+that|never\s*mind|i\s+meant|i\s+mean|correction|sorry|wait|no|nope|actually|oops|my\s+bad|i\s+was\s+wrong)\b[,:;!.\-\s]*(?:it'?s|it\s+is|that'?s|its|make\s+it|change\s+it\s+to|set\s+it\s+to|it\s+should\s+be|i\s+meant|i\s+mean)?\s*(?:really|actually|now|in\s+fact)?\s*(.{2,60})$""",
    )
    private val CORRECTION_ITS = Regex("""(?i)^(?:it'?s|it\s+is|that'?s)\s+(?:really|actually|now|in\s+fact)\s+(.{2,60})$|^(?:it'?s|it\s+is)\s+(.{2,40}?)\s+now$""")
    private val CORRECTION_CHANGE = Regex("""(?i)^(?:change|set|switch|update)\s+(?:it|that|my\s+[a-z]+(?:\s+[a-z]+)?)\s+to\s+(.{2,60})$|^make\s+it\s+(.{2,40})$""")
    private val CORRECTION_NOT = Regex("""(?i)^(?:it'?s|it\s+is)\s+not\s+.{1,40}?[,;]\s*(?:it'?s|it\s+is)\s+(.{2,40})$""")
    private val NOT_A_VALUE = Regex("""(?i)^(?:i|you|we|he|she|they|that|this|what|why|how|the|thanks|thank|ok|okay|yes|yeah|sure|please)\b""")

    /**
     * A correction that refers back to something said earlier ("I take it back, it's really orange", "make it green",
     * "it's red now"), reduced to the new value. Null if the message is not such a correction. It names no topic: the
     * service finds which saved memory is meant from the recent conversation.
     */
    fun correctionValue(text: String): String? {
        var s = text.replace('\u2019', '\'').trim().trimEnd('.', '!', '?', ' ', ',', ';').trim()
        repeat(4) { s = s.replace(LEAD_IN, "") }
        if (s.endsWith("?") || HEDGE.containsMatchIn(s)) return null
        val groups = listOf(CORRECTION_NOT, CORRECTION_ITS, CORRECTION_CHANGE, CORRECTION_CUE).firstNotNullOfOrNull { re ->
            re.matchEntire(s)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        } ?: return null
        val value = groups.trim().trimEnd('.', '!', ',', ';').trim()
        if (value.isEmpty() || value.split(Regex("""\s+""")).size > 4 || NOT_A_VALUE.containsMatchIn(value)) return null
        return value
    }

    /** Is this a correction of an older fact ("now", "no longer", ...)? */
    fun looksLikeCorrection(text: String): Boolean = CORRECTION.containsMatchIn(text)

    private fun infer(body: String): Pair<MemoryCategory, String?> {
        FAVOURITE.matchEntire(body.trimEnd('.'))?.let { return MemoryCategory.FAVOURITE to "favourite ${it.groupValues[1].trim().lowercase()}" }
        TECH.matchEntire(body.trimEnd('.'))?.let { return MemoryCategory.TECH to "my ${it.groupValues[1].lowercase()}" }
        val l = body.lowercase()
        return when {
            INSTRUCTION.containsMatchIn(body) || l.startsWith("i prefer") || l.startsWith("always ") || l.startsWith("never ") -> MemoryCategory.INSTRUCTION to null
            l.contains("goal") || l.startsWith("i want to") || l.startsWith("i plan to") -> MemoryCategory.GOAL to null
            l.contains("project") || l.contains("working on") || l.contains("building") -> MemoryCategory.PROJECT to null
            l.contains("decided") -> MemoryCategory.DECISION to null
            l.contains("phone") || l.contains("laptop") || l.contains("android") || l.contains("pc ") -> MemoryCategory.TECH to null
            else -> MemoryCategory.OTHER to null
        }
    }

    private fun clean(s: String) = s.trim().trimEnd('.', '!', ',', ';').trim().take(MemoryLimits.MAX_CONTENT_CHARS)

    private fun sentenceCase(s: String) = s.trim().replaceFirstChar { it.uppercase() }.take(MemoryLimits.MAX_CONTENT_CHARS)
}
