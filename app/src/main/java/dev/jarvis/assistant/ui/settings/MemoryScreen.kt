package dev.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jarvis.assistant.memory.Memory
import dev.jarvis.assistant.memory.MemoryCategory
import dev.jarvis.assistant.memory.MemorySource
import dev.jarvis.assistant.memory.MemoryStatus
import dev.jarvis.assistant.memory.MemoryViewModel
import dev.jarvis.assistant.settings.SettingsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

@Composable
fun MemoryScreen(
    modifier: Modifier = Modifier,
    vm: MemoryViewModel = viewModel(),
    settingsVm: SettingsViewModel = viewModel(),
) {
    val all by vm.memories.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val pending = all.filter { it.status == MemoryStatus.PENDING }
    val active = all.filter { it.status == MemoryStatus.ACTIVE }

    ScreenColumn(modifier) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use memory", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "JARVIS notes useful, lasting facts you tell it and adds the few relevant ones to each request. " +
                                "Everything stays on this phone. Only the relevant memories are sent to your AI provider, " +
                                "as part of the request, like the rest of your message. Passwords, keys, card and account " +
                                "numbers are never saved. Health, identity, money and facts about other people are only " +
                                "saved if you approve.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings?.memoryEnabled ?: true,
                        onCheckedChange = { v -> settingsVm.update { it.copy(memoryEnabled = v) } },
                    )
                }
                Text(
                    "Turning it off stops saving and using memories. Existing ones are kept until you delete them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::clearMessage) { Text("OK") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }) { Text("Add memory") }
            OutlinedButton(enabled = all.isNotEmpty(), onClick = { confirmClear = true }) { Text("Clear all") }
        }

        if (pending.isNotEmpty()) {
            SectionTitle("Needs your approval")
            pending.forEach { m -> PendingCard(m, onYes = { vm.approve(m.id) }, onNo = { vm.dismiss(m.id) }) }
        }

        if (active.isEmpty()) {
            Text(
                "Nothing remembered yet. Tell JARVIS things like \"my favourite colour is orange\" or \"remember that I use " +
                    "an iQOO 13\", or add a memory above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (category in MemoryCategory.entries) {
            val items = active.filter { it.category == category }
            if (items.isEmpty()) continue
            SectionTitle("${category.label} (${items.size})")
            items.forEach { m -> MemoryCard(m, onEdit = { editing = m }, onDelete = { vm.delete(m.id) }) }
        }
    }

    if (adding) {
        MemoryEditDialog(
            title = "Add memory", initialText = "", initialCategory = MemoryCategory.OTHER, showCategory = true,
            onDismiss = { adding = false },
            onSave = { cat, text -> vm.add(cat, text); adding = false },
        )
    }
    editing?.let { m ->
        MemoryEditDialog(
            title = "Edit memory", initialText = m.content, initialCategory = m.category, showCategory = false,
            onDismiss = { editing = null },
            onSave = { _, text -> vm.edit(m.id, text); editing = null },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all memories?") },
            text = { Text("This permanently deletes everything JARVIS remembers, including proposals waiting for approval. It cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("Delete everything") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemoryCard(m: Memory, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.content, style = MaterialTheme.typography.bodyLarge)
            val saved = DATE.format(Instant.ofEpochMilli(m.createdAt))
            val updated = DATE.format(Instant.ofEpochMilli(m.updatedAt))
            val how = if (m.source == MemorySource.MANUAL) "added by you" else "noted from chat"
            Text(
                "Saved $saved" + (if (updated != saved) " · updated $updated" else "") + " · $how",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun PendingCard(m: Memory, onYes: () -> Unit, onNo: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.content, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Waiting for your OK: ${m.reason ?: "it may be sensitive"}.",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onYes) { Text(if (m.replacesId != null) "Update" else "Save") }
                OutlinedButton(onClick = onNo) { Text("Don't save") }
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    title: String,
    initialText: String,
    initialCategory: MemoryCategory,
    showCategory: Boolean,
    onDismiss: () -> Unit,
    onSave: (MemoryCategory, String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var category by remember { mutableStateOf(initialCategory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(300) },
                    label = { Text("What should JARVIS remember?") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showCategory) {
                    Text("Category", style = MaterialTheme.typography.labelMedium)
                    MemoryCategory.entries.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { c ->
                                FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(category, text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
