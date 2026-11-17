package dev.jarvis.assistant.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps API keys encrypted (AES-GCM) with a key that lives inside Android's hardware-backed
 * Keystore and can't be exported. Only the encrypted text is written to app storage.
 * The app has backups switched off, so keys never leave the phone through cloud backup.
 * The save / read / delete rules live in [SecretVault] (unit-tested); this class only supplies Android's pieces.
 * Call these from a background thread: writes wait until they are on disk.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secure_keys", Context.MODE_PRIVATE)

    private val vault = SecretVault(
        crypto = object : SecretCrypto {
            override fun encrypt(plain: ByteArray): ByteArray {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key())
                return cipher.iv + cipher.doFinal(plain)
            }

            override fun decrypt(packed: ByteArray): ByteArray {
                try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
                    return cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
                } catch (e: KeyPermanentlyInvalidatedException) {
                    throw SecretUnreadableException("The Keystore key was invalidated", e)
                } catch (e: UnrecoverableKeyException) {
                    throw SecretUnreadableException("The Keystore key can't be recovered", e)
                }
            }
        },
        store = object : SecretStore {
            override fun get(name: String): String? = prefs.getString(name, null)
            override fun contains(name: String): Boolean = prefs.contains(name)
            override fun put(name: String, value: String): Boolean = prefs.edit().putString(name, value).commit()
            override fun remove(name: String): Boolean = prefs.edit().remove(name).commit()
        },
    )

    fun has(name: String): Boolean = vault.has(name)

    /** True only when the encrypted key is on disk. */
    fun save(name: String, secret: String): Boolean = vault.save(name, secret)

    /** Returns null when there is no key or it can't be read right now. A key is deleted only if it can never be read again. */
    fun read(name: String): String? = vault.read(name)

    fun remove(name: String): Boolean = vault.remove(name)

    /** Reads the key to find out whether it is really usable. Background thread only. */
    fun state(name: String): KeyState = vault.state(name)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "jarvis_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
