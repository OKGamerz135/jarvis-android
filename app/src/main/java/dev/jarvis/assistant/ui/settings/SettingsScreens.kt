package dev.jarvis.assistant.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LinearProgressIndicator
import dev.jarvis.assistant.ai.ConnectionTestViewModel
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.ai.TestState
import dev.jarvis.assistant.chat.ChatViewModel
import dev.jarvis.assistant.providers.ProviderDescriptor
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderStatusModel
import dev.jarvis.assistant.providers.toId
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.Humour
import dev.jarvis.assistant.settings.Profanity
import dev.jarvis.assistant.settings.JarvisSettings
import dev.jarvis.assistant.settings.KeyInputCheck
import dev.jarvis.assistant.settings.KeyNotice
import dev.jarvis.assistant.settings.ModelInputNote
import dev.jarvis.assistant.settings.KeyState
import dev.jarvis.assistant.settings.Personality
import dev.jarvis.assistant.settings.ResponseLength
import dev.jarvis.assistant.settings.SettingsViewModel
import dev.jarvis.assistant.settings.Tone
import dev.jarvis.assistant.update.UpdateSection
import kotlin.math.roundToInt

// ---------------------------------------------------------------- hub

@Composable
fun SettingsHubScreen(
    onOpenPersonalIntelligence: () -> Unit,
    onOpenProvider: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenMemory: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
    testVm: ConnectionTestViewModel = viewModel(),
    chatVm: ChatViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keyStates by vm.keyState.collectAsStateWithLifecycle()
    val testStates by testVm.states.collectAsStateWithLifecycle()
    val chat by chatVm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshKeys() }
    val s = settings

    ScreenColumn(modifier) {
        NavCard(
            title = "Personal Intelligence",
            subtitle = if (s == null) "" else "${s.personality.label}, humour: ${Humour.of(s.humour).label}, profanity: ${Profanity.of(s.profanity).label}",
            onClick = onOpenPersonalIntelligence,
        )
        NavCard(
            title = "AI providers",
            // The same status model the provider tabs use: configuration and test result are stated separately.
            subtitle = if (s == null) "" else ProviderStatusModel.hubSummary(
                ProviderStatusModel.all(
                    vm.registry.all(), keyStates, s.acknowledged.map { it.toId() }.toSet(), testStates, s.provider.toId(), chat.preferredProvider,
                ).values,
                s.failoverEnabled,
            ),
            onClick = onOpenProvider,
        )
        NavCard(
            title = "Memory",
            subtitle = if (s == null) "" else "What JARVIS remembers about you. Use memory: ${if (s.memoryEnabled) "on" else "off"}",
            onClick = onOpenMemory,
        )
        NavCard(
            title = "Diagnostics log",
            subtitle = "Metadata of recent AI calls. No keys, prompts or replies.",
            onClick = onOpenDiagnostics,
        )
        UpdateSection()
    }
}

// ------------------------------------------------- personal intelligence

@Composable
fun PersonalIntelligenceScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings
    if (current == null) {
        Loading(modifier)
    } else {
        PersonalIntelligenceContent(current, vm, modifier)
    }
}

