package dev.jarvis.assistant.settings

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Pure Kotlin on purpose (no android.* imports).

/**
 * "The saved API key of this provider was replaced, removed or found unreadable." Anything that keeps results obtained
 * with the old key (the connection tests) listens to this and drops them.
 */
object KeyChanges {
    private val _events = MutableSharedFlow<AiProvider>(extraBufferCapacity = 32)
    val events: SharedFlow<AiProvider> = _events.asSharedFlow()

    fun changed(provider: AiProvider) {
        _events.tryEmit(provider)
    }
}
