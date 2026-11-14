package dev.jarvis.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class SensitivityGuardTest {
    private fun level(s: String) = SensitivityGuard.check(s).level

    @Test fun ordinaryPreferencesAreOk() {
        assertEquals(Sensitivity.OK, level("My favourite colour is orange"))
        assertEquals(Sensitivity.OK, level("I use an iQOO 13"))
        assertEquals(Sensitivity.OK, level("Building a voice assistant called JARVIS"))
    }

    @Test fun secretsAreBlocked() {
        assertEquals(Sensitivity.BLOCKED, level("my password is hunter22"))
        assertEquals(Sensitivity.BLOCKED, level("api key: sk-abcdefghijklmnopqrstuvwxyz123456"))
        assertEquals(Sensitivity.BLOCKED, level("token = AIzaSyA1234567890abcdefghijklmnopqrstuv"))
        assertEquals(Sensitivity.BLOCKED, level("my pin is 4821"))
    }

    @Test fun cardNumbersAreBlockedButRandomDigitsAreNot() {
        assertEquals(Sensitivity.BLOCKED, level("my card is 4111 1111 1111 1111"))
        assertEquals(Sensitivity.OK, level("I want to ship build 25 by 2026"))
    }

    @Test fun governmentIdsAreBlocked() {
        assertEquals(Sensitivity.BLOCKED, level("my PAN is ABCDE1234F"))
        assertEquals(Sensitivity.BLOCKED, level("my aadhaar is 2345 6789 0123"))
    }

    @Test fun usefulButSensitiveNeedsApproval() {
        assertEquals(Sensitivity.NEEDS_APPROVAL, level("I have asthma"))
        assertEquals(Sensitivity.NEEDS_APPROVAL, level("my friend Rahul is getting divorced"))
        assertEquals(Sensitivity.NEEDS_APPROVAL, level("I live at 12 MG Road, Pune"))
    }
}
