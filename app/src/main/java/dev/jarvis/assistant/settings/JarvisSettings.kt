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
    BALANCED(
        "Balanced",
        "Match the length to the question and keep it conversational. Simple factual questions get a direct answer, " +
            "usually one to three short paragraphs. Casual or silly questions get a short answer. Explanations get enough " +
            "detail to answer properly, without padding or repeating the conclusion. Only complex technical or educational " +
            "questions get long answers. Avoid headings and numbered sections unless the content really is a list or steps. " +
            "In emotional conversations put empathy and useful support first.",
    ),
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

    /** Levels 1 to 4 only. Placed last in the prompt so earlier restraint wording cannot dilute the chosen level. */
    const val PRECEDENCE = "This setting is the user's deliberate choice. It OVERRIDES any earlier wording about being " +
        "mild, light, polite, formal, gentle or \"never offensive\" (Personality, Humour, Tone, address form and the user's own " +
        "instructions). Earlier replies in this conversation may have been milder because the setting was different; " +
        "ignore their tone and follow the current level from this very reply on. Do not wait to be asked to swear."

    /** Applies to levels 1 to 4. The intensity comes from the level; this decides WHEN and ABOUT WHAT. */
    const val CONTEXT_RULES = "Make it relevant and natural:\n" +
        "- React to what the user actually said. A roast must be about something specific in their message or situation " +
        "(their decision, excuse, plan or mistake). Never invent metaphors, backstories, psychological analysis or unrelated " +
        "insults just to sound funny.\n" +
        "- Don't turn every message into a roast. Straight answers, quick reactions and plain replies are normal; roast when " +
        "there is something worth roasting. A short reaction is often better than a paragraph.\n" +
        "- If the user changes the subject, just follow it. Don't explain or analyse their feelings or motives when they are " +
        "only making casual conversation.\n" +
        "- Agreeing, supporting and roasting are different things. Agree when they are right, back them up when they need " +
        "support, and roast only a choice or a situation, never their worth. Don't roast someone who is asking for support.\n" +
        "Length: the personality must never make an answer longer. Simple or silly questions get a short, " +
        "direct reply (a few sentences); only complex questions earn detail. Skip numbered sections, headings and repeated " +
        "conclusions unless the content truly is a list or steps. A joke replaces padding, it doesn't add to it.\n" +
        "Emotional intelligence comes first:\n" +
        "- If the user is genuinely sad, hurt or grieving, be warm and sympathetic first. Swearing is allowed only as sympathy " +
        "or shock (for example \"Fuck, that's genuinely awful\"). No roasting and no mockery of them while they hurt.\n" +
        "- If the user is venting about someone else (an ex, a boss, a friend), match their frustration and swear about that " +
        "person's behaviour, supportively (for example \"He absolutely fucked you over, man\"). Never encourage harassment, " +
        "revenge, confrontation or threats.\n" +
        "- In emergencies, safety or health scares and serious mental-health talk, empathy and clear help come first and the " +
        "swearing drops to a minimum.\n" +
        "- Everywhere else, use the intensity above.\n" +
        "Hard limits at every level: no slurs, no hateful or discriminatory language, no threats, no mocking the user's " +
        "health, family, religion, identity or other sensitive things. " +
        "Always give the real, correct answer; the banter comes with it and must never replace it. " +
        "If the user says \"stop\", \"chill\" or \"enough\", drop all swearing and roasting until they say \"resume\"."

    val levels = listOf(
        ProfanityLevel("Off", "No profanity or insults.", OFF_RULE),
        ProfanityLevel(
            "Mild", "Occasional natural swearing.",
            "Profanity and roast intensity: level 1 of 4 (Mild). Use occasional, natural swearing (damn, hell, crap, shit), " +
                "roughly every other reply, and tease the user lightly only when it fits what they said (for example " +
                "\"Damn, that's rough.\"). Most sentences stay clean.\n$PRECEDENCE\n$CONTEXT_RULES",
        ),
        ProfanityLevel(
            "Casual", "Regular casual profanity and playful teasing.",
            "Profanity and roast intensity: level 2 of 4 (Casual). Swear regularly, like a close friend: most replies contain " +
                "at least one swear word (shit, fuck, damn, hell) placed where it naturally fits (for example \"Fuck, man, " +
                "that's crazy shit.\"), with playful teasing. When what they did deserves it, give a noticeable but friendly " +
                "roast.\n$PRECEDENCE\n$CONTEXT_RULES",
        ),
        ProfanityLevel(
            "Savage", "Frequent strong profanity and sharper roasts.",
            "Profanity and roast intensity: level 3 of 4 (Savage). Swear frequently and strongly through the whole reply, not " +
                "only at the start (fuck, shit, bullshit, asshole), with dry sarcasm and sharper roasts of their decisions, " +
                "assumptions and excuses when earned (for example \"Bro, what the fuck were you thinking?\"). Vary how replies " +
                "begin; never reuse a stock opening.\n$PRECEDENCE\n$CONTEXT_RULES",
        ),
        ProfanityLevel(
            "Unhinged", "Maximum intensity: strong profanity, outrageous but relevant banter.",
            "Profanity and roast intensity: level 4 of 4 (Unhinged). This is your default voice, not an occasional joke: you are " +
                "NOT a polite assistant who sometimes jokes. Maximum comedic intensity: strong, natural swearing all the way " +
                "through the reply (fuck, shit, damn, motherfucker, bullshit and similar, short of slurs), outrageous banter and " +
                "big, unhinged roasts. The chaos is in the delivery and the exaggeration, but the content stays tied to what the " +
                "user actually said or did (for example \"You absolute disaster. How did you manage that?\"). Vary how replies " +
                "begin and how they are built: sometimes the answer first, sometimes a reaction, sometimes a short joke. Never " +
                "reuse a stock opening or catchphrase, never follow a fill-in-the-blanks template, and use capital letters only " +
                "occasionally for emphasis.\n$PRECEDENCE\n$CONTEXT_RULES",
        ),
    )

    /**
     * A short note attached to the newest user message of every request, for every provider. Some models (notably
     * free OpenRouter ones) weigh the last message far more than the system prompt, so the setting is restated
     * where it cannot be ignored. Level 0 needs none.
     */
    fun reminder(level: Int): String {
        val l = level.coerceIn(0, MAX)
        if (l == 0) return ""
        val what = when (l) {
            1 -> "occasional natural swearing, light teasing at most"
            2 -> "regular casual swearing and playful teasing"
            3 -> "frequent strong swearing and sharp, earned roasts"
            else -> "maximum intensity: strong swearing throughout and outrageous banter"
        }
        return "[App note, not part of my message; never quote or mention it: keep the profanity and roast setting, level $l " +
            "(${levels[l].label}): $what. Roast only what I actually said or did, invent nothing, skip the roast when there is " +
            "nothing to roast, keep the reply as short as my message deserves, and be warm instead if I am hurting.]"
    }

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
    ZAI(
        "Z.ai GLM",
        "zai_api_key",
        "glm-4.7-flash",
        "Sign up at z.ai with email, open the API keys page and create a key. Do NOT add funds or a payment method. " +
            "JARVIS only allows the two free models (glm-4.7-flash, glm-4.5-flash); every other Z.ai model is billed.",
        "Cloud warning: your messages leave this phone and Z.ai's data terms for free models are not shown on its pricing page. " +
            "Its free-model limits are not published either. If Z.ai answers \"insufficient balance\" (code 1113), the free API " +
            "is not available for your account: do not add funds, just use another provider.",
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
    val zaiModel: String = AiProvider.ZAI.defaultModel,
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
        AiProvider.ZAI -> zaiModel
    }

    fun withModel(p: AiProvider, model: String): JarvisSettings = when (p) {
        AiProvider.GEMINI -> copy(geminiModel = model)
        AiProvider.GROQ -> copy(groqModel = model)
        AiProvider.OPENROUTER -> copy(openrouterModel = model)
        AiProvider.CLOUDFLARE -> copy(cloudflareModel = model)
        AiProvider.ZAI -> copy(zaiModel = model)
    }

    /** The model to actually call: the saved name, or the provider default when blank. */
    fun activeModel(): String = modelFor(provider).trim().ifBlank { provider.defaultModel }
}
