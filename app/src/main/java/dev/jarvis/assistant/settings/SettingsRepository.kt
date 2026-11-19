package dev.jarvis.assistant.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        store.edit { prefs ->
            val before = prefs.toSettings()
            prefs.write(transform(before), before)
        }
    }

    private companion object {
        val PERSONALITY = stringPreferencesKey("personality")
        val TONE = stringPreferencesKey("tone")
        val HUMOUR = intPreferencesKey("humour")
        val PROFANITY = intPreferencesKey("profanity")
        val LENGTH = stringPreferencesKey("length")
        val LANGUAGE = stringPreferencesKey("language")
        val ADDRESS = stringPreferencesKey("address_as")
        val CUSTOM = stringPreferencesKey("custom_instructions")
        val PROVIDER = stringPreferencesKey("provider")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
        val CLOUDFLARE_MODEL = stringPreferencesKey("cloudflare_model")
        val ZAI_MODEL = stringPreferencesKey("zai_model")
        val ACKNOWLEDGED = stringSetPreferencesKey("acknowledged_providers")
        val FAILOVER = booleanPreferencesKey("failover_enabled")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val ORDER = stringPreferencesKey("provider_order")
        val DISABLED = stringSetPreferencesKey("disabled_providers")

        fun Preferences.rawProviderPrefs() = RawProviderPrefs(this[PROVIDER], this[ACKNOWLEDGED], this[ORDER], this[DISABLED])

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        fun Preferences.toSettings(): JarvisSettings {
            val d = JarvisSettings()
            val providers = ProviderPrefsCodec.decode(rawProviderPrefs(), d.provider, d.providerOrder)
            return JarvisSettings(
                personality = enumOr(this[PERSONALITY], d.personality),
                tone = enumOr(this[TONE], d.tone),
                humour = (this[HUMOUR] ?: d.humour).coerceIn(0, Humour.MAX),
                profanity = (this[PROFANITY] ?: d.profanity).coerceIn(0, Profanity.MAX),
                length = enumOr(this[LENGTH], d.length),
                language = this[LANGUAGE] ?: d.language,
                addressAs = this[ADDRESS] ?: d.addressAs,
                customInstructions = this[CUSTOM] ?: d.customInstructions,
                provider = providers.provider,
                geminiModel = this[GEMINI_MODEL] ?: d.geminiModel,
                groqModel = this[GROQ_MODEL] ?: d.groqModel,
                openrouterModel = this[OPENROUTER_MODEL] ?: d.openrouterModel,
                cloudflareModel = this[CLOUDFLARE_MODEL] ?: d.cloudflareModel,
                zaiModel = this[ZAI_MODEL] ?: d.zaiModel,
                acknowledged = providers.acknowledged,
                failoverEnabled = this[FAILOVER] ?: d.failoverEnabled,
                memoryEnabled = this[MEMORY_ENABLED] ?: d.memoryEnabled,
                providerOrder = providers.order,
                disabledProviders = providers.disabled,
            )
        }

        fun androidx.datastore.preferences.core.MutablePreferences.write(s: JarvisSettings, before: JarvisSettings) {
            val providers = ProviderPrefsCodec.encode(
                new = ProviderPrefs(s.provider, s.acknowledged, s.providerOrder, s.disabledProviders),
                before = ProviderPrefs(before.provider, before.acknowledged, before.providerOrder, before.disabledProviders),
                existing = rawProviderPrefs(),
            )
            this[PERSONALITY] = s.personality.name
            this[TONE] = s.tone.name
            this[HUMOUR] = s.humour
            this[PROFANITY] = s.profanity.coerceIn(0, Profanity.MAX)
            this[LENGTH] = s.length.name
            this[LANGUAGE] = s.language
            this[ADDRESS] = s.addressAs
            this[CUSTOM] = s.customInstructions
            this[PROVIDER] = providers.provider
            this[GEMINI_MODEL] = s.geminiModel
            this[GROQ_MODEL] = s.groqModel
            this[OPENROUTER_MODEL] = s.openrouterModel
            this[CLOUDFLARE_MODEL] = s.cloudflareModel
            this[ZAI_MODEL] = s.zaiModel
            this[ACKNOWLEDGED] = providers.acknowledged
            this[FAILOVER] = s.failoverEnabled
            this[MEMORY_ENABLED] = s.memoryEnabled
            this[ORDER] = providers.order
            this[DISABLED] = providers.disabled
        }
    }
}
