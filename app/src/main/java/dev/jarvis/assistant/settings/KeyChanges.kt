package dev.jarvis.assistant.settings

import dev.jarvis.assistant.providers.ProviderId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// Pure Kotlin on purpose (no android.* imports).

/**
 * "The saved API key of this provider was replaced, removed or found unreadable." Anything that keeps results obtained
 * with the old key (the connection tests) listens to this and drops them.
 */
object KeyChanges {
    private val _events = MutableSharedFlow<ProviderId>(extraBufferCapacity = 32)
    val events: SharedFlow<ProviderId> = _events.asSharedFlow()

    fun changed(provider: ProviderId) {
        _events.tryEmit(provider)
    }

    /**
     * The providers whose key was usable in [before] and is not in [now] (a key that stopped being usable behind our
     * back, for example one that was deleted because it could never be decrypted). A provider missing from [now]
     * counts as not usable. Providers that were not usable before are never reported.
     */
    fun lost(before: Map<ProviderId, KeyState>, now: Map<ProviderId, KeyState>): List<ProviderId> =
        before.filter { (id, state) -> state == KeyState.USABLE && now[id] != KeyState.USABLE }.keys.toList()
}
