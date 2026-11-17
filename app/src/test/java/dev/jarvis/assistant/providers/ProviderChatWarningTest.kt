package dev.jarvis.assistant.providers

import dev.jarvis.assistant.settings.AiProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D4: a provider without a key can be chosen, but the screen must say plainly that nothing will be sent. */
class ProviderChatWarningTest {
    @Test fun missingKeyWarnsThatMessagesAreNotSentAndNothingSwitches() {
        for (p in AiProvider.entries) {
            val w = ProviderStatus.chatWarning(p, acknowledged = true, hasKey = false)!!
            assertTrue(w, w.contains(p.label) && w.contains("NOT sent") && w.contains("will not switch"))
        }
    }

    @Test fun missingConsentWarnsToo() {
        val w = ProviderStatus.chatWarning(AiProvider.GROQ, acknowledged = false, hasKey = true)!!
        assertTrue(w.contains("consent") && w.contains("NOT sent"))
    }

    @Test fun readyProviderHasNoWarning() {
        assertNull(ProviderStatus.chatWarning(AiProvider.GROQ, acknowledged = true, hasKey = true))
    }

    @Test fun missingKeyIsReportedBeforeMissingConsent() {
        assertTrue(ProviderStatus.chatWarning(AiProvider.GROQ, false, false)!!.contains("API key"))
    }
}
