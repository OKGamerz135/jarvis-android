package dev.jarvis.assistant.providers

import dev.jarvis.assistant.ai.AvailabilityState
import dev.jarvis.assistant.ai.DiagState
import dev.jarvis.assistant.ai.ProviderTestState
import dev.jarvis.assistant.ai.TestState
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.KeyState

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/** Is the provider set up? Says nothing about whether it works. */
enum class ConfigStatus(val label: String) {
    NO_KEY("No API key"),
    KEY_UNREADABLE("Key can't be read right now"),
    NEEDS_CONSENT("Needs your consent"),
    CONFIGURED("Configured"),
}

/** What the most recent connection checks in this app session showed. Never persisted: after a restart it is NOT_TESTED. */
enum class TestStatus(val label: String) {
    NOT_TESTED("Not tested"),
    RUNNING("Testing..."),
    FAILED("Last test failed"),

    /** The key was accepted and the model exists, but nothing was generated. This is NOT a passed generation test. */
    ACCEPTED("Key and model accepted (generation not tested)"),

    /** A real reply came back from the provider. */
    PASSED("Test passed"),
}

/**
 * The four independent facts about one provider. They never imply each other:
 *  - [config]: the key and consent are in place (it can be tried);
 *  - [test]: what the connection checks showed (a configured provider is NOT "tested");
 *  - [preferred]: the user's own choice for normal chat (during an automatic switch this is still the original choice);
 *  - [active]: the provider that will handle the next chat message right now (the saved preference, if it is configured);
 *  - [viaFailover]: it is active only because an automatic switch put it there.
 */
data class ProviderStatusModel(
    val provider: AiProvider,
    val config: ConfigStatus,
    val test: TestStatus,
    val preferred: Boolean,
    val active: Boolean,
    val viaFailover: Boolean,
    /** Selected for chat but not configured: chat messages are refused (nothing is sent, nothing falls back). */
    val chatBlocked: Boolean,
) {
    val configured: Boolean get() = config == ConfigStatus.CONFIGURED

    companion object {
        /**
         * [savedPreferred] is the saved chat provider. During an automatic switch [failoverFrom] is the provider the user
         * had chosen before it (and [savedPreferred] is the one the switch put in use).
         */
        fun of(
            provider: AiProvider,
            keyState: KeyState,
            acknowledged: Boolean,
            tests: ProviderTestState,
            savedPreferred: AiProvider,
            failoverFrom: AiProvider? = null,
        ): ProviderStatusModel {
            val config = when {
                keyState == KeyState.MISSING -> ConfigStatus.NO_KEY
                keyState == KeyState.UNREADABLE_NOW -> ConfigStatus.KEY_UNREADABLE
                !acknowledged -> ConfigStatus.NEEDS_CONSENT
                else -> ConfigStatus.CONFIGURED
            }
            val episode = failoverFrom != null && failoverFrom != savedPreferred
            val selectedForChat = provider == savedPreferred
            val configured = config == ConfigStatus.CONFIGURED
            return ProviderStatusModel(
                provider = provider,
                config = config,
                test = testStatus(tests),
                preferred = provider == (if (episode) failoverFrom else savedPreferred),
                active = selectedForChat && configured,
                viaFailover = episode && selectedForChat && configured,
                chatBlocked = selectedForChat && !configured,
            )
        }

        /**
         * Deliberately conservative: any failure wins over a success (so a mixed picture is never shown as passed), only a
         * real generation reply counts as PASSED, and availability alone is only ACCEPTED. The diagnostics verdict is free
         * text, so it can add a failure but never a pass.
         */
        fun testStatus(t: ProviderTestState): TestStatus = when {
            t.availability is AvailabilityState.Running || t.generation is TestState.Running || t.diagnostics is DiagState.Running ->
                TestStatus.RUNNING
            t.availability is AvailabilityState.Failed || t.generation is TestState.Failed || t.diagnostics is DiagState.Failed ->
                TestStatus.FAILED
            t.generation is TestState.Done -> TestStatus.PASSED
            t.availability is AvailabilityState.Ok -> TestStatus.ACCEPTED
            else -> TestStatus.NOT_TESTED
        }
    }
}
