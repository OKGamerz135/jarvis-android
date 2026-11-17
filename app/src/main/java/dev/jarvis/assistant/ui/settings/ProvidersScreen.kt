package dev.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.jarvis.assistant.ai.ErrorMessages
import dev.jarvis.assistant.ai.LlmException
import dev.jarvis.assistant.ai.OpenRouterParsing
import dev.jarvis.assistant.ai.Providers
import dev.jarvis.assistant.settings.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.assistant.ui.common.ActionRow
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jarvis.assistant.chat.ChatViewModel
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.diag.UsageGuard
import dev.jarvis.assistant.providers.ProviderStatus
import dev.jarvis.assistant.providers.QuotaLedgers
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.SettingsViewModel
import java.time.Instant
import java.time.ZoneId

/** Overview of all providers: failover switch, order, on/off, and honest quota status. Details open per provider. */
@Composable
fun ProvidersScreen(
    onConfigure: (AiProvider) -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
    chatVm: ChatViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keyPresent.collectAsStateWithLifecycle()
    val ledger = QuotaLedgers.get(LocalContext.current)
    val records by ledger.records.collectAsStateWithLifecycle()
    val log by DiagnosticsLog.entries.collectAsStateWithLifecycle()
    val chat by chatVm.state.collectAsStateWithLifecycle()
    // Re-read the keys whenever this screen comes back, so "key saved" is never older than the key itself.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshKeys() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quotaText by remember { mutableStateOf<String?>(null) }
    var quotaBusy by remember { mutableStateOf(false) }
    val s = settings
    if (s == null) {
        Loading(modifier)
        return
    }
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val order = s.orderedProviders()

    ScreenColumn(modifier) {
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

        SectionTitle("Providers, in failover order")
        val since = System.currentTimeMillis() - UsageGuard.DAY_MS
        order.forEachIndexed { index, p ->
            val used = UsageGuard.generationCalls(log, p.label, since)
            val record = records[p]
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val tags = buildList {
                                if (p == s.provider) add(if (chat.temporaryProvider == p) "preferred, set by automatic failover" else "preferred")
                            }
                            Text(
                                p.label + if (tags.isEmpty()) "" else " (${tags.joinToString(", ")})",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                ProviderStatus.describe(p, p in s.acknowledged, keys[p] == true, record, now, zone),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (p == s.provider) {
                                ProviderStatus.chatWarning(p, p in s.acknowledged, keys[p] == true)?.let { warning ->
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
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Use for failover", style = MaterialTheme.typography.labelSmall)
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
                        OutlinedButton(onClick = { onConfigure(p) }) { Text("Key and model") }
                    }
                    if (p == AiProvider.OPENROUTER && keys[p] == true) {
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
                        quotaText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
        Button(enabled = !chat.simulationArmed, onClick = { chatVm.armSimulation() }) {
            Text(if (chat.simulationArmed) "Armed: send a chat message now" else "Simulate quota exhaustion on my next message")
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
