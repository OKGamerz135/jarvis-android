package dev.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jarvis.assistant.ai.ConnectionTestViewModel
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.OpenRouterParsing
import dev.jarvis.assistant.ai.ProviderTestState
import dev.jarvis.assistant.ai.Providers
import dev.jarvis.assistant.chat.ChatViewModel
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.diag.UsageGuard
import dev.jarvis.assistant.providers.ConfigStatus
import dev.jarvis.assistant.providers.ProviderStatus
import dev.jarvis.assistant.providers.ProviderStatusModel
import dev.jarvis.assistant.providers.TestStatus
import dev.jarvis.assistant.providers.QuotaLedgers
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.JarvisSettings
import dev.jarvis.assistant.settings.KeyState
import dev.jarvis.assistant.settings.SecureKeyStore
import dev.jarvis.assistant.settings.SettingsViewModel
import dev.jarvis.assistant.ui.ProvidersTab
import dev.jarvis.assistant.ui.common.ActionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * The AI providers screen with two tabs. Which tab is shown is only a display choice ([tab], kept in NavState); every
 * setting, key, test result and the chat state live in the view models, so switching tabs never changes or loses any of it.
 *  - Configure & Test AI: set up a provider (key, model, checks). Never changes which provider answers chats.
 *  - Choose Chat Provider: which provider answers chats, failover order and on/off, and the failover simulation.
 */
@Composable
fun ProvidersScreen(
    tab: ProvidersTab,
    onTabChange: (ProvidersTab) -> Unit,
    onConfigure: (AiProvider) -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
    chatVm: ChatViewModel = viewModel(),
    testVm: ConnectionTestViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keyStates by vm.keyState.collectAsStateWithLifecycle()
    val testStates by testVm.states.collectAsStateWithLifecycle()
    val chat by chatVm.state.collectAsStateWithLifecycle()
    // Re-read the keys whenever this screen comes back, so "key saved" is never older than the key itself.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshKeys() }
    val s = settings
    if (s == null) {
        Loading(modifier)
        return
    }
    // One status model for every card: configured, tested, preferred and active are separate facts (B1).
    val statuses = AiProvider.entries.associateWith { p ->
        ProviderStatusModel.of(
            provider = p,
            keyState = keyStates[p] ?: KeyState.MISSING,
            acknowledged = p in s.acknowledged,
            tests = testStates[p] ?: ProviderTestState(),
            savedPreferred = s.provider,
            failoverFrom = chat.preferredProvider,
        )
    }

    Column(modifier) {
        ProvidersTabRow(tab, onTabChange)
        when (tab) {
            ProvidersTab.Choose -> ChooseChatProviderTab(s, statuses, chat.preferenceError, chat.simulationArmed, vm, chatVm)
            ProvidersTab.Configure -> ConfigureProvidersTab(s, statuses, testStates, onConfigure)
        }
    }
}

/**
 * The two tabs. Written as a plain row of equal-width, wrapping labels (instead of the stock TabRow, whose tabs have a
 * fixed height) so that "Configure & Test AI" and "Choose Chat Provider" can take two or three lines on a narrow screen or
 * with a large font without being cut off. Each tab is at least 48dp tall and is announced as a tab and as selected.
 */
