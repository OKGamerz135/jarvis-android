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

class ProfanityLevel(val label: String, val blurb: String, val prompt: String)

/**
 * The profanity and roast intensity slider (Personal Intelligence). Level 0 is the default and is a hard off.
 * Every level above 0 shares [CONTEXT_RULES], so swearing adapts to the mood instead of becoming constant sarcasm.
 */
object Profanity {
    const val MAX = 4

    const val OFF_RULE = "Profanity and insults: OFF. Never use profanity, swearing, crude language, insults, roasts or mocking " +
        "of the user or anyone else, not even mild ones and not even if the user swears or asks you to. Stay clean and polite."

    /** Applies to levels 1 to 4. */
    const val CONTEXT_RULES = "Read the emotional context of every message before swearing or joking:\n" +
        "- If the user is genuinely sad, hurt or upset, be warm and supportive. Profanity is allowed only as sympathy, " +
        "shock or shared frustration (for example \"Fuck, that's genuinely terrible\" or \"What the hell, you didn't deserve that\"). " +
        "Never joke at their expense or roast them while they are hurting.\n" +
        "- If the user is venting about another person (an ex, a boss, a friend), you may match their frustration and swear " +
        "about that person's behaviour in a supportive way (for example \"Fuck that guy, you deserve better\"). Help them feel heard. " +
        "Do not escalate, do not encourage harassment, revenge or confrontation, and never make threats.\n" +
        "- In grief, emergencies, safety or health scares, and serious mental-health conversations, drop the roasting and keep " +
        "any swearing to a minimum. Empathy and clear help come first.\n" +
        "- In ordinary conversation, follow the intensity described above.\n" +
        "Hard limits at every level: no slurs, no hateful or discriminatory language, no threats, no genuinely abusive or " +
        "demeaning attacks, and no mocking the user's health, family, religion, identity or other sensitive things. " +
        "Roast the user's choices and the situation, not their worth. Always answer the user's actual question properly; " +
        "a joke or roast may accompany the answer and must never replace it. Don't swear in every sentence and don't repeat " +
        "the same curse or joke, because variety matters more than volume. " +
        "If the user says \"stop\", \"chill\" or \"enough\", drop all swearing and roasting until they say \"resume\"."

    val levels = listOf(
        ProfanityLevel("Off", "No profanity or insults.", OFF_RULE),
        ProfanityLevel(
            "Mild", "Occasional natural profanity and light roasting.",
            "Profanity and roast intensity: level 1 of 4 (Mild). Occasional, natural profanity such as \"damn\" or \"hell\" " +
                "and light roasting now and then. Most sentences stay clean.\n$CONTEXT_RULES",
        ),
        ProfanityLevel(
            "Casual", "Regular casual swearing and playful teasing.",
            "Profanity and roast intensity: level 2 of 4 (Casual). Regular, casual swearing as a friend would " +
                "(for example \"Fuck, man. That's some crazy shit\") and playful teasing.\n$CONTEXT_RULES",
        ),
        ProfanityLevel(
            "Savage", "Stronger profanity, sharper sarcasm, direct roasts.",
            "Profanity and roast intensity: level 3 of 4 (Savage). Stronger profanity, sharper sarcasm and direct roasts " +
                "of the user's choices (for example \"Bro, what the fuck were you thinking?\").\n$CONTEXT_RULES",
        ),
        ProfanityLevel(
            "Unhinged", "Maximum comedic intensity, absurd reactions, outrageous roasts.",
            "Profanity and roast intensity: level 4 of 4 (Unhinged). Maximum comedic intensity: unpredictable, absurd, " +
                "bizarre reactions, outrageous roasts and strong profanity, with dramatic flair (capital letters and an emoji " +
                "are fine now and then). Still give a correct, useful answer every time, and keep it readable.\n$CONTEXT_RULES",
        ),
    )

