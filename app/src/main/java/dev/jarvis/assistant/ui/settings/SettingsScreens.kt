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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LinearProgressIndicator
import dev.jarvis.assistant.ai.ConnectionTestViewModel
import dev.jarvis.assistant.ai.PromptBuilder
import dev.jarvis.assistant.ai.TestState
import dev.jarvis.assistant.settings.AiProvider
import dev.jarvis.assistant.settings.Humour
import dev.jarvis.assistant.settings.JarvisSettings
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
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keyPresent.collectAsStateWithLifecycle()
    val s = settings

    ScreenColumn(modifier) {
        NavCard(
            title = "Personal Intelligence",
            subtitle = if (s == null) "" else "${s.personality.label}, humour: ${Humour.of(s.humour).label}",
            onClick = onOpenPersonalIntelligence,
        )
        NavCard(
            title = "AI provider and key",
            subtitle = if (s == null) "" else {
                val saved = if (keys[s.provider] == true) "key saved" else "no key yet"
                "${s.provider.label}, $saved"
            },
            onClick = onOpenProvider,
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
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
    testVm: ConnectionTestViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keyPresent.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val current = settings
    if (current == null) {
        Loading(modifier)
    } else {
        ProviderContent(current, keys, notice, vm, testVm, modifier)
    }
}

@Composable
private fun ProviderContent(
    s: JarvisSettings,
    keys: Map<AiProvider, Boolean>,
    notice: String?,
    vm: SettingsViewModel,
    testVm: ConnectionTestViewModel,
    modifier: Modifier,
) {
    val provider = s.provider
    val acknowledged = provider in s.acknowledged
    val hasKey = keys[provider] == true

    ScreenColumn(modifier) {
        SectionTitle("Which AI answers you")
        AiProvider.entries.forEach { p ->
            RadioRow(
                selected = provider == p,
                title = p.label,
                subtitle = if (keys[p] == true) "Key saved" else "No key yet",
                onClick = { vm.update { it.copy(provider = p) } },
            )
        }
        Text(
            "JARVIS never switches provider by itself and never uses a paid service. " +
                "If the free quota runs out you'll see a message, and you choose what happens next.",
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
        var model by remember(provider) { mutableStateOf(s.modelFor(provider)) }
        OutlinedTextField(
            value = model,
            onValueChange = { v ->
                model = v
                vm.update { it.withModel(provider, v) }
            },
            label = { Text("Model name") },
            supportingText = { Text("Default: ${provider.defaultModel}. Model names change; edit this if one stops working.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("API key")
        Text(provider.keyHelp, style = MaterialTheme.typography.bodySmall)
        if (hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Key saved, encrypted on this phone.",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { vm.removeKey(provider) }) { Text("Remove") }
            }
        }
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
        val cleaned = keyInput.filterNot { it.isWhitespace() }
        Button(
            enabled = acknowledged && cleaned.length >= MIN_KEY_LENGTH,
            onClick = {
                vm.saveKey(provider, cleaned)
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
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = if (notice.startsWith("Couldn't")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        ProviderTools(provider, hasKey && acknowledged, testVm)

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

private const val MIN_KEY_LENGTH = 20

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
