package dev.jarvis.assistant.ui

import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** C-08: back always returns to where the user came from; saved state can never produce an impossible screen. */
class NavStateTest {
    @Test fun startsAtTheChatWithNothingToGoBackTo() {
        val n = NavState()
        assertEquals(Screen.Home, n.current); assertFalse(n.canGoBack)
        assertEquals(n, n.back())
    }

    @Test fun backFromProvidersOpenedFromTheChatGoesToTheChatNotSettings() {
        val n = NavState().open(Screen.Providers)
        assertEquals(Screen.Home, n.back().current)
    }

    @Test fun backFromProvidersOpenedFromSettingsGoesToSettings() {
        val n = NavState().open(Screen.Settings).open(Screen.Providers)
        assertEquals(Screen.Settings, n.back().current)
        assertEquals(Screen.Home, n.back().back().current)
    }

    @Test fun backFromAProviderScreenGoesToTheListAndForgetsTheProvider() {
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GROQ.toId())
        assertEquals(Screen.Provider, n.current); assertEquals(AiProvider.GROQ.toId(), n.editing)
        val b = n.back()
        assertEquals(Screen.Providers, b.current); assertNull(b.editing)
    }

    @Test fun openingAnotherProviderFromAProviderScreenReplacesItSoBackIsNotAMaze() {
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GEMINI.toId()).openProvider(AiProvider.GROQ.toId())
        assertEquals(AiProvider.GROQ.toId(), n.editing)
        assertEquals(Screen.Providers, n.back().current)
    }

    @Test fun reopeningTheCurrentScreenDoesNothingAndOpeningOneInTheStackReturnsToIt() {
        val n = NavState().open(Screen.Settings)
        assertEquals(n, n.open(Screen.Settings))
        val deep = n.open(Screen.Memory)
        assertEquals(n, deep.open(Screen.Settings))
    }

    @Test fun homeForgetsEverything() {
        assertEquals(NavState(), NavState().open(Screen.Settings).open(Screen.Providers).openProvider(AiProvider.GROQ.toId()).home())
    }

    @Test fun providerScreenCannotBeOpenedWithoutAProvider() {
        assertThrows(IllegalArgumentException::class.java) { NavState().open(Screen.Provider) }
    }

    @Test fun savedStateRoundTrips() {
        for (n in listOf(
            NavState(),
            NavState().open(Screen.Settings),
            NavState().open(Screen.Settings).open(Screen.Providers).openProvider(AiProvider.CLOUDFLARE.toId()),
        )) assertEquals(n, NavState.decode(n.encode()))
    }

    @Test fun garbageOrImpossibleSavedStateFallsBackToTheChat() {
        val home = NavState()
        for (bad in listOf(
            null, "", "|", "garbage", "Home|", "Nope,Home|", "Settings|", "Home,Settings,Settings|",
            "Home,Provider|", "Home,Provider|NOT_A_PROVIDER", "Home,Provider,Settings|GROQ", "Home,Providers|GROQ,x|y",
        )) assertEquals("for '$bad'", home, NavState.decode(bad))
    }

    @Test fun editingIsOnlyKeptWhileTheProviderScreenIsInTheStack() {
        val n = NavState.decode("Home,Settings|GROQ")
        assertNull(n.editing)
        assertTrue(n.stack == listOf(Screen.Home, Screen.Settings))
    }

    // ------------------------------------------------------------------ the two tabs on the AI providers screen

    @Test fun startsOnTheConfigureTab() = assertEquals(ProvidersTab.Configure, NavState().providersTab)

    @Test fun selectingATabChangesOnlyTheTab() {
        val n = NavState().open(Screen.Providers)
        val c = n.selectTab(ProvidersTab.Choose)
        assertEquals(ProvidersTab.Choose, c.providersTab)
        assertEquals(n.stack, c.stack); assertEquals(n.editing, c.editing)
    }

    @Test fun theTabSurvivesLeavingAndComingBack() {
        val n = NavState().open(Screen.Settings).open(Screen.Providers).selectTab(ProvidersTab.Choose)
        assertEquals(ProvidersTab.Choose, n.back().providersTab) // still remembered after Back to Settings
        assertEquals(ProvidersTab.Choose, n.back().open(Screen.Providers).providersTab)
    }

    @Test fun openingProviderSetupSwitchesToConfigureSoBackReturnsThere() {
        val n = NavState().open(Screen.Providers).selectTab(ProvidersTab.Choose).openProvider(AiProvider.GROQ.toId())
        assertEquals(ProvidersTab.Configure, n.providersTab)
        assertEquals(ProvidersTab.Configure, n.back().providersTab)
        assertEquals(Screen.Providers, n.back().current)
    }

    @Test fun tabRoundTripsAndOldSavedTextStillRestores() {
        val n = NavState().open(Screen.Providers).selectTab(ProvidersTab.Choose)
        assertEquals(n, NavState.decode(n.encode()))
        val old = NavState.decode("Home,Providers|") // the format from before tabs existed
        assertEquals(listOf(Screen.Home, Screen.Providers), old.stack)
        assertEquals(ProvidersTab.Configure, old.providersTab)
    }

    @Test fun anUnknownSavedTabFallsBackToTheChat() {
        assertEquals(NavState(), NavState.decode("Home,Providers||Nope"))
    }

    @Test fun selectingATabNeverTouchesTheProviderBeingEdited() {
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GROQ.toId()).selectTab(ProvidersTab.Choose)
        assertEquals(AiProvider.GROQ.toId(), n.editing)
    }

    // ------------------------------------------------------------------ B7: restoration and Back from provider setup

    @Test fun backFromProviderSetupAlwaysLandsOnTheConfigureTab() {
        val n = NavState().open(Screen.Providers).selectTab(ProvidersTab.Choose).openProvider(AiProvider.GROQ.toId()).selectTab(ProvidersTab.Choose)
        val b = n.back()
        assertEquals(Screen.Providers, b.current)
        assertEquals(ProvidersTab.Configure, b.providersTab)
        assertNull(b.editing)
    }

    @Test fun backFromProvidersKeepsTheChosenTabForNextTime() {
        val n = NavState().open(Screen.Settings).open(Screen.Providers).selectTab(ProvidersTab.Choose)
        assertEquals(ProvidersTab.Choose, n.back().providersTab)
    }

    @Test fun savedStateWithProviderSetupAlwaysRestoresToTheConfigureTab() {
        val tampered = NavState.decode("Home,Providers,Provider|GROQ|Choose")
        assertEquals(Screen.Provider, tampered.current)
        assertEquals(AiProvider.GROQ.toId(), tampered.editing)
        assertEquals(ProvidersTab.Configure, tampered.providersTab)
        assertEquals(ProvidersTab.Configure, tampered.back().providersTab)
    }

    @Test fun theChooseTabIsRestoredWhenNoSetupScreenIsOpen() {
        val n = NavState.decode(NavState().open(Screen.Providers).selectTab(ProvidersTab.Choose).encode())
        assertEquals(Screen.Providers, n.current); assertEquals(ProvidersTab.Choose, n.providersTab)
    }

    @Test fun everyReachableStateSurvivesRotationAndProcessRecreation() {
        // Explores every state reachable with up to 4 actions and checks the saved text restores it exactly.
        val actions: List<(NavState) -> NavState> =
            Screen.entries.filter { it != Screen.Provider }.map { s -> { n: NavState -> n.open(s) } } +
                AiProvider.entries.map { p -> { n: NavState -> n.openProvider(p.toId()) } } +
                ProvidersTab.entries.map { t -> { n: NavState -> n.selectTab(t) } } +
                listOf({ n: NavState -> n.back() }, { n: NavState -> n.home() })
        var frontier = setOf(NavState())
        val seen = HashSet<NavState>(frontier)
        repeat(4) {
            frontier = frontier.flatMap { n -> actions.map { it(n) } }.filter { seen.add(it) }.toSet()
        }
        assertTrue(seen.size > 100)
        for (n in seen) {
            assertEquals(n, NavState.decode(n.encode()))
            if (Screen.Provider in n.stack) {
                assertEquals(Screen.Provider, n.current)               // only ever on top
                assertTrue(n.editing != null)                           // always knows its provider
                assertEquals(ProvidersTab.Configure, n.providersTab)    // and returns to Configure
            } else assertNull(n.editing)
        }
    }

    @Test fun savedTextNeverContainsAnythingButScreensProvidersAndTabs() {
        // Nothing secret can be in the saved navigation state: it is built only from enum names.
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GEMINI.toId())
        assertTrue(n.encode().all { it.isLetter() || it == ',' || it == '|' })
    }

    @Test fun openingAnotherScreenFromProviderSetupLeavesTheSetupScreen() {
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GROQ.toId()).open(Screen.Memory)
        assertEquals(listOf(Screen.Home, Screen.Providers, Screen.Memory), n.stack)
        assertNull(n.editing)
        assertEquals(Screen.Providers, n.back().current)
    }

    @Test fun tabsCannotBeChangedWhileProviderSetupIsOpen() {
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GROQ.toId())
        assertEquals(n, n.selectTab(ProvidersTab.Choose))
    }

    // ------------------------------------------------------------------ C2e: navigation is built-in-only until C4

    private val customId = "custom:123e4567-e89b-12d3-a456-426614174000"

    @Test fun aCustomProviderCannotBeOpenedYet() {
        val custom = ProviderId.parse(customId)!!
        assertTrue(custom.isCustom)
        assertThrows(IllegalArgumentException::class.java) { NavState().open(Screen.Providers).openProvider(custom) }
    }

    @Test fun aSavedStateNamingACustomProviderRestoresToTheChat() {
        val home = NavState()
        for (bad in listOf(
            "Home,Providers,Provider|$customId|Configure", // valid custom id, setup screen on top
            "Home,Providers,Provider|$customId",            // old two-part form
            "Home,Settings|$customId|Configure",            // custom id even though no setup screen is in the stack
            "Home,Providers,Provider|${customId.uppercase()}|Configure",
        )) assertEquals("for '$bad'", home, NavState.decode(bad))
    }

    @Test fun anUnknownOrMalformedSavedProviderRestoresToTheChatNeverToAnotherProvider() {
        val home = NavState()
        for (bad in listOf(
            "Home,Providers,Provider|SAMBANOVA|Configure", "Home,Settings|SAMBANOVA|Configure",
            "Home,Providers,Provider|groq|Configure", "Home,Providers,Provider| GROQ|Configure",
            "Home,Providers,Provider|GROQ |Configure", "Home,Providers,Provider|custom:|Configure",
            "Home,Providers,Provider|custom:not-a-uuid|Configure", "Home,Providers,Provider|GROQ:x|Configure",
        )) assertEquals("for '$bad'", home, NavState.decode(bad))
    }

    @Test fun everyBuiltInProviderScreenRestoresToItselfAndSavedTextIsTheEnumName() {
        for (p in AiProvider.entries) {
            val n = NavState().open(Screen.Providers).openProvider(p.toId())
            assertEquals("Home,Providers,Provider|${p.name}|Configure", n.encode())
            val back = NavState.decode(n.encode())
            assertEquals(p.toId(), back.editing)
            assertEquals(n, back)
        }
    }

    @Test fun savedTextFromBuild46And47StillRestoresExactly() {
        // Build 46/47 wrote the enum name; the tab-less two-part form is older still.
        val n = NavState.decode("Home,Providers,Provider|GROQ|Configure")
        assertEquals(listOf(Screen.Home, Screen.Providers, Screen.Provider), n.stack)
        assertEquals(AiProvider.GROQ.toId(), n.editing)
        assertEquals(n, NavState.decode("Home,Providers,Provider|GROQ"))
    }
}
