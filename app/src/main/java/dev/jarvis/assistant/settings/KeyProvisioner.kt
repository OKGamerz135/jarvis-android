package dev.jarvis.assistant.settings

// Pure JVM (no Android classes) so the race below can be reproduced and tested without a device.

/**
 * Finds the one encryption key, or creates it the first time. This is the logic that used to sit inside
 * SecureKeyStore.key(): "is the key there? no: make it".
 *
 * That check-then-create had nothing between the two steps. Two callers on a fresh install could both see "no key"
 * and both create one under the same alias; the second creation replaces the first, and anything already encrypted
 * with the replaced key can never be decrypted again. Creation is therefore done under a lock that is shared by the
 * whole process (every SecureKeyStore instance, not just one), and the lookup is repeated inside the lock, so a
 * caller that waited for the lock finds the key the winner just made instead of making another.
 *
 * An existing key is never created again and never replaced: [create] runs only when the lookup, repeated inside
 * the lock, still finds nothing. A lookup that throws is not "no key" and creates nothing.
 */
class KeyProvisioner<K : Any>(
    private val find: () -> K?,
    private val create: () -> K,
    private val lock: Any = PROCESS_LOCK,
) {
    fun getOrCreate(): K {
        find()?.let { return it } // the usual case, and it takes no lock
        synchronized(lock) {
            find()?.let { return it } // someone may have created it while we waited
            return create()
        }
    }

    companion object {
        /** One lock for the whole process, because each SecureKeyStore instance builds its own provisioner. */
        val PROCESS_LOCK = Any()
    }
}
