package dev.jarvis.assistant.settings

import android.content.Context
import dev.jarvis.assistant.providers.ProviderRegistry

/** Puts the Android key store behind a [KeyBook]. The key store itself, and how it encrypts, are not touched. */
object AndroidKeyBook {
    fun create(context: Context, registry: ProviderRegistry): KeyBook {
        val store = SecureKeyStore(context)
        return KeyBook(
            registry,
            object : KeyVault {
                override fun has(name: String) = store.has(name)
                override fun save(name: String, secret: String) = store.save(name, secret)
                override fun read(name: String) = store.read(name)
                override fun remove(name: String) = store.remove(name)
                override fun state(name: String) = store.state(name)
            },
        )
    }
}
