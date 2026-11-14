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

    private val EXPLICIT = Regex("""(?i)^(?:please\s+)?(?:remember|note|keep in mind|don't forget|dont forget)(?:\s+that)?\s*[:,]?\s+(.{4,})$""")
    private val FAVOURITE = Regex("""(?i)^my\s+(?:all[- ]time\s+)?favou?rite\s+([a-z][a-z \-]{1,30}?)\s+(?:is|are|was)\s+(?:now\s+)?(.{1,100})$""")
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
            if (s.endsWith("?")) continue
            candidateFor(s)?.let { out += it }
        }
        return out
    }

    private val HEDGE_LEAD = Regex("""(?i)^(?:i think|i guess|i suppose|i believe|maybe|probably|perhaps)[, ]+(?:that\s+)?""")
    private val HEDGE_TAIL = Regex("""(?i)[, ]+(?:maybe|probably|perhaps|i think|i guess)$""")

    private fun candidateFor(original: String): Candidate? {
        val uncertain = HEDGE.containsMatchIn(original)
        // The hedge is remembered as "uncertain"; the sentence is matched without it so the patterns still apply.
        val s = original.replace(HEDGE_LEAD, "").replace(HEDGE_TAIL, "").trim()
        val conf = if (uncertain) 0.4 else 0.75

        EXPLICIT.matchEntire(s)?.let { m ->
            val body = clean(m.groupValues[1])
            if (body.length < 4) return null
            val (cat, key) = infer(body)
            return Candidate(cat, key, sentenceCase(body), if (uncertain) 0.5 else 1.0, uncertain = false, explicit = true)
        }
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