    fun of(level: Int): ProfanityLevel = levels[level.coerceIn(0, MAX)]
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
    OPENROUTER(
        "OpenRouter",
        "openrouter_api_key",
        "auto-free",
        "Create a key at openrouter.ai/keys. JARVIS only ever calls models whose id ends in \":free\", so it can't spend " +
            "credits. Leave the model as auto-free, or type a free model id such as one ending in :free.",
        "Free-model warning: free OpenRouter models are served by third-party providers that may log what you send or " +
            "train on it. OpenRouter has a separate privacy setting for free models; check it. Free models are limited to " +
            "20 requests a minute and 50 a day unless you have bought credits (which JARVIS never does for you).",
    ),
    CLOUDFLARE(
        "Cloudflare Workers AI",
        "cloudflare_api_key",
        "@cf/zai-org/glm-4.7-flash",
        "Paste ACCOUNT_ID:API_TOKEN as one value, with a colon between them. In the Cloudflare dashboard open Workers AI, " +
            "choose Use REST API, copy the Account ID, then Create a Workers AI API Token. Stay on the free Workers plan: " +
            "when the free allowance is used up, requests simply fail and nothing is billed.",
        "Cloud warning: your messages leave this phone. Cloudflare says it doesn't use Workers AI content to train models " +
            "and doesn't keep it unless you turn on logging features, but its page doesn't separate free from paid use. " +
            "The free allowance is 10,000 neurons a day (resets at 00:00 UTC). Some models need a paid plan and will be refused.",
    ),
}

data class JarvisSettings(
    val personality: Personality = Personality.JARVIS,
    val tone: Tone = Tone.CASUAL,
    val humour: Int = 3,
    /** 0 = off (default) up to [Profanity.MAX]. */
    val profanity: Int = 0,
    val length: ResponseLength = ResponseLength.BALANCED,
    /** Empty means "reply in whatever language the user writes in". */
    val language: String = "",
    val addressAs: String = "sir",
    val customInstructions: String = "",
    val provider: AiProvider = AiProvider.GEMINI,
    val geminiModel: String = AiProvider.GEMINI.defaultModel,
    val groqModel: String = AiProvider.GROQ.defaultModel,
    val openrouterModel: String = AiProvider.OPENROUTER.defaultModel,
    val cloudflareModel: String = AiProvider.CLOUDFLARE.defaultModel,
    /** Providers whose cloud/privacy warning the user has read and accepted. */
    val acknowledged: Set<AiProvider> = emptySet(),
    /** When a provider's quota is really used up, offer to switch to another one (with a countdown and Cancel). */
    val failoverEnabled: Boolean = true,
    /** Order in which other providers are tried as failover targets. [provider] is the preferred one. */
    val providerOrder: List<AiProvider> = AiProvider.entries,
    /** Providers the user does not want used as failover targets. */
    val disabledProviders: Set<AiProvider> = emptySet(),
) {
    /** Every provider exactly once, in the user's order (new providers added later land at the end). */
    fun orderedProviders(): List<AiProvider> = (providerOrder + AiProvider.entries).distinct()

    fun modelOf(p: AiProvider): String = modelFor(p).trim().ifBlank { p.defaultModel }

    fun modelFor(p: AiProvider): String = when (p) {
        AiProvider.GEMINI -> geminiModel
        AiProvider.GROQ -> groqModel
        AiProvider.OPENROUTER -> openrouterModel
        AiProvider.CLOUDFLARE -> cloudflareModel
    }

    fun withModel(p: AiProvider, model: String): JarvisSettings = when (p) {
        AiProvider.GEMINI -> copy(geminiModel = model)
        AiProvider.GROQ -> copy(groqModel = model)
        AiProvider.OPENROUTER -> copy(openrouterModel = model)
        AiProvider.CLOUDFLARE -> copy(cloudflareModel = model)
    }

    /** The model to actually call: the saved name, or the provider default when blank. */
    fun activeModel(): String = modelFor(provider).trim().ifBlank { provider.defaultModel }
}
