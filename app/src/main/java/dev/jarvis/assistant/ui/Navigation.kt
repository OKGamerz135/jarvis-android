package dev.jarvis.assistant.ui

import dev.jarvis.assistant.settings.AiProvider

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

enum class Screen(val title: String) {
    Home("JARVIS"),
    History("Chats"),
    Settings("Settings"),
    PersonalIntelligence("Personal Intelligence"),
    Providers("AI providers"),
    Provider("Key and model"),
    Diagnostics("Diagnostics log"),
    Memory("Memory"),
}

/**
 * A real back stack. Back always returns to the screen the user came from, whichever way they got there (for example
 * the chat's "Open AI providers" button goes back to the chat, not to Settings).
 *
 * [editing] is the provider whose configuration screen is open. It belongs to the [Screen.Provider] entry only and is
 * never the provider used for chat; a Provider screen without a provider cannot exist.
 */
data class NavState(
    val stack: List<Screen> = listOf(Screen.Home),
    val editing: AiProvider? = null,
) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Opens [screen]. Re-opening the current screen does nothing; opening one already in the stack goes back to it. */
    fun open(screen: Screen): NavState {
        require(screen != Screen.Provider) { "Use openProvider(provider) for the provider configuration screen" }
        if (screen == current) return this
        val at = stack.indexOf(screen)
        val next = if (at >= 0) stack.subList(0, at + 1) else stack + screen
        return NavState(next, editing.takeIf { Screen.Provider in next })
    }

    /** Opens the configuration screen of [provider]. If one is already open it shows [provider] instead of stacking. */
    fun openProvider(provider: AiProvider): NavState =
        if (current == Screen.Provider) copy(editing = provider) else NavState(stack + Screen.Provider, provider)

    fun back(): NavState =
        if (!canGoBack) this
        else {
            val next = stack.dropLast(1)
            NavState(next, editing.takeIf { Screen.Provider in next })
        }

    /** Back to the chat, forgetting everything in between. */
    fun home(): NavState = NavState()

    fun encode(): String = stack.joinToString(",") { it.name } + "|" + (editing?.name ?: "")

    companion object {
        /** Restores a saved state. Anything that does not make sense falls back to the chat instead of crashing. */
        fun decode(text: String?): NavState {
            if (text == null) return NavState()
            val parts = text.split("|")
            if (parts.size != 2) return NavState()
            val stack = parts[0].split(",").map { n -> Screen.entries.firstOrNull { it.name == n } ?: return NavState() }
            val editing = AiProvider.entries.firstOrNull { it.name == parts[1] }
            if (stack.isEmpty() || stack.first() != Screen.Home) return NavState()
            if (stack.distinct().size != stack.size) return NavState()
            if (Screen.Provider in stack && (editing == null || stack.last() != Screen.Provider)) {
                // The provider screen is only valid when it is on top and knows its provider.
                return NavState()
            }
            return NavState(stack, editing.takeIf { Screen.Provider in stack })
        }
    }
}
