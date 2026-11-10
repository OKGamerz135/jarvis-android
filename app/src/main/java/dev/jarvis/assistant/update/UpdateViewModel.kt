package dev.jarvis.assistant.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    val state: StateFlow<UpdateState> = UpdateManager.state

    fun check() {
        viewModelScope.launch { UpdateManager.check() }
    }

    fun install(update: UpdateState.Available) {
        viewModelScope.launch { UpdateManager.install(getApplication(), update) }
    }
}
