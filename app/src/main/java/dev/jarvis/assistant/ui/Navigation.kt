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

/** The two tabs on the AI providers screen. */
enum class ProvidersTab(val title: String) {
    Configure("Configure & Test AI"),
    Choose("Choose Chat Provider"),
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
    /** The tab shown on the AI providers screen. It only decides what is displayed; it never changes any setting. */
    val providersTab: ProvidersTab = ProvidersTab.Configure,
) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Opens [screen]. Re-opening the current screen does nothing; opening one already in the stack goes back to it. */
    fun open(screen: Screen): NavState {
        require(screen != Screen.Provider) { "Use openProvider(provider) for the provider configuration screen" }
        if (screen == current) return this
        // A provider's setup screen is only ever the top entry: opening another screen from it leaves it (it is not
        // kept underneath), so a saved state can never contain a setup screen that is not on top.
        val base = if (current == Screen.Provider) stack.dropLast(1) else stack
        val at = base.indexOf(screen)
        val next = if (at >= 0) base.subList(0, at + 1) else base + screen
        return NavState(next, editing.takeIf { Screen.Provider in next }, if (current == Screen.Provider) ProvidersTab.Configure else providersTab)
    }

    /** Shows [tab] on the AI providers screen. Nothing else changes (and it does nothing while a provider's setup is open). */
    fun selectTab(tab: ProvidersTab): NavState =
        if (current == Screen.Provider) this // the tabs are not on screen while a provider's setup is open
        else copy(providersTab = tab)

    /**
     * Opens the configuration screen of [provider]. If one is already open it shows [provider] instead of stacking.
     * Configuring belongs to the Configure tab, so Back from it returns there.
     */
    fun openProvider(provider: AiProvider): NavState =
        if (current == Screen.Provider) copy(editing = provider)
        else NavState(stack + Screen.Provider, provider, ProvidersTab.Configure)

    /**
     * Back to the screen the user came from. Leaving a provider's "Key and model" screen always lands on the Configure
     * tab (that is where it was opened from), even if the tab was changed while it was open.
     */
    fun back(): NavState =
        if (!canGoBack) this
        else {
            val next = stack.dropLast(1)
            NavState(next, editing.takeIf { Screen.Provider in next }, if (current == Screen.Provider) ProvidersTab.Configure else providersTab)
        }

    /** Back to the chat, forgetting everything in between. */
    fun home(): NavState = NavState()

    fun encode(): String = stack.joinToString(",") { it.name } + "|" + (editing?.name ?: "") + "|" + providersTab.name

    companion object {
        /** Restores a saved state. Anything that does not make sense falls back to the chat instead of crashing. */
        fun decode(text: String?): NavState {
            if (text == null) return NavState()
            val parts = text.split("|")
            // The first version of the saved text had no tab ("stack|editing"); it restores to the Configure tab.
            if (parts.size != 2 && parts.size != 3) return NavState()
            val tab = if (parts.size == 3) ProvidersTab.entries.firstOrNull { it.name == parts[2] } ?: return NavState() else ProvidersTab.Configure
            val stack = parts[0].split(",").map { n -> Screen.entries.firstOrNull { it.name == n } ?: return NavState() }
            val editing = AiProvider.entries.firstOrNull { it.name == parts[1] }
            if (stack.isEmpty() || stack.first() != Screen.Home) return NavState()
            if (stack.distinct().size != stack.size) return NavState()
            if (Screen.Provider in stack && (editing == null || stack.last() != Screen.Provider)) {
                // The provider screen is only valid when it is on top and knows its provider.
                return NavState()
            }
            // A provider's setup screen is only ever opened from the Configure tab, so that is the tab it returns to.
            return NavState(stack, editing.takeIf { Screen.Provider in stack }, if (Screen.Provider in stack) ProvidersTab.Configure else tab)
        }
    }
}
