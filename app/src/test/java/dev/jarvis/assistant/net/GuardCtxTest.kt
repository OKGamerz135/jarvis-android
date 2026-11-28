package dev.jarvis.assistant.net

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier N. t13 is reshaped from the S1 prototype suite: its claim (every violation is fail-closed
 * and poisons the context) is kept, but it is exercised on the pure context, without sockets,
 * DNS or an HTTP client. The others are new.
 *
 * Assertion messages that start with a tag such as [MC9] name the mutant that assertion is
 * designated to detect. The tags exist for a later mutation review and change no behaviour.
 */
class GuardCtxTest {

    private class Probe : Closeable {
        val closed = AtomicInteger()
        override fun close() { closed.incrementAndGet() }
    }

    private fun violationOf(block: () -> Unit): GuardViolation {
        try {
            block()
        } catch (e: GuardViolation) {
            return e
        }
        fail("expected a GuardViolation")
        throw AssertionError()
    }

    // ---- reshaped from S1 t13 ----

    @Test
    fun t13_every_violation_is_fail_closed_and_poisons_the_context() {
        val reasons = GuardReason.values()
        assertEquals("data set size", 10, reasons.size)
        for (r in reasons) {
            val hookCalls = AtomicInteger()
            val ctx = GuardCtx { hookCalls.incrementAndGet() }
            val before = Probe(); ctx.register(before)
            assertNull(ctx.violation)
            ctx.check() // not poisoned: no throw

            val v = violationOf { ctx.violate(r) }
            assertSame(r, v.reason)
            assertEquals("registered resource is closed", 1, before.closed.get())
            assertEquals("cancel hook ran", 1, hookCalls.get())
            assertSame("context is poisoned", v, ctx.violation)

            assertSame("later check is refused", v, violationOf { ctx.check() })
            val late = Probe()
            assertSame("later registration is refused", v, violationOf { ctx.register(late) })
            assertEquals("and the late resource is closed", 1, late.closed.get())
        }
    }

    // ---- new ----

