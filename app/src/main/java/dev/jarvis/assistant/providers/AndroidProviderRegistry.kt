package dev.jarvis.assistant.providers

import android.content.Context

/**
 * The app's one shared [ProviderRegistry]. Resolves the application context and requires it to be a
 * [ProviderRegistryOwner]; otherwise it throws [NoProviderRegistryOwnerException]. Never creates a registry of its own.
 */
fun Context.providerRegistry(): ProviderRegistry = requireRegistryOwner(applicationContext).providerRegistry
