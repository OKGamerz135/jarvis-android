package dev.jarvis.assistant.memory

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

enum class MemoryCategory(val label: String) {
    PREFERENCE("Preferences"),
    FAVOURITE("Favourites"),
    GOAL("Goals"),
    TECH("Devices and tech"),
    PROJECT("Projects"),
    DECISION("Decisions"),
    INSTRUCTION("Instructions"),
    PERSONAL("About you"),
    OTHER("Other"),
}

enum class MemorySource { AUTO, MANUAL }

/** One earlier message of the open conversation, oldest first in a list. Used to resolve "that", "it" and corrections. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** ACTIVE memories are used in chat. PENDING ones are proposals waiting for the user's yes or no and are never used. */
enum class MemoryStatus { ACTIVE, PENDING }

data class Memory(
    val id: String,
    val category: MemoryCategory,
    /** Normalised subject such as "favourite colour", used to find the record to update. Null for free-form facts. */
    val key: String?,
    val content: String,
    val source: MemorySource,
    /** 0.0 to 1.0. Explicit "remember that..." and manual entries are 1.0; pattern matches are lower. */
    val confidence: Double,
    val status: MemoryStatus,
    val createdAt: Long,
    val updatedAt: Long,
    /** Why a PENDING proposal is waiting (shown to the user). */
    val reason: String? = null,
    /** For a PENDING "update this" proposal: the ACTIVE memory it would change. */
    val replacesId: String? = null,
)

/** Something the extractor noticed in a user message. Not yet checked or saved. */
data class Candidate(
    val category: MemoryCategory,
    val key: String?,
    val content: String,
    val confidence: Double,
    /** The user said it with a hedge ("maybe", "I think"). Such statements are never saved or used to overwrite. */
    val uncertain: Boolean = false,
    /** The user asked to remember it ("remember that..."). */
    val explicit: Boolean = false,
)

object MemoryLimits {
    const val MAX_MEMORIES = 500
    const val MAX_CONTENT_CHARS = 300
    const val MAX_PENDING = 20
}
