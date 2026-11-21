package dev.jarvis.assistant.providers

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * Whatever owns the one [ProviderRegistry] of the running app (in production, the Application object). Every screen and
 * view model gets the registry from it, so they all see the same providers.
 *
 * Contract for C4: persisted custom providers are loaded and registered while the owner is being built, before it can hand
 * the registry to anything.
 */
interface ProviderRegistryOwner {
    val providerRegistry: ProviderRegistry
}

/** Thrown when the app has no registry owner. There is deliberately no private fallback registry. */
class NoProviderRegistryOwnerException(found: String) : IllegalStateException(
    "The application does not own a provider registry (found $found). Every consumer must use the shared one.",
)

/**
 * The verification step behind the Android accessor: [candidate] must be a [ProviderRegistryOwner]. Anything else fails
 * loudly instead of quietly creating a registry that other parts of the app would not see.
 */
fun requireRegistryOwner(candidate: Any?): ProviderRegistryOwner =
    candidate as? ProviderRegistryOwner ?: throw NoProviderRegistryOwnerException(candidate?.javaClass?.name ?: "null")
