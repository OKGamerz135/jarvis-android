package dev.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.ui.settings.DiagnosticsScreen
import dev.jarvis.assistant.ui.settings.PersonalIntelligenceScreen
import dev.jarvis.assistant.ui.settings.ProviderScreen
import dev.jarvis.assistant.ui.settings.SettingsHubScreen
import dev.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DiagnosticsLog.init(applicationContext)
        setContent {
            JarvisTheme {
                JarvisApp()
            }
        }
    }
}

private enum class Screen(val title: String, val parent: Screen?) {
    Home("JARVIS", null),
    Settings("Settings", Home),
    PersonalIntelligence("Personal Intelligence", Settings),
    Provider("AI provider and key", Settings),
    Diagnostics("Diagnostics log", Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JarvisApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val parent = screen.parent
    BackHandler(enabled = parent != null) { if (parent != null) screen = parent }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    if (parent != null) {
                        IconButton(onClick = { screen = parent }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (screen == Screen.Home) {
                        IconButton(onClick = { screen = Screen.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (screen) {
            Screen.Home -> HomeScreen(modifier)
            Screen.Settings -> SettingsHubScreen(
                onOpenPersonalIntelligence = { screen = Screen.PersonalIntelligence },
                onOpenProvider = { screen = Screen.Provider },
                onOpenDiagnostics = { screen = Screen.Diagnostics },
                modifier = modifier,
            )
            Screen.PersonalIntelligence -> PersonalIntelligenceScreen(modifier)
            Screen.Provider -> ProviderScreen(modifier)
            Screen.Diagnostics -> DiagnosticsScreen(modifier)
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "JARVIS",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Settings and the Gemini connection are live. Open Settings, then AI provider and key, to send a test message. Chat arrives in the next step.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
