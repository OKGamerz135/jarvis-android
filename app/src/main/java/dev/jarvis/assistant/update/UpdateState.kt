package dev.jarvis.assistant.update

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(
        val build: Int,
        val versionName: String,
        val url: String,
        val sizeBytes: Long,
        /** Lowercase hex SHA-256 the release publishes for the file. An integrity check, not proof of who made it. */
        val sha256: String,
    ) : UpdateState

    /** progress is 0f..1f, or negative when the size is unknown. */
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class NeedsPermission(val update: Available) : UpdateState
    data class Error(val message: String) : UpdateState
}
