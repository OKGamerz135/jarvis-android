package dev.jarvis.assistant.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsStore by preferencesDataStore(name = "jarvis_settings")

/** Stores the non-secret settings on the phone. API keys live in [SecureKeyStore] instead. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<JarvisSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun update(transform: (JarvisSettings) -> JarvisSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private companion object {
        val PERSONALITY = stringPreferencesKey("personality")
        val TONE = stringPreferencesKey("tone")
        val HUMOUR = intPreferencesKey("humour")
        val LENGTH = stringPreferencesKey("length")
        val LANGUAGE = stringPreferencesKey("language")
        val ADDRESS = stringPreferencesKey("address_as")
        val CUSTOM = stringPreferencesKey("custom_instructions")
        val PROVIDER = stringPreferencesKey("provider")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val ACKNOWLEDGED = stringSetPreferencesKey("acknowledged_providers")

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        fun Preferences.toSettings(): JarvisSettings {
            val d = JarvisSettings()
            return JarvisSettings(
                personality = enumOr(this[PERSONALITY], d.personality),
                tone = enumOr(this[TONE], d.tone),
                humour = (this[HUMOUR] ?: d.humour).coerceIn(0, Humour.MAX),
                length = enumOr(this[LENGTH], d.length),
                language = this[LANGUAGE] ?: d.language,
                addressAs = this[ADDRESS] ?: d.addressAs,
                customInstructions = this[CUSTOM] ?: d.customInstructions,
                provider = enumOr(this[PROVIDER], d.provider),
                geminiModel = this[GEMINI_MODEL] ?: d.geminiModel,
                groqModel = this[GROQ_MODEL] ?: d.groqModel,
                acknowledged = (this[ACKNOWLEDGED] ?: emptySet())
                    .mapNotNull { n -> AiProvider.entries.firstOrNull { it.name == n } }
                    .toSet(),
            )
        }

        fun androidx.datastore.preferences.core.MutablePreferences.write(s: JarvisSettings) {
            this[PERSONALITY] = s.personality.name
            this[TONE] = s.tone.name
            this[HUMOUR] = s.humour
            this[LENGTH] = s.length.name
            this[LANGUAGE] = s.language
            this[ADDRESS] = s.addressAs
            this[CUSTOM] = s.customInstructions
            this[PROVIDER] = s.provider.name
            this[GEMINI_MODEL] = s.geminiModel
            this[GROQ_MODEL] = s.groqModel
            this[ACKNOWLEDGED] = s.acknowledged.map { it.name }.toSet()
        }
    }
}