@Composable
private fun PersonalIntelligenceContent(s: JarvisSettings, vm: SettingsViewModel, modifier: Modifier) {
    // Text boxes keep their own state so typing never fights with the saved copy.
    var address by remember { mutableStateOf(s.addressAs) }
    var language by remember { mutableStateOf(s.language) }
    var custom by remember { mutableStateOf(s.customInstructions) }
    var showPrompt by remember { mutableStateOf(false) }

    ScreenColumn(modifier) {
        SectionTitle("Personality")
        Personality.entries.forEach { p ->
            RadioRow(
                selected = s.personality == p,
                title = p.label,
                subtitle = p.blurb,
                onClick = { vm.update { it.copy(personality = p) } },
            )
        }

        SectionTitle("Tone")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tone.entries.forEach { t ->
                FilterChip(
                    selected = s.tone == t,
                    onClick = { vm.update { it.copy(tone = t) } },
                    label = { Text(t.label) },
                )
            }
        }

        SectionTitle("Humour and sarcasm: ${Humour.of(s.humour).label}")
        Slider(
            value = s.humour.toFloat(),
            onValueChange = { v -> vm.update { it.copy(humour = v.roundToInt()) } },
            valueRange = 0f..Humour.MAX.toFloat(),
            steps = Humour.MAX - 1,
        )

        SectionTitle("Profanity and roast intensity: ${Profanity.of(s.profanity).label}")
        Slider(
            value = s.profanity.toFloat(),
            onValueChange = { v -> vm.update { it.copy(profanity = v.roundToInt()) } },
            valueRange = 0f..Profanity.MAX.toFloat(),
            steps = Profanity.MAX - 1,
        )
        Text(
            Profanity.of(s.profanity).blurb + " Off by default. Swearing adapts to your mood: supportive when you're upset, " +
                "never slurs, threats or hate. Applies to every reply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Response length")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResponseLength.entries.forEach { l ->
                FilterChip(
                    selected = s.length == l,
                    onClick = { vm.update { it.copy(length = l) } },
                    label = { Text(l.label) },
                )
            }
        }

        SectionTitle("Language and name")
        OutlinedTextField(
            value = language,
            onValueChange = { v ->
                val t = v.take(PromptBuilder.MAX_SHORT_FIELD)
                language = t
                vm.update { it.copy(language = t) }
            },
            label = { Text("Preferred language") },
            supportingText = { Text("Leave empty to match whatever language you write in.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { v ->
                val t = v.take(PromptBuilder.MAX_SHORT_FIELD)
                address = t
                vm.update { it.copy(addressAs = t) }
            },
            label = { Text("What JARVIS calls you") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Your own instructions")
        OutlinedTextField(
            value = custom,
            onValueChange = { v ->
                val t = v.take(PromptBuilder.MAX_CUSTOM)
                custom = t
                vm.update { it.copy(customInstructions = t) }
            },
            label = { Text("Custom behaviour") },
            supportingText = { Text("${custom.length}/${PromptBuilder.MAX_CUSTOM}") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(onClick = { showPrompt = !showPrompt }) {
            Text(if (showPrompt) "Hide what JARVIS is told" else "Show exactly what JARVIS is told")
        }
        if (showPrompt) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    PromptBuilder.build(s),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------ provider

@Composable
fun ProviderScreen(
    editing: AiProvider,
    onConfigureOther: (AiProvider) -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
    testVm: ConnectionTestViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keyState.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    // Re-read the keys whenever this screen comes back, so "key saved" is never older than the key itself.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshKeys() }
    val current = settings
    if (current == null) {
        Loading(modifier)
    } else {
        // `editing` is the provider being SET UP. It has nothing to do with the provider used for chat.
        ProviderContent(current, keys, notice, vm, testVm, editing, onConfigureOther, modifier)
    }
}

@Composable
private fun ProviderContent(
    s: JarvisSettings,
    keys: Map<ProviderId, KeyState>,
    notice: KeyNotice?,
    vm: SettingsViewModel,
    testVm: ConnectionTestViewModel,
    provider: AiProvider,
    onConfigureOther: (AiProvider) -> Unit,
    modifier: Modifier,
) {
    val acknowledged = provider in s.acknowledged
    val keyState = keys[provider.toId()] ?: KeyState.MISSING
    val descriptor = remember(provider) { ProviderDescriptor.of(provider) }
    val hasKey = keyState == KeyState.USABLE

    ScreenColumn(modifier) {
        Text(
            "You are setting up ${provider.label}. This screen never changes which AI answers your chats " +
                "(now: ${s.provider.label}). Choose that under Settings > AI providers > Make preferred. Checks and " +
                "tests below always use ${provider.label}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud: ${provider.label}", style = MaterialTheme.typography.titleMedium)
                Text(provider.notice, style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = acknowledged,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                vm.update {
                                    it.copy(
                                        acknowledged = if (checked) it.acknowledged + provider
                                        else it.acknowledged - provider,
                                    )
                                }
                            },
                        ),
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text("I understand", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionTitle("Model")
        // Saved across rotation and process recreation. It is the same text that is written to the settings on every edit,
        // so the field and the saved model agree. (The API key field below is deliberately NOT saved this way.)
        var model by rememberSaveable(provider) { mutableStateOf(s.modelFor(provider)) }
        OutlinedTextField(
            value = model,
            onValueChange = { v ->
                model = v
                vm.update { it.withModel(provider, v) }
            },
            label = { Text("Model name") },
            supportingText = {
                // Says exactly which model is used, and whether that is the default because the field is blank.
                Text(ModelInputNote.of(model, descriptor).message + " Model names change; edit this if one stops working.")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("API key")
        Text(provider.keyHelp, style = MaterialTheme.typography.bodySmall)
        if (keyState == KeyState.UNREADABLE_NOW) {
            Text(
                "A key is stored but can't be read right now, so it is not being used. Try again in a moment, or paste " +
                    "a new key to replace it.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (hasKey || keyState == KeyState.UNREADABLE_NOW) {
            // Text on its own full-width line, the button below it: neither can squeeze the other at a large font.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (hasKey) "Key saved, encrypted on this phone." else "A key is stored.",
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = { vm.removeKey(provider.toId()) }) { Text("Remove") }
            }
        }
        // Plain `remember` on purpose: an API key must never go into saved instance state (it would end up in the system's
        // saved-state storage). After rotation the field is empty and the key has to be pasted again.
        var keyInput by remember(provider) { mutableStateOf("") }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (hasKey) "Paste a new key to replace it" else "Paste your API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        val keyCheck = KeyInputCheck.check(keyInput, descriptor)
        KeyInputCheck.message(keyCheck, descriptor)?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (keyCheck is KeyInputCheck.Valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Button(
            enabled = acknowledged && keyCheck is KeyInputCheck.Valid,
            onClick = {
                (keyCheck as? KeyInputCheck.Valid)?.let { vm.saveKey(provider.toId(), it.key) }
                keyInput = ""
            },
        ) { Text("Save key") }
        if (!acknowledged) {
            Text(
                "Tick \"I understand\" above to enable saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The result of the last save/remove, only on the screen of the provider it was for.
        if (notice != null && notice.provider == provider.toId()) {
            Text(
                notice.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (notice.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        // "Try Groq" only opens Groq's own setup. It does not change which provider answers chats.
        ProviderTools(provider, hasKey && acknowledged, testVm) { onConfigureOther(AiProvider.GROQ) }

        Text(
            "About your key: it is stored encrypted with Android's Keystore and is never written into the app " +
                "or the code. It is sent only to ${provider.label}. Anyone who gets this APK can't read it, " +
                "but a rooted phone or a leaked key (for example in a screenshot) can expose it. " +
                "If that happens, delete the key in the provider's console and make a new one. " +
                "On a free tier the worst case is a used-up quota, not a bill.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


// ------------------------------------------------------------- helpers

@Composable
internal fun ScreenColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
internal fun Loading(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
