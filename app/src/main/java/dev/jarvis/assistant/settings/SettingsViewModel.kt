package dev.jarvis.assistant.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

    /**
     * What is really true about each saved key. It starts from "is something stored" and is corrected by actually
     * reading every key as soon as the screen needs it ([refreshKeys]), so it can't keep saying "saved" for a key that
     * no longer decrypts.
     */
    private val _keyState = MutableStateFlow(AiProvider.entries.associateWith { if (keys.has(it.keyName)) KeyState.USABLE else KeyState.MISSING })
    val keyState: StateFlow<Map<AiProvider, KeyState>> = _keyState.asStateFlow()

    /** True only for keys that are stored AND readable. */
    val keyPresent: StateFlow<Map<AiProvider, Boolean>> = _keyState
        .map { states -> states.mapValues { it.value == KeyState.USABLE } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiProvider.entries.associateWith { keys.has(it.keyName) })

    init {
        refreshKeys()
        // SambaNova was removed from the app. Delete any key saved for it so nothing is left behind.
        viewModelScope.launch(Dispatchers.IO) { runCatching { keys.remove("sambanova_api_key") } }
    }

    fun update(transform: (JarvisSettings) -> JarvisSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    private val _notice = MutableStateFlow<KeyNotice?>(null)

    /** A short message about the last key action, or null. Failures show here instead of crashing. */
    val notice: StateFlow<KeyNotice?> = _notice.asStateFlow()

    fun saveKey(provider: AiProvider, key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stored = keys.save(provider.keyName, key.filterNot { it.isWhitespace() })
                _notice.value = KeyNotice.saved(provider, stored)
                if (stored) KeyChanges.changed(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = KeyNotice.saveFailed(provider, e)
            }
            readKeyStates()
        }
    }

    fun removeKey(provider: AiProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val removed = keys.remove(provider.keyName)
                _notice.value = KeyNotice.removed(provider, removed)
                if (removed) KeyChanges.changed(provider)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = KeyNotice.removeFailed(provider, e)
            }
            readKeyStates()
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Re-reads every key to see which ones are really usable. Call when a screen showing key status appears. */
    fun refreshKeys() {
        viewModelScope.launch(Dispatchers.IO) { readKeyStates() }
    }

    private fun readKeyStates() {
        val before = _keyState.value
        val now = AiProvider.entries.associateWith { keys.state(it.keyName) }
        _keyState.value = now
        // A key that stopped being usable behind our back (for example it was deleted because it could never be
        // decrypted) also makes any test result obtained with it stale.
        for (p in AiProvider.entries) if (before[p] == KeyState.USABLE && now[p] != KeyState.USABLE) KeyChanges.changed(p)
    }
}
