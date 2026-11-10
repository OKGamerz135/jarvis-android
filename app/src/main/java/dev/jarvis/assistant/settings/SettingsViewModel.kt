package dev.jarvis.assistant.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val keys = SecureKeyStore(app)

    /** Null only for the split second before the saved settings have been read. */
    val settings: StateFlow<JarvisSettings?> = repo.settings
        .map<JarvisSettings, JarvisSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _keyPresent = MutableStateFlow(AiProvider.entries.associateWith { keys.has(it.keyName) })
    val keyPresent: StateFlow<Map<AiProvider, Boolean>> = _keyPresent.asStateFlow()

    fun update(transform: (JarvisSettings) -> JarvisSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun saveKey(provider: AiProvider, key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            keys.save(provider.keyName, key.filterNot { it.isWhitespace() })
            refreshKeys()
        }
    }

    fun removeKey(provider: AiProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            keys.remove(provider.keyName)
            refreshKeys()
        }
    }

    private fun refreshKeys() {
        _keyPresent.value = AiProvider.entries.associateWith { keys.has(it.keyName) }
    }
}
