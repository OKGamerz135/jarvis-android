package dev.jarvis.assistant.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jarvis.assistant.BuildConfig

@Composable
fun UpdateSection(vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "Installed: version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Cloud: this only contacts github.com, and only when you tap a button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                UpdateState.Idle -> CheckButton(onClick = vm::check)

                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking GitHub...")
                }

                UpdateState.UpToDate -> {
                    Text("You're on the latest build.")
                    CheckButton(onClick = vm::check)
                }

                is UpdateState.Available -> {
                    Text(
                        "Build ${s.build} is available" + sizeSuffix(s.sizeBytes) + ".",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(onClick = { vm.install(s) }) { Text("Download and install") }
                }

                is UpdateState.Downloading -> {
                    Text("Downloading...")
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                UpdateState.Installing ->
                    Text("Download done. Waiting for Android's install prompt. If one appears, tap Update.")

                is UpdateState.NeedsPermission -> {
                    Text(
                        "Android needs your OK first: allow JARVIS to install apps, then come back and try again.",
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("Open permission settings") }
                    Button(onClick = { vm.install(s.update) }) { Text("Try again") }
                }

                is UpdateState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    CheckButton(onClick = vm::check)
                }
            }
        }
    }
}

@Composable
private fun CheckButton(onClick: () -> Unit) {
    Button(onClick = onClick) { Text("Check for updates") }
}

private fun sizeSuffix(bytes: Long): String =
    if (bytes > 0) " (%.1f MB)".format(bytes / 1_048_576.0) else ""
