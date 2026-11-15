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
    /** "save that in memory", "remember that", "store it to memory": nothing to remember is named, so it points at an earlier message. */
    private val REF_SAVE_MEM = Regex(
        """(?i)^$ASK(?:save|store|add|put|write(?:\s+down)?|log|keep|memori[sz]e|remember|note(?:\s+down)?)\s+(?:that|this|it)\s+(?:in|into|to|on)\s+(?:your\s+|my\s+|the\s+)?(?:long[- ]term\s+)?(?:memory|memories|notes)(?:\s+(?:please|now))?$""",
    )
    private val REF_SAVE_BARE = Regex("""(?i)^$ASK(?:remember|memori[sz]e|note|don't forget|dont forget)\s+(?:that|this|it)(?:\s+(?:please|now))?$""")
    private val FAV_REF = Regex("""(?i)^(?:that'?s|that\s+is|it'?s|it\s+is|this\s+is|they'?re|those\s+are)\s+(?:also\s+|really\s+|definitely\s+|easily\s+)?my\s+(?:all[- ]time\s+)?fav(?:ou?rite)?\s+([a-z][a-z \-]{1,30})$""")
    private val FAV_REV = Regex("""(?i)^(.{2,60}?)\s+(?:is|are)\s+my\s+(?:all[- ]time\s+)?fav(?:ou?rite)?\s+([a-z][a-z \-]{1,30})$""")
    private val BOUGHT = Regex("""(?i)^i\s+(?:just\s+|recently\s+)?(?:bought|got|purchased|ordered|switched\s+to|moved\s+to|started\s+using|am\s+using|'m\s+using)\s+(.{2,100})$""")
    private val ARTICLE = Regex("""(?i)^(?:an?|the|my)\s+""")
    private val NOT_TOPIC_WORDS = setOf("late", "early", "raining", "rainy", "cold", "hot", "cloudy", "sunny", "dark", "noon", "midnight", "morning", "night", "evening", "bedtime", "okay", "fine", "good", "bad")
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

    /** What one user message means for memory, sentence by sentence, with the earlier turns available. */
    data class Analysis(
        val candidates: List<Candidate>,
        /** A topic-less correction ("it's orange now"), reduced to the new value. */
        val correction: String?,
        /** "Save that in memory": nothing named, so it points at an earlier user message. */
        val referencesPrevious: Boolean,
    )

    fun extract(text: String): List<Candidate> = analyze(text).candidates

    /**
     * The single entry point. Every sentence is looked at on its own (so "I change my mind. It's orange now." works),
     * and [recent] (the turns before this message) is used for words that point backwards: "that's my favourite phone",
     * "save that". Local rules only: nothing is sent to any provider.
     */
    fun analyze(text: String, recent: List<ChatTurn> = emptyList()): Analysis {
        val candidates = mutableListOf<Candidate>()
        var correction: String? = null
        var ref = false
        for (raw in text.replace('\u2019', '\'').split(SENTENCE_SPLIT)) {
            val s = raw.trim().trim('"', '\'', '`').trimEnd('.', '!', ',', ';').trim()
            if (s.length < 4 || s.length > 260) continue
            var plain = s
            repeat(4) { plain = plain.replace(LEAD_IN, "") }
            plain = plain.trimEnd('?', ' ', '.', '!').trim()
            if (REF_SAVE_MEM.matches(plain) || REF_SAVE_BARE.matches(plain)) { ref = true; continue }
            if (s.length >= 8) {
                val c = candidateFor(s, recent = recent)
                if (c != null) { candidates += c; continue }
            }
            if (correction == null) correction = correctionValue(s)
        }
        if (candidates.isEmpty() && correction == null && !ref) correction = correctionValue(text)
        return Analysis(candidates, correction, ref)
    }

    /** A first-person statement in an earlier message that "save that" can store ("I've an iQOO 13" becomes "Has an iQOO 13"). */
    fun statementCandidate(text: String): Candidate? {
        for (raw in text.replace('\u2019', '\'').split(SENTENCE_SPLIT)) {
            var s = raw.trim().trimEnd('.', '!', ',', ';', ' ').trim()
            repeat(4) { s = s.replace(LEAD_IN, "") }
            if (s.length < 8 || s.endsWith("?") || QUESTION_START.containsMatchIn(s) || TEMPORARY.containsMatchIn(s)) continue
            if (!Regex("""(?i)^(?:i|i'm|i've|my|we|our)\b""").containsMatchIn(s)) continue
            candidateFor(s)?.let { return it }
            explicitCandidate(s)?.let { return it }
        }
        return null
    }

    /** The thing an earlier user message talked about ("iQOO 13" from "I've an iQOO 13"), for "that's my favourite phone". */
    private fun entityIn(raw: String): String? {
        for (part in raw.replace('\u2019', '\'').split(SENTENCE_SPLIT)) {
            var s = part.trim().trimEnd('.', '!', ',', ';', ' ').trim()
            repeat(4) { s = s.replace(LEAD_IN, "") }
            val v = HAVE.matchEntire(s)?.groupValues?.get(1)
                ?: BOUGHT.matchEntire(s)?.groupValues?.get(1)
                ?: USE.matchEntire(s)?.groupValues?.get(1)
                ?: TECH.matchEntire(s)?.groupValues?.get(2)
                ?: FAVOURITE.matchEntire(s)?.groupValues?.get(2)
                ?: continue
            val e = clean(v).replace(ARTICLE, "").trim()
            if (e.isNotEmpty() && e.split(Regex("""\s+""")).size <= 6) return e
        }
        return null
    }

    /** Did the user ask JARVIS to remember something (as opposed to asking whether it remembers)? */
    fun asksToSave(text: String): Boolean {
        val t = text.replace('\u2019', '\'').trim()
        if (!SAVE_INTENT.containsMatchIn(t)) return false
        return !t.endsWith("?") || ASKING_TO.containsMatchIn(t.replace(LEAD_IN, ""))
    }

    private val HEDGE_LEAD = Regex("""(?i)^(?:i think|i guess|i suppose|i believe|maybe|probably|perhaps)[, ]+(?:that\s+)?""")
    private val HEDGE_TAIL = Regex("""(?i)[, ]+(?:maybe|probably|perhaps|i think|i guess)$""")

    private fun candidateFor(original: String, explicitDepth: Int = 0, recent: List<ChatTurn> = emptyList()): Candidate? {
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
        // "iQOO 13 is my favourite phone": the thing is in the same sentence.
        FAV_REV.matchEntire(s)?.let { m ->
            val value = clean(m.groupValues[1]).replace(ARTICLE, "").trim()
            val topic = m.groupValues[2].trim().lowercase()
            if (value.isNotEmpty() && value.split(Regex("""\s+""")).size <= 6) {
                return Candidate(MemoryCategory.FAVOURITE, "favourite $topic", "Favourite $topic is $value", conf, uncertain)
            }
        }
        // "That's my favourite phone": the thing is in an earlier message of the user's.
        FAV_REF.matchEntire(s)?.let { m ->
            val topic = m.groupValues[1].trim().lowercase()
            val value = recent.asReversed().filter { it.fromUser }.take(3).firstNotNullOfOrNull { entityIn(it.text) }
                ?: return null
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
        """(?i)^(?:i\s+take\s+(?:it|that)\s+back|take\s+(?:it|that)\s+back|scratch\s+that|never\s*mind|i\s+(?:change|changed)\s+my\s+mind|(?:i\s+)?changed\s+my\s+mind|on\s+second\s+thoughts?|change\s+of\s+heart|i\s+meant|i\s+mean|correction|sorry|wait|no|nope|actually|oops|my\s+bad|i\s+was\s+wrong)\b[,:;!.\-\s]*(?:it'?s|it\s+is|that'?s|its|make\s+it|change\s+it\s+to|set\s+it\s+to|it\s+should\s+be|i\s+meant|i\s+mean)?\s*(?:really|actually|now|in\s+fact)?\s*(.{2,60})$""",
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
            .replace(Regex("""(?i)\s+now$"""), "").replace(Regex("""(?i)^now\s+"""), "").trim()
        if (value.lowercase() in NOT_TOPIC_WORDS) return null
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
