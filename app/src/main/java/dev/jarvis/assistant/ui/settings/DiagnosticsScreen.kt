package dev.jarvis.assistant.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.assistant.BuildConfig
import dev.jarvis.assistant.diag.DiagnosticsFormat
import dev.jarvis.assistant.diag.DiagnosticsLog
import java.time.Instant
import java.time.ZoneId

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val entries by DiagnosticsLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val text = DiagnosticsFormat.toText(entries, "build ${BuildConfig.VERSION_CODE}", Instant.now(), ZoneId.systemDefault())

    ScreenColumn(modifier) {
        Text(
            "Metadata of the last ${entries.size} AI calls (max 100), newest first. Never contains API keys, " +
                "prompts or replies. Stored only on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = entries.isNotEmpty(), onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("JARVIS diagnostics", text))
                copied = true
            }) { Text("Copy log") }
            OutlinedButton(enabled = entries.isNotEmpty(), onClick = { DiagnosticsLog.clear(); copied = false }) {
                Text("Clear")
            }
        }
        if (copied) Text("Copied. Paste it into the chat with me.", color = MaterialTheme.colorScheme.primary)
        Text(
            if (entries.isEmpty()) "No calls recorded yet." else text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
