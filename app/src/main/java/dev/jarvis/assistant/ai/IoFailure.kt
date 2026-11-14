package dev.jarvis.assistant.ai

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Turns a network exception into the right typed error. A read timeout means the provider accepted the connection
 * but sent nothing in time: that is the provider being slow, not the phone being offline, so it must not trigger
 * the "wait for network and retry" path or tell the user to check their internet. A connect timeout is different:
 * the provider could not be reached at all, which is treated as offline.
 */
fun ioFailure(e: IOException): LlmError =
    if (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) != true) {
        LlmError.Timeout("No answer from the provider before the time limit.")
    } else {
        LlmError.Offline
    }
