package dev.jarvis.assistant.settings

import dev.jarvis.assistant.providers.ProviderDescriptor
import dev.jarvis.assistant.providers.ProviderId

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/** Keys shorter than this are rejected before anything is saved. (The existing rule, unchanged.) */
const val MIN_KEY_LENGTH = 20

/** What pasting [raw] into the key field means. Nothing here stores or sends anything. */
sealed interface KeyInputCheck {
    /** Nothing typed yet. Not an error: the Save button is simply off. */
    data object Untouched : KeyInputCheck

    /** Only spaces or line breaks. */
    data object BlankInput : KeyInputCheck

    data class TooShort(val length: Int, val min: Int, val providerHelp: String) : KeyInputCheck

    /** [key] is what will be saved: [raw] without any spaces, tabs or line breaks. [removedWhitespace] says that changed it. */
    data class Valid(val key: String, val removedWhitespace: Boolean) : KeyInputCheck

    val canSave: Boolean get() = this is Valid

    companion object {
        /** Same cleaning as before: every whitespace character is removed (pasted keys often carry a trailing newline). */
        fun check(raw: String, provider: ProviderDescriptor): KeyInputCheck {
            if (raw.isEmpty()) return Untouched
            val cleaned = raw.filterNot { it.isWhitespace() }
            if (cleaned.isEmpty()) return BlankInput
            if (cleaned.length < MIN_KEY_LENGTH) return TooShort(cleaned.length, MIN_KEY_LENGTH, provider.keyHelp)
            return Valid(cleaned, removedWhitespace = cleaned != raw)
        }

        /** The text to show under the field, or null when there is nothing to say. Never echoes any part of the key. */
        fun message(check: KeyInputCheck, provider: ProviderDescriptor): String? = when (check) {
            Untouched -> null
            BlankInput -> "That is only spaces or line breaks. Paste the API key itself."
            is TooShort ->
                "That is ${check.length} characters; a ${provider.label} key is normally longer (JARVIS needs at least ${check.min}). " +
                    "Check that you copied the whole key. " + check.providerHelp
            is Valid -> if (check.removedWhitespace) "Spaces or line breaks around or inside the key will be removed when it is saved." else null
        }
    }
}

/** The result of a save or remove, as data: whether it is an error is never guessed from the wording. */
data class KeyNotice(val provider: ProviderId, val message: String, val isError: Boolean) {
    companion object {
        fun saved(provider: ProviderId, stored: Boolean) =
            if (stored) KeyNotice(provider, "Key saved.", false)
            else KeyNotice(provider, "Couldn't save the key: the phone didn't write it to storage. Nothing was changed.", true)

        fun saveFailed(provider: ProviderId, e: Exception) =
            KeyNotice(provider, "Couldn't save the key: ${e.javaClass.simpleName}: ${e.message}", true)

        fun removed(provider: ProviderId, removed: Boolean) =
            if (removed) KeyNotice(provider, "Key removed.", false)
            else KeyNotice(provider, "Couldn't remove the key: the phone didn't write the change. Try again.", true)

        fun removeFailed(provider: ProviderId, e: Exception) =
            KeyNotice(provider, "Couldn't remove the key: ${e.javaClass.simpleName}: ${e.message}", true)
    }
}

/** What the model field means. [effective] is exactly what JARVIS will use (the same rule as JarvisSettings.modelOf). */
data class ModelInputNote(val effective: String, val usingDefault: Boolean, val message: String) {
    companion object {
        /** Blank (or only spaces) means the provider's default. No restrictions on what a model name looks like are invented. */
        fun of(raw: String, provider: ProviderDescriptor): ModelInputNote {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return ModelInputNote(
                    provider.defaultModel, true,
                    "The field is blank, so the default model is used: ${provider.defaultModel}.",
                )
            }
            val spaces = if (trimmed != raw) " Spaces at the start or end are ignored." else ""
            return if (trimmed == provider.defaultModel) {
                ModelInputNote(trimmed, true, "Using the default model: $trimmed.$spaces")
            } else {
                ModelInputNote(
                    trimmed, false,
                    "Using your model: $trimmed (default: ${provider.defaultModel}). JARVIS can't check the name here; " +
                        "run the availability check below to see whether your key can use it.$spaces",
                )
            }
        }
    }
}
