package dev.jarvis.assistant.ui.chat

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jarvis.assistant.memory.MemoryStatus
import dev.jarvis.assistant.memory.MemoryViewModel

/** Asks for permission before anything sensitive or uncertain is remembered. Shows one proposal at a time. */
@Composable
fun MemoryApprovalBanner(modifier: Modifier = Modifier, vm: MemoryViewModel = viewModel()) {
    val all by vm.memories.collectAsStateWithLifecycle()
    val proposal = all.firstOrNull { it.status == MemoryStatus.PENDING } ?: return
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (proposal.replacesId != null) "Update what JARVIS remembers?" else "Remember this?",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(proposal.content, style = MaterialTheme.typography.bodyMedium)
            Text("Asking because ${proposal.reason ?: "it may be sensitive"}. Stays on this phone.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.approve(proposal.id) }) { Text(if (proposal.replacesId != null) "Update" else "Save") }
                OutlinedButton(onClick = { vm.dismiss(proposal.id) }) { Text("Don't save") }
            }
        }
    }
}