    @Test
    fun n14_first_violation_wins_when_threads_race() {
        val threads = 8
        val iterations = 300
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val reasons = GuardReason.values()
            for (iter in 1..iterations) {
                val ctx = GuardCtx()
                val barrier = CyclicBarrier(threads)
                val results: List<Future<GuardViolation>> = (0 until threads).map { i ->
                    pool.submit<GuardViolation> {
                        barrier.await(30, TimeUnit.SECONDS)
                        violationOf { ctx.violate(reasons[i % reasons.size]) }
                    }
                }
                val thrown = results.map { it.get(60, TimeUnit.SECONDS) }
                val stored = ctx.violation
                assertNotNull("[MC9] a violation must be stored", stored)
                for (t in thrown) assertSame("[MC9] every racing caller must see the one stored violation", stored, t)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun n15a_a_violation_closes_every_registered_resource_once() {
        val ctx = GuardCtx()
        val probes = List(3) { Probe() }
        probes.forEach { ctx.register(it) }
        violationOf { ctx.violate(GuardReason.PEER_MISMATCH) }
        for ((i, p) in probes.withIndex()) assertEquals("[MC2] resource $i must be closed exactly once", 1, p.closed.get())
        violationOf { ctx.violate(GuardReason.PEER_MISMATCH) }
        for ((i, p) in probes.withIndex()) assertEquals("[MC2] resource $i must not be closed twice", 1, p.closed.get())
    }

    @Test
    fun n15b_a_resource_registered_after_the_violation_is_closed_at_once_and_refused() {
        val ctx = GuardCtx()
        val stored = violationOf { ctx.violate(GuardReason.CONNECT_PROHIBITED) }
        val late = Probe()
        val thrown = violationOf { ctx.register(late) }
        assertSame("[MC3] register must throw the stored violation", stored, thrown)
        assertEquals("[MC3] the late resource must be closed immediately", 1, late.closed.get())
    }

    @Test
    fun n15c_a_resource_racing_with_a_violation_is_always_closed_exactly_once() {
        val iterations = 2000
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (iter in 1..iterations) {
                val ctx = GuardCtx()
                val probe = Probe()
                val barrier = CyclicBarrier(2)
                val a = pool.submit {
                    barrier.await(30, TimeUnit.SECONDS)
                    try { ctx.register(probe) } catch (_: GuardViolation) { }
                }
                val b = pool.submit {
                    barrier.await(30, TimeUnit.SECONDS)
                    violationOf { ctx.violate(GuardReason.PEER_MISMATCH) }
                }
                a.get(60, TimeUnit.SECONDS); b.get(60, TimeUnit.SECONDS)
                assertEquals("[MC10] the racing resource must be closed exactly once (iteration $iter)", 1, probe.closed.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun n16_the_cancel_hook_runs_exactly_once_however_many_violations_follow() {
        val hookCalls = AtomicInteger()
        val ctx = GuardCtx { hookCalls.incrementAndGet() }
        violationOf { ctx.violate(GuardReason.DNS_SECOND_LOOKUP) }
        assertEquals("[MC4] the hook must run on the first violation", 1, hookCalls.get())
        violationOf { ctx.violate(GuardReason.PEER_MISMATCH) }
        violationOf { ctx.violate(GuardReason.NETWORK_CHANGED) }
        assertEquals("[MC5] the hook must not run again", 1, hookCalls.get())
    }

    @Test
    fun n17_check_rethrows_the_stored_violation_with_the_same_reason() {
        val ctx = GuardCtx()
        ctx.check()
        ctx.check()
        val stored = violationOf { ctx.violate(GuardReason.PROTOCOL_NOT_HTTP11) }
        for (i in 1..3) {
            val again = violationOf { ctx.check() }
            assertSame("[MC6] check must throw the stored violation", stored, again)
            assertSame("[MC6] with the same reason", GuardReason.PROTOCOL_NOT_HTTP11, again.reason)
        }
    }

    @Test
    fun n18_counters_are_atomic() {
        val threads = 8
        val perThread = 10_000
        val ctx = GuardCtx()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val barrier = CyclicBarrier(threads)
            val fs = (0 until threads).map {
                pool.submit {
                    barrier.await(30, TimeUnit.SECONDS)
                    repeat(perThread) { ctx.incrementLookups(); ctx.incrementNetworkPasses() }
                }
            }
            fs.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("[MC8] lookups", threads * perThread, ctx.lookups)
        assertEquals("[MC8] network passes", threads * perThread, ctx.networkPasses)
        val fresh = GuardCtx()
        assertEquals(1, fresh.incrementLookups())
        assertEquals(2, fresh.incrementLookups())
        assertEquals(1, fresh.incrementNetworkPasses())
        assertEquals("counters are independent", 2, fresh.lookups)
    }

    @Test
    fun n19_a_second_violation_keeps_the_first_reason_and_category() {
        val ctx = GuardCtx()
        val first = violationOf { ctx.violate(GuardReason.DNS_PROHIBITED_ADDRESS, AddressClass.PRIVATE) }
        val second = violationOf { ctx.violate(GuardReason.PEER_MISMATCH) }
        assertSame("[MC1] the first violation must win", first, second)
        assertSame("[MC1]", GuardReason.DNS_PROHIBITED_ADDRESS, ctx.violation!!.reason)
        assertSame("[MC1]", AddressClass.PRIVATE, ctx.violation!!.category)
        assertEquals("message stays the reason code only", "DNS_PROHIBITED_ADDRESS", second.message)
    }

    @Test
    fun n20_a_failing_resource_or_hook_does_not_stop_the_rest_or_mask_the_violation() {
        val hookCalls = AtomicInteger()
        val ctx = GuardCtx { hookCalls.incrementAndGet(); throw IllegalStateException("hook failed") }
        val ok1 = Probe(); val ok2 = Probe()
        val bad1 = object : Closeable { override fun close() { throw RuntimeException("close failed") } }
        val bad2 = object : Closeable { override fun close() { throw IOException("close failed too") } }
        for (c in listOf<Closeable>(bad1, ok1, bad2, ok2)) ctx.register(c)
        val v = violationOf { ctx.violate(GuardReason.BIND_FAILED) }
        assertSame("[MC7] the violation, not a close failure, must be thrown", GuardReason.BIND_FAILED, v.reason)
        assertEquals("[MC7] resources after a failing one must still be closed", 1, ok1.closed.get())
        assertEquals("[MC7]", 1, ok2.closed.get())
        assertEquals("[MC7] the hook must still run", 1, hookCalls.get())
        assertTrue(ctx.violation === v)
    }
}
