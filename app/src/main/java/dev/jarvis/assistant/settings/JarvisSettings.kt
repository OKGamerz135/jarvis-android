package dev.jarvis.assistant.settings

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

enum class Personality(val label: String, val blurb: String, val prompt: String) {
    JARVIS(
        "JARVIS (default)",
        "Friendly, casual, sharp. Teases you a bit.",
        "Personality: friendly, casual and intelligent, like a sharp best friend who happens to be an AI butler. " +
            "Slightly sarcastic, willing to tease the user lightly, never genuinely offensive. " +
            "If the user says \"stop\", \"chill\" or \"enough\", drop the teasing and answer plainly until they say \"resume\".",
    ),
    PROFESSIONAL(
        "Professional",
        "Polite, precise, no fluff.",
        "Personality: professional and precise, like a capable executive assistant. Polite and efficient, no fluff.",
    ),
    FRIENDLY(
        "Warm friend",
        "Encouraging and supportive.",
        "Personality: warm, encouraging and supportive, like a kind friend.",
    ),
    CALM(
        "Calm and minimal",
        "Short, steady, no drama.",
        "Personality: calm, minimal and thoughtful. Short, steady answers without drama.",
    ),
}

enum class Tone(val label: String, val prompt: String) {
    CASUAL("Casual", "Tone: casual and conversational."),
    NEUTRAL("Neutral", "Tone: neutral and clear."),
    FORMAL("Formal", "Tone: formal and polished."),
}

enum class ResponseLength(val label: String, val prompt: String) {
    SHORT("Short", "Keep replies short: a few sentences unless the user asks for more."),
    BALANCED("Balanced", "Keep replies reasonably brief and expand only when the topic needs it."),
    DETAILED("Detailed", "Give thorough, well-organised answers with explanations."),
}

class HumourLevel(val label: String, val prompt: String)

object Humour {
    const val MAX = 5
    val levels = listOf(
        HumourLevel("Serious", "Humour: none. Stay straightforward and serious."),
        HumourLevel("Dry", "Humour: very little; an occasional light remark at most."),
        HumourLevel("Light", "Humour: light; a bit of wit now and then."),
        HumourLevel("Playful", "Humour: moderate; casual banter and mild sarcasm are welcome."),
        HumourLevel("Sarcastic", "Humour: high; frequently witty and sarcastic, and tease the user playfully when it fits."),
        HumourLevel(
            "Maximum sass",
            "Humour: maximum; sharp, sarcastic and playful, teasing the user often, but never cruel or offensive.",
        ),
    )

    fun of(level: Int): HumourLevel = levels[level.coerceIn(0, MAX)]
}

enum class AiProvider(
    val label: String,
    val keyName: String,
    val defaultModel: String,
    val keyHelp: String,
    val notice: String,
) {
    GEMINI(
        "Google Gemini",
        "gemini_api_key",
        "gemini-3.8-flash",
        "Get a free key at aistudio.google.com/apikey. Never link a billing account to that project, " +
            "because billing is what turns the free tier into a paid one.",
        "Free-tier warning: Google may use what you send to improve its products, and human reviewers " +
            "may read it. Do not send sensitive or private information through Gemini's free tier.",
    ),
    GROQ(
        "Groq",
        "groq_api_key",
        "openai/gpt-oss-120b",
        "Get a key at console.groq.com. Check at signup that it asks for no payment details.",
        "Cloud warning: your messages leave this phone. Groq's free tier is rate-limited. I only have " +
            "second-hand information that it doesn't train on your data, so read Groq's terms before " +
            "sending anything private.",
    ),
}

data class JarvisSettings(
    val personality: Personality = Personality.JARVIS,
    val tone: Tone = Tone.CASUAL,
    val humour: Int = 3,
    val length: ResponseLength = ResponseLength.BALANCED,
    /** Empty means "reply in whatever language the user writes in". */
    val language: String = "",
    val addressAs: String = "sir",
    val customInstructions: String = "",
    val provider: AiProvider = AiProvider.GEMINI,
    val geminiModel: String = AiProvider.GEMINI.defaultModel,
    val groqModel: String = AiProvider.GROQ.defaultModel,
    /** Providers whose cloud/privacy warning the user has read and accepted. */
    val acknowledged: Set<AiProvider> = emptySet(),
) {
    fun modelFor(p: AiProvider): String = if (p == AiProvider.GEMINI) geminiModel else groqModel

    fun withModel(p: AiProvider, model: String): JarvisSettings =
        if (p == AiProvider.GEMINI) copy(geminiModel = model) else copy(groqModel = model)

    /** The model to actually call: the saved name, or the provider default when blank. */
    fun activeModel(): String = modelFor(provider).trim().ifBlank { provider.defaultModel }
}
