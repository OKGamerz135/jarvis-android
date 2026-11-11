package dev.jarvis.assistant.chat

import dev.jarvis.assistant.ai.ChatRole
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Pure Kotlin plus org.json, so everything here is unit-tested on the build server.

data class StoredMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    /** Which provider and model answered. Null for the user's own messages. */
    val provider: String? = null,
    val model: String? = null,
    val timeMillis: Long,
    /** True when a reply was cut short (error, or the user tapped Stop). */
    val interrupted: Boolean = false,
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /** True when the last thing in the chat is the user's message with no answer, so Retry makes sense. */
    val awaitingReply: Boolean get() = messages.lastOrNull()?.role == ChatRole.USER

    fun withMessage(m: StoredMessage): Conversation = copy(messages = messages + m, updatedAt = m.timeMillis)

    companion object {
        fun empty(id: String, now: Long) = Conversation(id, "New chat", now, now, emptyList())

        fun titleFrom(text: String): String {
            val oneLine = text.trim().replace(Regex("\\s+"), " ")
            return if (oneLine.length <= 40) oneLine else oneLine.take(40).trimEnd() + "…"
        }
    }
}

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long, val messageCount: Int)

object ChatCodec {
    fun encode(c: Conversation): String {
        val messages = JSONArray()
        for (m in c.messages) {
            messages.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role.name)
                    .put("text", m.text)
                    .put("provider", m.provider ?: JSONObject.NULL)
                    .put("model", m.model ?: JSONObject.NULL)
                    .put("t", m.timeMillis)
                    .put("interrupted", m.interrupted),
            )
        }
        return JSONObject()
            .put("v", 1)
            .put("id", c.id)
            .put("title", c.title)
            .put("created", c.createdAt)
            .put("updated", c.updatedAt)
            .put("messages", messages)
            .toString()
    }

    /** Returns null for anything unreadable, so one damaged file never breaks the app. */
    fun decode(text: String): Conversation? {
        return try {
            val o = JSONObject(text)
            val id = o.getString("id")
            val array = o.getJSONArray("messages")
            val list = ArrayList<StoredMessage>()
            for (i in 0 until array.length()) {
                val m = array.getJSONObject(i)
                val role = ChatRole.entries.firstOrNull { it.name == m.optString("role") } ?: continue
                list += StoredMessage(
                    id = m.optString("id", "m$i"),
                    role = role,
                    text = m.optString("text"),
                    provider = if (m.isNull("provider")) null else m.optString("provider"),
                    model = if (m.isNull("model")) null else m.optString("model"),
                    timeMillis = m.optLong("t"),
                    interrupted = m.optBoolean("interrupted", false),
                )
            }
            Conversation(id, o.optString("title", "Chat"), o.optLong("created"), o.optLong("updated"), list)
        } catch (e: JSONException) {
            null
        }
    }
}
