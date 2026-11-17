package dev.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jarvis.assistant.chat.ChatViewModel
import dev.jarvis.assistant.diag.DiagnosticsLog
import dev.jarvis.assistant.ui.chat.ChatScreen
import dev.jarvis.assistant.ui.chat.HistoryScreen
import dev.jarvis.assistant.ui.settings.DiagnosticsScreen
import dev.jarvis.assistant.ui.settings.MemoryScreen
import dev.jarvis.assistant.ui.settings.PersonalIntelligenceScreen
import dev.jarvis.assistant.ui.NavState
import dev.jarvis.assistant.ui.Screen
import dev.jarvis.assistant.ui.settings.ProviderScreen
import dev.jarvis.assistant.ui.settings.ProvidersScreen
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JarvisApp() {
    var nav by rememberSaveable(stateSaver = Saver(save = { it.encode() }, restore = { NavState.decode(it) })) {
        mutableStateOf(NavState())
    }
    val chatVm: ChatViewModel = viewModel()
    val screen = nav.current
    BackHandler(enabled = nav.canGoBack) { nav = nav.back() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (screen == Screen.Provider) nav.editing?.label ?: screen.title else screen.title) },
                navigationIcon = {
                    if (nav.canGoBack) {
                        IconButton(onClick = { nav = nav.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (screen == Screen.Home) {
                        TextButton(onClick = { nav = nav.open(Screen.History) }) { Text("Chats") }
                        IconButton(onClick = { chatVm.newChat() }) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = { nav = nav.open(Screen.Settings) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        when (screen) {
            Screen.Home -> ChatScreen(onOpenProvider = { nav = nav.open(Screen.Providers) }, vm = chatVm, modifier = modifier)
            Screen.History -> HistoryScreen(vm = chatVm, onOpened = { nav = nav.home() }, modifier = modifier)
            Screen.Settings -> SettingsHubScreen(
                onOpenPersonalIntelligence = { nav = nav.open(Screen.PersonalIntelligence) },
                onOpenProvider = { nav = nav.open(Screen.Providers) },
                onOpenDiagnostics = { nav = nav.open(Screen.Diagnostics) },
                onOpenMemory = { nav = nav.open(Screen.Memory) },
                modifier = modifier,
            )
            Screen.PersonalIntelligence -> PersonalIntelligenceScreen(modifier)
            Screen.Providers -> ProvidersScreen(
                tab = nav.providersTab,
                onTabChange = { nav = nav.selectTab(it) },
                onConfigure = { nav = nav.openProvider(it) },
                modifier = modifier,
                chatVm = chatVm,
            )
            Screen.Provider -> {
                val editing = nav.editing
                if (editing != null) {
                    ProviderScreen(
                        editing = editing,
                        onConfigureOther = { nav = nav.openProvider(it) },
                        modifier = modifier,
                    )
                }
            }
            Screen.Diagnostics -> DiagnosticsScreen(modifier)
            Screen.Memory -> MemoryScreen(modifier)
        }
    }
}
