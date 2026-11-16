package dev.jarvis.assistant.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

class SecretVaultTest {
    /** Not real encryption: reverses the bytes and prepends a marker so tests can tell ciphertext from plaintext. */
    private class FakeCrypto : SecretCrypto {
        var encryptFailure: Exception? = null
        var decryptFailure: Exception? = null
        override fun encrypt(plain: ByteArray): ByteArray { encryptFailure?.let { throw it }; return "ENC:".toByteArray() + plain.reversedArray() }
        override fun decrypt(packed: ByteArray): ByteArray { decryptFailure?.let { throw it }; return packed.copyOfRange(4, packed.size).reversedArray() }
    }

    private class FakeStore : SecretStore {
        val map = LinkedHashMap<String, String>()
        var writesSucceed = true
        override fun get(name: String) = map[name]
        override fun contains(name: String) = map.containsKey(name)
        override fun put(name: String, value: String): Boolean { if (writesSucceed) map[name] = value; return writesSucceed }
        override fun remove(name: String): Boolean { if (writesSucceed) map.remove(name); return writesSucceed }
    }

    private val crypto = FakeCrypto()
    private val store = FakeStore()
    private val vault = SecretVault(crypto, store)
    private val key = "sk-or-v1-0123456789abcdef0123456789abcdef"

    @Test fun roundTripAndReplace() {
        assertTrue(vault.save("p", key))
        assertTrue(vault.has("p"))
        assertEquals(key, vault.read("p"))
        assertTrue(vault.save("p", "second-key-value-123"))
        assertEquals("second-key-value-123", vault.read("p"))
        assertEquals(1, store.map.size)
    }

    @Test fun nothingReadableIsEverWrittenToStorage() {
        vault.save("p", key)
        val stored = store.map.getValue("p")
        assertFalse(stored, stored.contains(key))
        assertFalse(stored, stored.contains(key.reversed()))
        assertFalse(stored, stored.contains("sk-or"))
    }

    @Test fun saveIsOnlyReportedWhenTheWriteReachedStorage() {
        store.writesSucceed = false
        assertFalse(vault.save("p", key))
        assertFalse(vault.has("p"))
        assertNull(vault.read("p"))
    }

    @Test fun failedEncryptionStoresNothing() {
        crypto.encryptFailure = KeyStoreException("busy")
        assertThrows(KeyStoreException::class.java) { vault.save("p", key) }
        assertTrue(store.map.isEmpty())
    }

    @Test fun failedReplaceKeepsTheOldKey() {
        vault.save("p", key)
        store.writesSucceed = false
        assertFalse(vault.save("p", "new-key-value-456789"))
        store.writesSucceed = true
        assertEquals(key, vault.read("p"))
    }

    @Test fun removeDeletesAndReportsFailure() {
        vault.save("p", key)
        assertTrue(vault.remove("p"))
        assertFalse(vault.has("p"))
        vault.save("p", key)
        store.writesSucceed = false
        assertFalse(vault.remove("p"))
        assertTrue(vault.has("p"))
    }

    // ------------------------------------------------------------------ KEY-01: only permanent failures delete a key

    @Test fun temporaryKeystoreErrorsKeepTheStoredKey() {
        vault.save("p", key)
        for (e in listOf<Exception>(KeyStoreException("not ready"), GeneralSecurityException("hiccup"), IllegalStateException("keystore locked"), RuntimeException("oem bug"))) {
            crypto.decryptFailure = e
            assertNull(e.toString(), vault.read("p"))
            assertTrue("$e must not delete the key", vault.has("p"))
        }
        crypto.decryptFailure = null
        assertEquals("after the problem clears the key works again", key, vault.read("p"))
    }

    @Test fun permanentFailuresDeleteTheUnreadableKey() {
        for (e in listOf<Exception>(AEADBadTagException("tag"), BadPaddingException("pad"), SecretUnreadableException("gone"))) {
            vault.save("p", key)
            crypto.decryptFailure = e
            assertNull(vault.read("p"))
            assertFalse("$e should delete the unreadable key", vault.has("p"))
            crypto.decryptFailure = null
        }
    }

    @Test fun damagedStoredTextIsDeleted() {
        store.map["p"] = "!!!not base64!!!"
        assertNull(vault.read("p"))
        assertFalse(vault.has("p"))
    }

    @Test fun missingKeyReadsAsNull() {
        assertNull(vault.read("nothing"))
    }
}
