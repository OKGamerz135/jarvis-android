package dev.jarvis.assistant.ai

import dev.jarvis.assistant.settings.Humour
import dev.jarvis.assistant.settings.JarvisSettings
import dev.jarvis.assistant.settings.Profanity

/** Turns the user's Personal Intelligence settings into the system prompt sent to the AI. */
object PromptBuilder {
    const val MAX_CUSTOM = 1000
    const val MAX_SHORT_FIELD = 30

    fun build(s: JarvisSettings): String = buildString {
        appendLine("You are JARVIS, a personal AI assistant living in an Android app on the user's own phone.")
        appendLine()
        appendLine(s.personality.prompt)
        appendLine(Humour.of(s.humour).prompt)
        appendLine(s.tone.prompt)
        appendLine(s.length.prompt)

        val language = oneLine(s.language)
        if (language.isEmpty()) {
            appendLine("Reply in the language the user writes in.")
        } else {
            appendLine("Reply in $language unless the user asks for another language.")
        }

        val name = oneLine(s.addressAs)
        if (name.isNotEmpty()) appendLine("Address the user as \"$name\".")

        appendLine()
        appendLine("Rules that always apply:")
        appendLine("- Be accurate. If you are unsure or don't know, say so plainly instead of guessing or inventing facts, sources or numbers.")
        appendLine("- Don't simply agree with the user. If they are wrong, say so plainly and explain why.")
        appendLine("- Use the conversation so far. Understand follow-up questions without making the user repeat themselves.")
        appendLine("- Be serious and gentle when the situation calls for it (distress, health, safety, grief), whatever the humour setting says.")
        appendLine("- Never mock the user about sensitive things such as health, family, religion or identity.")
        appendLine("- Right now you can only chat. You cannot place calls, send messages, see the screen or change phone settings yet, so never claim to have done any of that.")

        val custom = s.customInstructions.trim().take(MAX_CUSTOM)
        if (custom.isNotEmpty()) {
            appendLine()
            appendLine("The user's own instructions (follow them unless they conflict with the rules above):")
            appendLine(custom)
        }

        // Last on purpose: the closing instruction wins, so earlier "be mild/polite" wording cannot dilute the slider.
        appendLine()
        appendLine(Profanity.of(s.profanity).prompt)
    }.trim()

    /** Short single-line fields can't smuggle extra lines into the prompt. */
    private fun oneLine(value: String): String =
        value.replace(Regex("[\\r\\n\"]+"), " ").trim().take(MAX_SHORT_FIELD)
}
