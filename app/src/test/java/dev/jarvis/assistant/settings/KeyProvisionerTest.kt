package dev.jarvis.assistant.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

/**
 * P-04: SecureKeyStore.key() checked "is the key there?" and then created it, with nothing in between. Two threads
 * saving their first key at the same moment could both see "no key" and both create one under the same alias.
 *
 * The fake keystore below behaves like a Keystore alias: creating a key under an alias that already holds one
 * REPLACES it (last writer wins). That is how the failure shows up for real: whatever was encrypted with the
 * replaced key can no longer be decrypted.
 *
 * What this proves and what it does not: these tests prove the decision logic in [KeyProvisioner] (which every
 * SecureKeyStore instance uses) creates the key exactly once, even across separate instances. They do NOT prove
 * what the real AndroidKeyStore does when two keys are generated under one alias; that needs a device.
 */
class KeyProvisionerTest {

    private class FakeKey(val id: Int)

    /** An alias that holds at most one key; creating a key replaces whatever was there. */
    private class FakeAlias(private val pause: Boolean = true) {
        val created = AtomicInteger()
        val stored = AtomicReference<FakeKey?>(null)
        private val bothHaveLooked = CyclicBarrier(2)
        private val firstLookDone = ThreadLocal.withInitial { false }

        /** The first look of each thread waits for the other thread's first look, so both are sure to see "no key". */
        fun find(): FakeKey? {
            if (pause && !firstLookDone.get()) {
                firstLookDone.set(true)
                bothHaveLooked.await(5, TimeUnit.SECONDS)
            }
            return stored.get()
        }

        fun create(): FakeKey {
            val key = FakeKey(created.incrementAndGet())
            Thread.sleep(50) // keep the creating window open so a racing second creation would really overlap
            stored.set(key)
            return key
        }
    }

    private fun raceTwoInstances(alias: FakeAlias, a: KeyProvisioner<FakeKey>, b: KeyProvisioner<FakeKey>): List<FakeKey> {
        val got = CopyOnWriteArrayList<FakeKey>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val threads = listOf(a, b).map { p ->
            thread { try { got += p.getOrCreate() } catch (t: Throwable) { errors += t } }
        }
        threads.forEach { it.join(10_000) }
        assertTrue("a thread failed or hung: $errors", errors.isEmpty() && got.size == 2)
        return got
    }

    @Test fun twoSeparateInstancesRacingForAMissingKeyCreateItExactlyOnce() {
        val alias = FakeAlias()
        // Two separate provisioners, as two SecureKeyStore instances would have, each built the way SecureKeyStore builds it.
        val a = KeyProvisioner(alias::find, alias::create)
        val b = KeyProvisioner(alias::find, alias::create)

        val got = raceTwoInstances(alias, a, b)

        assertEquals("the key must be created exactly once", 1, alias.created.get())
        assertSame("both callers must get the same key", got[0], got[1])
        assertSame("the key that is stored must be the one both callers got", got[0], alias.stored.get())
    }

    @Test fun theLockIsSharedByEveryInstanceInTheProcessNotJustOneInstance() {
        // Negative control: instances with their OWN locks do not exclude each other, and the duplicate creation
        // comes back. This is why the lock has to be process-wide, and it shows the fake really can reproduce the race.
        val alias = FakeAlias()
        val a = KeyProvisioner(alias::find, alias::create, lock = Any())
        val b = KeyProvisioner(alias::find, alias::create, lock = Any())

        raceTwoInstances(alias, a, b)

        assertEquals("with separate locks the race is expected to come back", 2, alias.created.get())
    }

    @Test fun manyThreadsAcrossManyInstancesStillCreateTheKeyOnce() {
        repeat(40) { round ->
            val alias = FakeAlias(pause = false)
            val provisioners = List(4) { KeyProvisioner(alias::find, alias::create) }
            val got = CopyOnWriteArrayList<FakeKey>()
            val errors = CopyOnWriteArrayList<Throwable>()
            val start = CyclicBarrier(8)
            val threads = List(8) { i ->
                thread { try { start.await(5, TimeUnit.SECONDS); got += provisioners[i % 4].getOrCreate() } catch (t: Throwable) { errors += t } }
            }
            threads.forEach { it.join(10_000) }
            assertTrue("round $round: $errors", errors.isEmpty() && got.size == 8)
            assertEquals("round $round", 1, alias.created.get())
            assertTrue("round $round: everyone must hold the same key", got.all { it === got[0] })
        }
    }

    @Test fun anExistingKeyIsReturnedAndNeverCreatedOrReplaced() {
        val alias = FakeAlias(pause = false)
        val existing = FakeKey(99)
        alias.stored.set(existing)
        val p = KeyProvisioner(alias::find, alias::create)

        repeat(5) { assertSame(existing, p.getOrCreate()) }
        val threads = List(6) { thread { assertSame(existing, KeyProvisioner(alias::find, alias::create).getOrCreate()) } }
        threads.forEach { it.join(10_000) }

        assertEquals(0, alias.created.get())
        assertSame(existing, alias.stored.get())
    }