@Composable
private fun ProvidersTabRow(selected: ProvidersTab, onSelect: (ProvidersTab) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            ProvidersTab.entries.forEach { t ->
                val isSelected = t == selected
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = isSelected, onClick = { onSelect(t) }, role = Role.Tab),
                ) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                    HorizontalDivider(
                        thickness = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- Choose Chat Provider

@Composable
private fun ChooseChatProviderTab(
    s: JarvisSettings,
    statuses: Map<AiProvider, ProviderStatusModel>,
    preferenceError: String?,
    simulationArmed: Boolean,
    vm: SettingsViewModel,
    chatVm: ChatViewModel,
) {
    val ledger = QuotaLedgers.get(LocalContext.current)
    val records by ledger.records.collectAsStateWithLifecycle()
    val log by DiagnosticsLog.entries.collectAsStateWithLifecycle()
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val order = s.orderedProviders()

    ScreenColumn(Modifier) {
        val answering = statuses.values.firstOrNull { it.active || it.chatBlocked }
        Text(
            when {
                answering == null -> ""
                answering.chatBlocked -> "Chat is set to ${answering.provider.label}, but it can't answer yet. Messages are NOT sent anywhere until it is set up or you choose another provider."
                answering.viaFailover -> "Chat is currently answered by ${answering.provider.label} (automatic failover)."
                else -> "Chat is answered by ${answering.provider.label}."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Choosing a provider here never changes its key or model. Set those up on the Configure & Test AI tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic provider failover", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "When a provider's daily or monthly quota is genuinely used up, JARVIS announces it, counts down " +
                                "5 seconds (you can cancel) and switches to the next ready provider for a while. " +
                                "Per-minute limits, errors and outages never trigger a switch. " +
                                "Your conversation so far is sent to the new provider.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = s.failoverEnabled,
                        onCheckedChange = { on -> vm.update { it.copy(failoverEnabled = on) } },
                    )
                }
            }
        }

        preferenceError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

        SectionTitle("Providers, in failover order")
        val since = System.currentTimeMillis() - UsageGuard.DAY_MS
        order.forEachIndexed { index, p ->
            val st = statuses.getValue(p)
            val used = UsageGuard.generationCalls(log, p.label, since)
            val record = records[p]
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Full-width text first, the on/off switch on its own line below: a long label or a large font never
                    // squeezes the text into a narrow column next to the switch.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column {
                            Text(p.label, style = MaterialTheme.typography.titleMedium)
                            st.chatRoleBadges().forEach { badge ->
                                Text(badge, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            // Configured and tested are different facts; a configured provider is never shown as tested.
                            Text(
                                "${st.config.label}. ${st.test.label}.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            ProviderStatus.quotaLine(p, record, now, zone)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            if (st.chatBlocked) {
                                ProviderStatus.chatWarning(p, p in s.acknowledged, st.config == ConfigStatus.NEEDS_CONSENT || st.configured)?.let { warning ->
                                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Text(
                                "Counted on this phone: $used generation request(s) in the last 24 h. " +
                                    "The provider's own count may differ.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Use for failover", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = p !in s.disabledProviders,
                                onCheckedChange = { on ->
                                    vm.update { cur ->
                                        cur.copy(disabledProviders = if (on) cur.disabledProviders - p else cur.disabledProviders + p)
                                    }
                                },
                            )
                        }
                    }
                    ActionRow {
                        TextButton(enabled = index > 0, onClick = { vm.update { it.copy(providerOrder = move(order, index, -1)) } }) { Text("Up") }
                        TextButton(
                            enabled = index < order.lastIndex,
                            onClick = { vm.update { it.copy(providerOrder = move(order, index, +1)) } },
                        ) { Text("Down") }
                        TextButton(enabled = p != s.provider, onClick = { chatVm.choosePreferred(p) }) { Text("Make preferred") }
                    }
                    if (record != null) {
                        OutlinedButton(onClick = { ledger.clear(p); chatVm.checkRecovery() }) { Text("Mark as available") }
                        Text(
                            "Use this only if you know the quota has reset. JARVIS never guesses reset times.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionTitle("Test failover without using any quota")
        Text(
            "Makes your next chat message behave as if the current provider's quota were used up. Nothing is sent to any " +
                "provider. You'll see the announcement, the 5-second countdown, the switch, and later the offer to switch back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(enabled = !simulationArmed, onClick = { chatVm.armSimulation() }) {
            Text(if (simulationArmed) "Armed: send a chat message now" else "Simulate quota exhaustion on my next message")
        }
    }
}

// --------------------------------------------------------------------------- Configure & Test AI

/**
 * One card per provider: configuration, key and last connection-test status, read from the existing results. Opening this
 * tab never starts a check. Checks are run from each provider's own screen ("Key and model"), where the key, the model
 * and the checks for THAT provider are. Nothing here changes which provider answers chats.
 */
@Composable
private fun ConfigureProvidersTab(
    s: JarvisSettings,
    statuses: Map<AiProvider, ProviderStatusModel>,
    testStates: Map<AiProvider, ProviderTestState>,
    onConfigure: (AiProvider) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quotaText by remember { mutableStateOf<String?>(null) }
    var quotaBusy by remember { mutableStateOf(false) }

    ScreenColumn(Modifier) {
        Text(
            "Set up each provider's API key and model, and run connection checks from its \"Key and model\" screen. " +
                "This tab only shows the latest results; it never starts a check and never changes which provider answers " +
                "your chats (use the Choose Chat Provider tab for that).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Test results last until you change that provider's key or model, or the app restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AiProvider.entries.forEach { p ->
            val st = statuses.getValue(p)
            val details = ProviderStatusModel.testDetails(testStates[p] ?: ProviderTestState())
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium)
                    Text("Configuration: ${st.config.label}", style = MaterialTheme.typography.bodyMedium)
                    Text(st.keyLabel(), style = MaterialTheme.typography.bodySmall)
                    Text("Model: ${s.modelOf(p)}", style = MaterialTheme.typography.bodySmall)
                    // "Configured" is not "tested": the test status is its own line, from the stored results only.
                    Text(
                        "Connection test: ${st.test.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (st.test == TestStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    ActionRow {
                        OutlinedButton(onClick = { onConfigure(p) }) { Text("Key and model") }
                        if (p == AiProvider.OPENROUTER && st.configured) {
                            OutlinedButton(
                                enabled = !quotaBusy,
                                onClick = {
                                    quotaBusy = true
                                    quotaText = "Asking OpenRouter..."
                                    scope.launch {
                                        quotaText = try {
                                            val key = withContext(Dispatchers.IO) { SecureKeyStore(context).read(p.keyName) }.orEmpty()
                                            OpenRouterParsing.describe(Providers.openRouter().freeQuota(key))
                                        } catch (e: LlmException) {
                                            ErrorMessages.of(e.error)
                                        }
                                        quotaBusy = false
                                    }
                                },
                            ) { Text("Check free quota") }
                        }
                    }
                    if (p == AiProvider.OPENROUTER) quotaText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun move(order: List<AiProvider>, index: Int, delta: Int): List<AiProvider> {
    val list = order.toMutableList()
    val target = index + delta
    if (target !in list.indices) return list
    val item = list.removeAt(index)
    list.add(target, item)
    return list
}
