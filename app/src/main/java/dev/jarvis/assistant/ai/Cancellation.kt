package dev.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.HttpURLConnection

/**
 * Runs [block] and closes [conn] the moment the surrounding coroutine is cancelled.
 *
 * A blocking socket read can't be interrupted by coroutine cancellation, and `invokeOnCompletion` only fires once
 * the coroutine has completely finished, which it can't while stuck in that read. A child coroutine is cancelled
 * immediately when its parent is, even if the parent is blocked, so its `finally` closes the socket and the
 * blocked read throws straight away.
 */
internal suspend fun <T> withDisconnectOnCancel(conn: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
    val watcher = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            conn.disconnect()
        }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}
