package dev.jarvis.assistant.ai

import dev.jarvis.assistant.providers.ProviderDescriptor
import dev.jarvis.assistant.providers.ProviderId
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.toAiProviderOrNull
import dev.jarvis.assistant.settings.AiProvider

// Pure Kotlin on purpose (no android.* imports): the built-in adapters are passed in as functions.

/**
 * Where a REGISTERED custom provider gets its adapter. Nothing implements this in the app yet (custom providers cannot
 * run before their storage, consent and endpoint validation exist), so [NONE] is what production uses.
 */
fun interface CustomAdapterSource {
    fun adapterFor(descriptor: ProviderDescriptor): OpenAiCompatibleProvider?

    companion object {
        val NONE = CustomAdapterSource { null }
    }
}

/**
 * The one place that decides which adapter, if any, serves a provider id. It answers from the shared [registry]:
 *
 *  1. An id that is not registered has no adapter, however it is spelled. A well-formed custom id (`custom:<uuid>`) proves
 *     nothing; only a registry entry does.
 *  2. A registered built-in provider gets its own existing adapter, never another provider's.
 *  3. A registered custom provider gets an adapter only if [custom] has one for its descriptor (none today).
 *  4. Everything else gets null. There is no default adapter and no fallback to a built-in provider.
 *
 * The callers still refuse a null with a clear message before anything is sent, so this is the second of two layers.
 */
class ProviderAdapters(
    private val registry: ProviderRegistry,
    private val llm: (AiProvider) -> LlmProvider,
    private val diagnostics: (AiProvider) -> ProviderDiagnostics,
    private val custom: CustomAdapterSource = CustomAdapterSource.NONE,
) {
    private sealed interface Target {
        data class BuiltIn(val provider: AiProvider) : Target
        data class Custom(val adapter: OpenAiCompatibleProvider) : Target
    }

    private fun targetOf(id: ProviderId): Target? {
        val descriptor = registry.get(id) ?: return null // unregistered: nothing, however valid the id looks
        if (descriptor.id != id) return null
        if (descriptor.isBuiltIn) {
            val provider = id.toAiProviderOrNull() ?: return null
            return Target.BuiltIn(provider)
        }
        return custom.adapterFor(descriptor)?.let { Target.Custom(it) }
    }

    fun llmFor(id: ProviderId): LlmProvider? = when (val t = targetOf(id)) {
        is Target.BuiltIn -> llm(t.provider)
        is Target.Custom -> t.adapter
        null -> null
    }

    fun diagnosticsFor(id: ProviderId): ProviderDiagnostics? = when (val t = targetOf(id)) {
        is Target.BuiltIn -> diagnostics(t.provider)
        is Target.Custom -> t.adapter
        null -> null
    }
}
