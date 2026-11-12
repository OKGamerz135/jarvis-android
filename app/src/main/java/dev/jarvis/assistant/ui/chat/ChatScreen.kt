package dev.jarvis.assistant.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import dev.jarvis.assistant.chat.SendResult
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.assistant.ai.ChatRole
import dev.jarvis.assistant.chat.ChatProblem
import dev.jarvis.assistant.chat.ChatViewModel
import dev.jarvis.assistant.chat.StoredMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChatScreen(
    onOpenProvider: () -> Unit,
    vm: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The failover countdown only runs while the app is on screen, so a switch is never silent.
    LifecycleStartEffect(Unit) {
        vm.setVisible(true)
        vm.checkRecovery()
        onStopOrDispose { vm.setVisible(false) }
    }
    val messages = ui.conversation.messages
    val streaming = ui.streaming

    // The list is reversed (newest at the bottom, anchored there), so index 0 is the newest item.
    LaunchedEffect(ui.conversation.id, messages.size, ui.generating) {
        listState.scrollToItem(0)
    }

    if (!ui.loaded) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && !ui.generating) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (ui.generating) {
                        item(key = "live") { LiveBubble(streaming, ui.note) }
                    }
                    items(messages.asReversed(), key = { it.id }) { Bubble(it) }
                }
            }
        }

        ui.failover?.let { f ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(f.announcement, style = MaterialTheme.typography.titleSmall)
                    Text(f.disclosure, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${f.from.label}: ${f.resetNote}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = vm::cancelFailover) { Text("Cancel") }
                }
            }
        }

        val temp = ui.temporaryProvider
        if (temp != null && !ui.generating) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val offer = ui.recoveryOffer
                Text(
                    if (offer != null) "${offer.label} should be available again. Switch back to it?"
                    else "Temporarily using ${temp.label} (${ui.preferredProvider?.label ?: "your preferred provider"} used up its quota).",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (offer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (offer != null) {
                    TextButton(onClick = vm::acceptRecovery) { Text("Switch back") }
                    TextButton(onClick = vm::declineRecovery) { Text("Stay") }
                }
            }
        }

        ui.problem?.let { p ->
            ProblemBanner(
                problem = p,
                canRetry = ui.conversation.awaitingReply && !ui.generating,
                onRetry = vm::retry,
                onOpenProvider = { vm.dismissProblem(); onOpenProvider() },
                onDismiss = vm::dismissProblem,
            )
        }

        if (ui.generating) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (ui.stopping) "Stopping..." else "JARVIS is answering...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(enabled = !ui.stopping, onClick = vm::stop) { Text("Stop") }
            }
        }
        ui.rejection?.let { text ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::dismissRejection) { Text("OK") }
            }
        }

        // The button and the keyboard's Send action share this one function, and so the same lock.
        val submit: () -> Unit = {
            if (vm.send(input) == SendResult.ACCEPTED) {
                input = ""
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(8000) },
                placeholder = { Text("Message JARVIS") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Button(enabled = input.isNotBlank() && !ui.generating, onClick = { submit() }) { Text("Send") }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("JARVIS", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "At your service. Type a message below. Your replies come from the provider selected in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Bubble(m: StoredMessage) {
    val mine = m.role == ChatRole.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        BubbleBody(
            mine = mine,
            text = m.text,
            footer = if (mine) null else m.provider?.let { it + (m.model?.let { model -> " · $model" } ?: "") },
            interrupted = m.interrupted,
        )
    }
}

@Composable
private fun LiveBubble(text: String?, note: String?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth(0.88f)) {
            if (!text.isNullOrEmpty()) {
                BubbleBody(mine = false, text = text, footer = null, interrupted = false)
            } else {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(note ?: "Thinking...", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleBody(mine: Boolean, text: String, footer: String?, interrupted: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(if (mine) 0.85f else 0.88f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
            if (interrupted) {
                Text(
                    "Response interrupted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (footer != null) {
                Text(
                    footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ProblemBanner(
    problem: ChatProblem,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(problem.message, color = MaterialTheme.colorScheme.error)
            if (problem.details != null) {
                Text(
                    problem.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRetry) Button(onClick = onRetry) { Text("Retry") }
                if (problem.needsSetup) OutlinedButton(onClick = onOpenProvider) { Text("Open AI providers") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    vm: ChatViewModel,
    onOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val format = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())
    if (ui.history.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No saved chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ui.history, key = { it.id }) { c ->
            Card(
                onClick = { vm.open(c.id); onOpened() },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            "${format.format(Instant.ofEpochMilli(c.updatedAt).atZone(ZoneId.systemDefault()))} · ${c.messageCount} messages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.delete(c.id) }) { Text("Delete") }
                }
            }
        }
    }
}
