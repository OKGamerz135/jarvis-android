package dev.jarvis.assistant.memory

/** Word matching used both to find duplicates and to pick the few memories relevant to the current chat. */
object MemoryMatcher {
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "is", "are", "was", "were", "be",
        "it", "this", "that", "i", "me", "my", "you", "your", "we", "our", "they", "he", "she", "do", "does", "did", "can",
        "could", "would", "should", "will", "what", "which", "who", "how", "why", "when", "where", "so", "as", "if", "then",
        "than", "just", "very", "really", "about", "from", "into", "have", "has", "had", "not", "no", "yes", "its", "their",
        "them", "us", "am", "been", "being", "some", "any", "get", "got", "like", "want", "need", "please", "tell", "know",
    )

    fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length > 1 && it !in STOP }
            .map(::stem)
            .toSet()

    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun similarity(a: String, b: String): Double {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return if (a.trim().equals(b.trim(), ignoreCase = true)) 1.0 else 0.0
        val inter = x.intersect(y).size.toDouble()
        return inter / (x.size + y.size - inter)
    }

    /**
     * How well [memory] matches the words of the current conversation. Words of the memory's subject count double.
     * Zero means "not relevant".
     */
    fun score(memory: Memory, query: Set<String>): Double {
        if (query.isEmpty()) return 0.0
        val keyTokens = memory.key?.let(::tokens).orEmpty()
        val bodyTokens = tokens(memory.content)
        var score = 0.0
        for (q in query) {
            if (q in keyTokens) score += 2.0 else if (q in bodyTokens) score += 1.0
        }
        return score
    }
}
