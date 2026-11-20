package dev.jarvis.assistant.providers

import dev.jarvis.assistant.ai.AvailabilityState
import dev.jarvis.assistant.ai.DiagState
import dev.jarvis.assistant.ai.ProviderTestState
import dev.jarvis.assistant.ai.TestState
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
    val id: ProviderId,
    /** The name to show, supplied by the caller from the provider's descriptor. The model does not look anything up. */
    val label: String,
    val config: ConfigStatus,
    val test: TestStatus,
    val preferred: Boolean,
    val active: Boolean,
    val viaFailover: Boolean,
    /** The user's own choice, set aside while an automatic switch is in use. */
    val preferredPaused: Boolean,
    /** Selected for chat but not configured: chat messages are refused (nothing is sent, nothing falls back). */
    val chatBlocked: Boolean,
) {
    val configured: Boolean get() = config == ConfigStatus.CONFIGURED

    /** "configured, not tested" style: configuration first, then what the connection checks showed. */
    fun shortState(): String = "${config.short()}, ${test.short()}"

    /** About the API key only (consent is part of [config], not of the key). */
    fun keyLabel(): String = when (config) {
        ConfigStatus.NO_KEY -> "No API key saved"
        ConfigStatus.KEY_UNREADABLE -> "A key is stored but can't be read right now, so it isn't being used"
        ConfigStatus.NEEDS_CONSENT, ConfigStatus.CONFIGURED -> "API key saved (encrypted on this phone)"
    }

    /** Short labels for the chat-provider roles, for the Choose Chat Provider tab. Says nothing about testing. */
    fun chatRoleBadges(): List<String> = buildList {
        if (preferred) add(if (preferredPaused) "Preferred (paused by automatic failover)" else "Preferred")
        if (viaFailover) add("Active via automatic failover") else if (active) add("Active")
        if (chatBlocked) add("Selected for chat, but can't answer yet")
    }

    companion object {
        /**
         * [savedPreferred] is the saved chat provider. During an automatic switch [failoverFrom] is the provider the user
         * had chosen before it (and [savedPreferred] is the one the switch put in use).
         */
        fun of(
            id: ProviderId,
            label: String,
            keyState: KeyState,
            acknowledged: Boolean,
            tests: ProviderTestState,
            savedPreferred: ProviderId,
            failoverFrom: ProviderId? = null,
        ): ProviderStatusModel {
            val config = when {
                keyState == KeyState.MISSING -> ConfigStatus.NO_KEY
                keyState == KeyState.UNREADABLE_NOW -> ConfigStatus.KEY_UNREADABLE
                !acknowledged -> ConfigStatus.NEEDS_CONSENT
                else -> ConfigStatus.CONFIGURED
            }
            val episode = failoverFrom != null && failoverFrom != savedPreferred
            val selectedForChat = id == savedPreferred
            val configured = config == ConfigStatus.CONFIGURED
            return ProviderStatusModel(
                id = id,
                label = label,
                config = config,
                test = testStatus(tests),
                preferred = id == (if (episode) failoverFrom else savedPreferred),
                active = selectedForChat && configured,
                viaFailover = episode && selectedForChat && configured,
                preferredPaused = episode && id == failoverFrom,
                chatBlocked = selectedForChat && !configured,
            )
        }

        /** The model of every provider, built the one way every screen uses (so no screen has its own status logic). */
        fun all(
            providers: List<ProviderDescriptor>,
            keyStates: Map<ProviderId, KeyState>,
            acknowledged: Set<ProviderId>,
            tests: Map<ProviderId, ProviderTestState>,
            savedPreferred: ProviderId,
            failoverFrom: ProviderId?,
        ): Map<ProviderId, ProviderStatusModel> = providers.associate { d ->
            d.id to of(
                d.id, d.label, keyStates[d.id] ?: KeyState.MISSING, d.id in acknowledged,
                tests[d.id] ?: ProviderTestState(), savedPreferred, failoverFrom,
            )
        }

        /**
         * The one-line-or-two status for the Settings hub card. It names the preferred provider (the user's choice) and,
         * during an automatic switch, separately the provider answering now. Configuration and testing are stated
         * separately and a saved key alone is never called "ready" or "tested".
         */
        fun hubSummary(all: Collection<ProviderStatusModel>, failoverEnabled: Boolean): String {
            val failover = "Failover ${if (failoverEnabled) "on" else "off"}."
            val preferred = all.firstOrNull { it.preferred } ?: return failover
            val answering = all.firstOrNull { it.active || it.chatBlocked }
            if (preferred.preferredPaused && answering != null && answering.viaFailover) {
                return "Preferred: ${preferred.label} (paused). Answering now: ${answering.label} " +
                    "(automatic failover, ${answering.shortState()}). $failover"
            }
            if (preferred.chatBlocked) {
                return "Preferred: ${preferred.label}, ${preferred.config.short()}. Chat can't answer until this is fixed. $failover"
            }
            return "Preferred: ${preferred.label}, ${preferred.shortState()}. $failover"
        }

        /**
         * One line per check that has something to show, in plain words, taken straight from the stored results (nothing
         * is run to produce them). Empty when nothing has been tested. The diagnostics verdict text is not repeated here.
         */
        fun testDetails(t: ProviderTestState): List<String> = buildList {
            when (val a = t.availability) {
                AvailabilityState.Idle -> {}
                AvailabilityState.Running -> add("Availability check: running")
                is AvailabilityState.Ok -> add("Availability check: the key is accepted and model \"${a.model}\" exists")
                is AvailabilityState.Failed -> add("Availability check failed: ${a.message}")
            }
            when (val g = t.generation) {
                TestState.Idle -> {}
                is TestState.Running -> add("Generation test: running")
                is TestState.Done -> add("Generation test: a reply came back")
                is TestState.Failed -> add("Generation test failed: ${g.message}")
            }
            when (val d = t.diagnostics) {
                DiagState.Idle -> {}
                is DiagState.Running -> add("Diagnostics: running")
                is DiagState.Done -> add("Diagnostics were run (see the verdict on the provider's screen)")
                is DiagState.Failed -> add("Diagnostics could not finish: ${d.message}")
            }
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

private fun ConfigStatus.short(): String = when (this) {
    ConfigStatus.NO_KEY -> "no API key"
    ConfigStatus.KEY_UNREADABLE -> "key can't be read"
    ConfigStatus.NEEDS_CONSENT -> "needs your consent"
    ConfigStatus.CONFIGURED -> "configured"
}

private fun TestStatus.short(): String = when (this) {
    TestStatus.NOT_TESTED -> "not tested"
    TestStatus.RUNNING -> "testing"
    TestStatus.FAILED -> "last test failed"
    TestStatus.ACCEPTED -> "key accepted, generation not tested"
    TestStatus.PASSED -> "test passed"
}
