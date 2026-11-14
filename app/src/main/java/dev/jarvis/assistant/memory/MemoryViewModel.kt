package dev.jarvis.assistant.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Thin wrapper so screens can use the app-wide [MemoryService]. All work happens off the main thread inside the service. */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val service = Memories.get(app)

    val memories: StateFlow<List<Memory>> = service.memories

    private val _message = MutableStateFlow<String?>(null)

    /** A short result such as "JARVIS never stores secrets". Never contains memory text. */
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { service.refresh() }
    }

    fun refresh() { viewModelScope.launch { service.refresh() } }

    fun add(category: MemoryCategory, text: String) = viewModelScope.launch {
        _message.value = when (val r = service.addManual(category, text)) {
            is AddResult.Blocked -> r.reason
            is AddResult.Failed -> r.message
            AddResult.Duplicate -> "You already have that saved."
            else -> null
        }
    }

    fun edit(id: String, text: String) = viewModelScope.launch {
        _message.value = when (val r = service.edit(id, text)) {
            is AddResult.Blocked -> r.reason
            is AddResult.Failed -> r.message
            else -> null
        }
    }

    fun delete(id: String) = viewModelScope.launch { service.delete(id) }
    fun clearAll() = viewModelScope.launch { service.clearAll() }
    fun approve(id: String) = viewModelScope.launch { service.approve(id) }
    fun dismiss(id: String) = viewModelScope.launch { service.dismiss(id) }
    fun clearMessage() { _message.value = null }
}
