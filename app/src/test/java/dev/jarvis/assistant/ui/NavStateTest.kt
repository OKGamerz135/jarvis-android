package dev.jarvis.assistant.ui

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
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GROQ)
        assertEquals(Screen.Provider, n.current); assertEquals(AiProvider.GROQ, n.editing)
        val b = n.back()
        assertEquals(Screen.Providers, b.current); assertNull(b.editing)
    }

    @Test fun openingAnotherProviderFromAProviderScreenReplacesItSoBackIsNotAMaze() {
        val n = NavState().open(Screen.Providers).openProvider(AiProvider.GEMINI).openProvider(AiProvider.GROQ)
        assertEquals(AiProvider.GROQ, n.editing)
        assertEquals(Screen.Providers, n.back().current)
    }

    @Test fun reopeningTheCurrentScreenDoesNothingAndOpeningOneInTheStackReturnsToIt() {
        val n = NavState().open(Screen.Settings)
        assertEquals(n, n.open(Screen.Settings))
        val deep = n.open(Screen.Memory)
        assertEquals(n, deep.open(Screen.Settings))
    }

    @Test fun homeForgetsEverything() {
        assertEquals(NavState(), NavState().open(Screen.Settings).open(Screen.Providers).openProvider(AiProvider.GROQ).home())
    }

    @Test fun providerScreenCannotBeOpenedWithoutAProvider() {
        assertThrows(IllegalArgumentException::class.java) { NavState().open(Screen.Provider) }
    }

    @Test fun savedStateRoundTrips() {
        for (n in listOf(
            NavState(),
            NavState().open(Screen.Settings),
            NavState().open(Screen.Settings).open(Screen.Providers).openProvider(AiProvider.CLOUDFLARE),
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
}
