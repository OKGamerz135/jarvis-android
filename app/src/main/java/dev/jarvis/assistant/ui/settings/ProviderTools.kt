package dev.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.assistant.ai.AvailabilityState
import dev.jarvis.assistant.ai.ConnectionTestViewModel
import dev.jarvis.assistant.ai.DiagState
import dev.jarvis.assistant.ai.TestState
import dev.jarvis.assistant.settings.AiProvider

@Composable
internal fun ProviderTools(
    provider: AiProvider,
    ready: Boolean,
    vm: ConnectionTestViewModel,
    onTryGroq: () -> Unit,
) {
    if (!ready) {
        Text(
            "Checks need a saved key and the \"I understand\" box ticked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AvailabilityCard(provider, ready, vm)
    GenerationCard(provider, ready, vm, onTryGroq)
    DiagnosticsCard(ready, vm)
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun FailureBlock(message: String, details: String?) {
    Text(message, color = MaterialTheme.colorScheme.error)
    if (details != null) {
        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AvailabilityCard(provider: AiProvider, ready: Boolean, vm: ConnectionTestViewModel) {
    val state by vm.availability.collectAsStateWithLifecycle()
    SectionTitle("1. Availability check")
    Hint(
        "Asks ${provider.label} whether your key can see this model. Generates nothing and uses no generation quota. " +
            "It does not prove that generating works.",
    )
    Button(enabled = ready && state !is AvailabilityState.Running, onClick = { vm.checkAvailability() }) {
        Text("Check availability")
    }
    when (val s = state) {
        AvailabilityState.Idle -> {}
        AvailabilityState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is AvailabilityState.Ok -> Panel {
            Text(
                "Reachable. Key accepted and model \"${s.model}\" exists" +
                    (s.displayName?.let { " ($it)" } ?: "") + ".",
                color = MaterialTheme.colorScheme.primary,
            )
            Hint(
                if (s.canGenerate) "It lists text generation as supported."
                else "Warning: it does not list text generation among its methods.",
            )
        }
        is AvailabilityState.Failed -> Panel { FailureBlock(s.message, s.details) }
    }
}

@Composable
private fun GenerationCard(provider: AiProvider, ready: Boolean, vm: ConnectionTestViewModel, onTryGroq: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val usage by vm.generationCalls24h.collectAsStateWithLifecycle()
    val used = usage[provider.label] ?: 0
    var prompt by remember { mutableStateOf("Introduce yourself in two sentences.") }

    SectionTitle("2. Generation test")
    Hint(
        "Sends one real message through ${provider.label} using your Personal Intelligence settings. " +
            "This uses free quota and is sent to ${provider.label}. If the provider says it is overloaded, " +
            "I retry up to 2 times (about 2 s then 4 s) and stop. Other errors are never retried. " +
            "There is a 60 second pause between generation calls.",
    )
    OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it.take(500) },
        label = { Text("Test message") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = ready && prompt.isNotBlank() && state !is TestState.Running,
        onClick = { vm.runTest(prompt.trim()) },
    ) { Text("Send test message") }
    Hint("${provider.label} generation requests from this phone in the last 24 hours, failed ones included: $used")

    when (val t = state) {
        TestState.Idle -> {}
        is TestState.Running -> Panel {
            if (t.text.isEmpty()) Text(t.note ?: "Waiting for ${provider.label}...") else Text(t.text)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is TestState.Done -> Panel {
            Text(t.text)
            Text(
                "Answered by ${t.provider} (${t.model}) in ${"%.1f".format(t.millis / 1000.0)} s" +
                    if (t.attempts > 1) ", after ${t.attempts} attempts" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is TestState.Failed -> Panel {
            FailureBlock(t.message, t.details)
            if (t.offerAlternative) {
                Text(
                    "Gemini is still overloaded after 2 retries. You can switch to Groq yourself. Nothing is sent " +
                        "to Groq until you tap Send test message there, and Groq needs its own key and \"I understand\" box.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onTryGroq) { Text("Try Groq instead") }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(ready: Boolean, vm: ConnectionTestViewModel) {
    val state by vm.diagnostics.collectAsStateWithLifecycle()
    SectionTitle("3. Run diagnostics")
    Hint(
        "Runs the availability check, then ONE tiny generation (a one-word reply, no retries). Only if that fails " +
            "with a server-side error does it make one more generation call, not streamed, to compare. " +
            "So it uses at most 2 generation requests.",
    )
    OutlinedButton(enabled = ready && state !is DiagState.Running, onClick = { vm.runDiagnostics() }) {
        Text("Run diagnostics")
    }
    when (val d = state) {
        DiagState.Idle -> {}
        is DiagState.Running -> Panel {
            Text(d.step)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is DiagState.Failed -> Panel { Text(d.message, color = MaterialTheme.colorScheme.error) }
        is DiagState.Done -> Panel {
            for (l in d.lines) {
                val mark = when (l.ok) { true -> "PASS"; false -> "FAIL"; null -> "SKIP" }
                Text("$mark  ${l.label}", style = MaterialTheme.typography.titleSmall)
                Hint(l.detail)
            }
            Text("Verdict", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(d.verdict)
        }
    }
}