    @Test fun laterCallsReuseTheKeyTheFirstCallCreated() {
        val alias = FakeAlias(pause = false)
        val p = KeyProvisioner(alias::find, alias::create)
        val first = p.getOrCreate()
        repeat(5) { assertSame(first, p.getOrCreate()) }
        assertEquals(1, alias.created.get())
    }

    @Test fun aFailedCreationIsReportedReleasesTheLockAndLeavesNothingStored() {
        var attempts = 0
        val stored = AtomicReference<FakeKey?>(null)
        val p = KeyProvisioner<FakeKey>(
            find = { stored.get() },
            create = {
                attempts++
                if (attempts == 1) throw IllegalStateException("keystore busy")
                FakeKey(attempts).also { stored.set(it) }
            },
        )
        try { p.getOrCreate(); fail("the failure must reach the caller") } catch (e: IllegalStateException) { assertEquals("keystore busy", e.message) }
        assertNull("a failed creation must not leave a half-made key behind", stored.get())

        // The lock was released, and a later call can try again.
        val key = p.getOrCreate()
        assertEquals(2, key.id)
        assertSame(key, stored.get())
    }

    @Test fun aFailingLookupIsReportedAndCreatesNothing() {
        val created = AtomicInteger()
        val p = KeyProvisioner<FakeKey>(find = { throw IllegalStateException("cannot read the alias") }, create = { created.incrementAndGet(); FakeKey(1) })
        try { p.getOrCreate(); fail("the failure must reach the caller") } catch (e: IllegalStateException) { assertEquals("cannot read the alias", e.message) }
        assertEquals("a lookup that failed must never be treated as 'no key'", 0, created.get())
    }

    // ---- the real harm, end to end: a saved key that can no longer be read ----

    /** A SecretCrypto that really encrypts (AES-GCM) with whatever key the provisioner hands out. */
    private class AesKey(val id: Int, val key: SecretKey)

    private class AesAlias {
        val created = AtomicInteger()
        val stored = AtomicReference<AesKey?>(null)
        private val bothHaveLooked = CyclicBarrier(2)
        private val firstLookDone = ThreadLocal.withInitial { false }
        @Volatile var armed = true
        fun find(): AesKey? {
            if (armed && !firstLookDone.get()) { firstLookDone.set(true); bothHaveLooked.await(5, TimeUnit.SECONDS) }
            return stored.get()
        }
        fun create(): AesKey {
            val key = AesKey(created.incrementAndGet(), KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            Thread.sleep(50)
            stored.set(key)
            return key
        }
    }

    private class MemoryStore : SecretStore {
        private val m = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(name: String) = m[name]
        override fun contains(name: String) = m.containsKey(name)
        override fun put(name: String, value: String): Boolean { m[name] = value; return true }
        override fun remove(name: String): Boolean { m.remove(name); return true }
    }

    private fun vaultOver(provisioner: KeyProvisioner<AesKey>, store: SecretStore) = SecretVault(
        crypto = object : SecretCrypto {
            override fun encrypt(plain: ByteArray): ByteArray {
                val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, provisioner.getOrCreate().key)
                return c.iv + c.doFinal(plain)
            }
            override fun decrypt(packed: ByteArray): ByteArray {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, provisioner.getOrCreate().key, GCMParameterSpec(128, packed, 0, 12))
                return c.doFinal(packed, 12, packed.size - 12)
            }
        },
        store = store,
    )

    @Test fun twoFirstSavesAtTheSameMomentBothStayReadable() {
        val alias = AesAlias()
        val store = MemoryStore() // one shared storage, as the real app has
        val vaultA = vaultOver(KeyProvisioner(alias::find, alias::create), store)
        val vaultB = vaultOver(KeyProvisioner(alias::find, alias::create), store)

        val threads = listOf(thread { vaultA.save("one", "first-secret") }, thread { vaultB.save("two", "second-secret") })
        threads.forEach { it.join(10_000) }
        alias.armed = false // the two racing saves are over; reading back must not wait for a partner

        assertEquals("first-secret", vaultA.read("one"))
        assertEquals("second-secret", vaultB.read("two"))
        assertNotNull(store.get("one")); assertNotNull(store.get("two")) // and neither was deleted as unreadable
        assertEquals(1, alias.created.get())
    }

    @Test fun normalSaveAndReadStillWorkThroughTheProvisioner() {
        val alias = AesAlias()
        val store = MemoryStore()
        val vault = vaultOver(KeyProvisioner({ alias.stored.get() }, alias::create), store)
        assertTrue(vault.save("k", "abc123"))
        assertEquals("abc123", vault.read("k"))
        assertEquals(KeyState.USABLE, vault.state("k"))
        assertEquals(KeyState.MISSING, vault.state("other"))
        assertEquals(1, alias.created.get())
    }
}
