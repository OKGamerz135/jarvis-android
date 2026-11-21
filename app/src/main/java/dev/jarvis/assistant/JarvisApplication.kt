package dev.jarvis.assistant

import android.app.Application
import dev.jarvis.assistant.providers.ProviderRegistry
import dev.jarvis.assistant.providers.ProviderRegistryOwner

/**
 * Owns the one [ProviderRegistry] for the life of the process. The chat, the settings, the connection tests and the quota
 * action all receive this same instance.
 *
 * C4: persisted custom providers must be registered here, as part of creating the registry, so that no consumer can ever see
 * it before they are in.
 */
class JarvisApplication : Application(), ProviderRegistryOwner {
    override val providerRegistry: ProviderRegistry by lazy { ProviderRegistry.withBuiltIns() }
}
