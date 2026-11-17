package dev.jarvis.assistant.settings

import java.security.GeneralSecurityException
import javax.crypto.BadPaddingException

// Pure JVM (no Android classes) so the decisions below are unit-tested with failure injection.

/** Encrypts and decrypts one secret. The Android implementation uses a hardware-backed, non-exportable Keystore key. */
interface SecretCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(packed: ByteArray): ByteArray
}

/** Thrown by a [SecretCrypto] when a stored secret can never be read again (the Keystore key is gone or invalidated). */
class SecretUnreadableException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/** Where the encrypted text is kept. Writes report whether they really reached storage. */
interface SecretStore {
    fun get(name: String): String?
    fun contains(name: String): Boolean
    fun put(name: String, value: String): Boolean
    fun remove(name: String): Boolean
}

/** What is really true about a saved key, found by trying to read it. */
enum class KeyState {
    /** No key is stored. */
    MISSING,

    /** A key is stored and decrypts. */
    USABLE,

    /** A key is stored but can't be read right now (for example the Keystore is temporarily unavailable). It was kept. */
    UNREADABLE_NOW,
}

/**
 * Saves API keys encrypted. Rules:
 *  - a save is only reported as done when the write really reached storage;
 *  - a stored key is deleted ONLY when it can never be read again (corrupt ciphertext, wrong or lost key);
 *  - any other failure (for example a temporarily unavailable Keystore) leaves the stored key untouched, so a
 *    momentary error can never silently destroy a working key.
 */
class SecretVault(private val crypto: SecretCrypto, private val store: SecretStore) {
    fun has(name: String): Boolean = store.contains(name)

    /** True only if the encrypted key is stored. Throws if encryption fails; nothing is stored then. */
    fun save(name: String, secret: String): Boolean {
        val packed = crypto.encrypt(secret.toByteArray(Charsets.UTF_8))
        return store.put(name, java.util.Base64.getEncoder().encodeToString(packed))
    }

    /** The key, or null when there is none or it can't be read right now. */
    fun read(name: String): String? {
        val packed = store.get(name) ?: return null
        return try {
            String(crypto.decrypt(java.util.Base64.getDecoder().decode(packed)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            store.remove(name); null // not valid base64: the stored text is damaged for good
        } catch (e: BadPaddingException) {
            store.remove(name); null // includes AEADBadTagException: wrong key or damaged data, never readable again
        } catch (e: SecretUnreadableException) {
            store.remove(name); null // the Keystore key itself is gone or invalidated
        } catch (e: GeneralSecurityException) {
            null // anything else may be temporary: keep the stored key
        } catch (e: RuntimeException) {
            null // for example IllegalStateException from a Keystore that is not ready: keep the stored key
        }
    }

    /**
     * Whether a usable key exists, found by actually reading it (so it can't say "saved" about a key that no longer
     * decrypts). Like [read], this removes a key that can never be read again, and then reports [KeyState.MISSING].
     */
    fun state(name: String): KeyState = when {
        read(name) != null -> KeyState.USABLE
        store.contains(name) -> KeyState.UNREADABLE_NOW
        else -> KeyState.MISSING
    }

    fun remove(name: String): Boolean = store.remove(name)
}
