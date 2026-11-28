package dev.jarvis.assistant.net

import java.io.Closeable
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tier N. t12c, t12d, t12e and t12f are reshaped from the S1 prototype suite: S1's three network
 * checkpoints are C1, C2 and C3 of the final design, and the claims (an abort before socket
 * creation, before connect, and after connect; nothing is sent) are kept, but they run on the pure
 * checkpoint logic with a scripted source instead of sockets and servers. The rest are new.
 *
 * The scripted source below can carry state the production types do not represent (link
 * addresses). That state lives only here.
 *
 * Assertion messages carry tags such as [MN2] naming the mutant they are designated to detect. The
 * tags exist for a later mutation review and change no behaviour. Matrix tests list every failing
 * case in one message, so a tag is found anywhere in the message, not only at its start.
 */
class NetworkSnapshotTest {

    private val netA = NetworkToken("net-A")
    private val netB = NetworkToken("net-B")
    private val dns = listOf("192.0.2.53", "192.0.2.54", "192.0.2.55")

    private fun link(
        iface: String? = "wlan0",
        servers: List<String> = dns,
        pdActive: Boolean = false,
        pdName: String? = null,
    ) = LinkState(iface, servers, pdActive, pdName)

    private class Source(var network: NetworkToken?, var link: LinkState?) : NetworkStateSource {
        var activeCalls = 0
        var linkCalls = 0
        var activeThrows: Exception? = null
        var linkThrows: Exception? = null
        var betweenReads: (() -> Unit)? = null
        var linkAddresses: List<String> = listOf("192.0.2.10") // test-only: the production types do not carry this

        val reads get() = activeCalls + linkCalls

        override fun activeNetwork(): NetworkToken? {
            activeCalls++
            activeThrows?.let { throw it }
            val n = network
            betweenReads?.invoke()
            return n
        }

        override fun linkState(network: NetworkToken): LinkState? {
            linkCalls++
            linkThrows?.let { throw it }
            return link
        }
    }

    private class Probe : Closeable {
        val closed = AtomicInteger()
        override fun close() { closed.incrementAndGet() }
    }

    private class Rig(val source: Source) {
        val hookCalls = AtomicInteger()
        val ctx = GuardCtx { hookCalls.incrementAndGet() }
        val probe = Probe().also { ctx.register(it) }
        val snapshot = NetworkSnapshot(ctx, source)
    }

    private fun rig(network: NetworkToken? = netA, link: LinkState? = link()) = Rig(Source(network, link))

    private fun violationOf(block: () -> Unit): GuardViolation {
        try {
            block()
        } catch (e: GuardViolation) {
            return e
        }
        fail("expected a GuardViolation")
        throw AssertionError()
    }

    /** The four checkpoints after C0, each as: run the checkpoint, and run the guarded action only if it passes. */
    private val checkpoints: List<Pair<String, (NetworkSnapshot, () -> Unit) -> Unit>> = listOf(
        "C1" to { s, act -> s.beforeSocketCreation(act) },
        "C2" to { s, act -> s.beforeConnect(act) },
        "C3" to { s, act -> s.afterConnect(); act() },
        "C4" to { s, act -> s.beforeFirstRequestByte(act) },
    )
    private val cpTag = mapOf("C1" to "[MN1]", "C2" to "[MN2]", "C3" to "[MN3]", "C4" to "[MN4]")

    // ---- reshaped from S1 ----

    @Test
    fun t12c_network_change_between_resolution_and_connection_aborts() {
        val r = rig()
        r.snapshot.capture()
        r.source.network = netB // the name lookup took long enough for the network to switch
        var created = false
        val v = violationOf { r.snapshot.beforeSocketCreation { created = true } }
        assertSame(GuardReason.NETWORK_CHANGED, v.reason)
        assertFalse("nothing is created or connected after the abort", created)
        assertEquals("registered resources are closed", 1, r.probe.closed.get())
    }

    @Test
    fun t12d_checkpoint_1_refuses_socket_creation_after_network_change() {
        val r = Source(netA, link()).let { Rig(it) }
        r.snapshot.capture()
        r.source.network = netB
        var created = false
        val v = violationOf { r.snapshot.beforeSocketCreation { created = true } }
        assertSame(GuardReason.NETWORK_CHANGED, v.reason)
        assertFalse("no socket may be created", created)
        assertSame("context is poisoned", v, r.ctx.violation)
    }

    @Test
    fun t12e_checkpoint_2_refuses_connect_after_network_change_and_sends_nothing() {
        val r = rig()
        r.snapshot.capture()
        r.snapshot.beforeSocketCreation { }
        r.source.network = netB
        var connected = false
        val v = violationOf { r.snapshot.beforeConnect { connected = true } }
        assertSame(GuardReason.NETWORK_CHANGED, v.reason)
        assertFalse("the connect must not run", connected)
        assertEquals("the socket is closed", 1, r.probe.closed.get())
    }

    @Test
    fun t12f_checkpoint_3_closes_when_network_changes_during_connect() {
        val r = rig()
        r.snapshot.capture()
        r.snapshot.beforeSocketCreation { }
        r.snapshot.beforeConnect { r.source.network = netB } // the connect itself spans the switch
        val v = violationOf { r.snapshot.afterConnect() }
        assertSame(GuardReason.NETWORK_CHANGED, v.reason)
        assertEquals("the connected socket is closed", 1, r.probe.closed.get())
        assertEquals(1, r.hookCalls.get())
    }

    // ---- new ----

    @Test
    fun n21_no_active_network_at_c0_is_not_a_violation_and_misuse_is_refused() {
        val none = rig(network = null)
        val baseline = none.snapshot.capture()
        assertSame("[MN12] no network is reported, not thrown", Baseline.NoNetwork, baseline)
        assertNull("[MN12] no violation", none.ctx.violation)
        assertEquals("[MN12] nothing is cancelled", 0, none.hookCalls.get())
        assertEquals("nothing is closed", 0, none.probe.closed.get())
        assertEquals("only the first read happened", 0, none.source.linkCalls)

        val ok = rig()
        assertTrue(ok.snapshot.capture() is Baseline.Captured)
        try { ok.snapshot.capture(); fail("a second capture must be refused") } catch (_: IllegalStateException) { }

        for ((name, cp) in checkpoints) {
            val early = rig()
            var ran = false
            try { cp(early.snapshot) { ran = true }; fail("$name before capture must be refused") } catch (_: IllegalStateException) { }
            assertFalse("$name action must not run before capture", ran)
        }
    }

    @Test
    fun n22_every_field_change_is_reported_at_every_checkpoint_and_the_guarded_action_never_runs() {
        val changes: List<Triple<String, String, (Source) -> Unit>> = listOf(
            Triple("handle", "[MN13]", { s: Source -> s.network = netB }),
            Triple("interface", "[MN14]", { s: Source -> s.link = link(iface = "rmnet0") }),
            Triple("dns-set", "[MN5]", { s: Source -> s.link = link(servers = listOf("192.0.2.53", "192.0.2.54", "192.0.2.99")) }),
            Triple("private-dns-state", "[MN8]", { s: Source -> s.link = link(pdActive = true) }),
            Triple("private-dns-name", "[MN8]", { s: Source -> s.link = link(pdActive = true, pdName = "dns.example.test") }),
        )
        assertEquals("fields", 5, changes.size)
        assertEquals("checkpoints", 4, checkpoints.size)
        val failures = ArrayList<String>()
        var cases = 0
        for ((cp, run) in checkpoints) for ((field, fieldTag, mutate) in changes) {
            cases++
            val r = rig()
            r.snapshot.capture()
            // Private DNS name case starts from an active Private DNS so only the name differs.
            if (field == "private-dns-name") {
                val base = Rig(Source(netA, link(pdActive = true, pdName = "other.example.test")))
                base.snapshot.capture()
                mutate(base.source)
                var ran = false
                val v = try { run(base.snapshot) { ran = true }; null } catch (e: GuardViolation) { e }
                if (v?.reason != GuardReason.NETWORK_CHANGED || ran) failures += "${cpTag[cp]}$fieldTag $cp/$field: reason=${v?.reason} actionRan=$ran"
                continue
            }
            mutate(r.source)
            var ran = false
            val v = try { run(r.snapshot) { ran = true }; null } catch (e: GuardViolation) { e }
            if (v?.reason != GuardReason.NETWORK_CHANGED || ran) failures += "${cpTag[cp]}$fieldTag $cp/$field: reason=${v?.reason} actionRan=$ran"
            else if (r.probe.closed.get() != 1 || r.hookCalls.get() != 1) failures += "${cpTag[cp]}$fieldTag $cp/$field: resources/hook not handled"
        }
        assertEquals("cases exercised", 20, cases)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())

        // Control: with no change every checkpoint passes and runs its action exactly once.
        for ((cp, run) in checkpoints) {
            val r = rig()
            r.snapshot.capture()
            var runs = 0
            run(r.snapshot) { runs++ }
            assertEquals("$cp control", 1, runs)
            assertNull(r.ctx.violation)
        }
    }

    @Test
    fun n23_a_dns_reorder_is_not_a_change_but_a_different_set_is() {
        for ((cp, run) in checkpoints) {
            val r = rig()
            r.snapshot.capture()
            r.source.link = link(servers = listOf("192.0.2.55", "192.0.2.53", "192.0.2.54")) // same set, new order
            var ran = false
            run(r.snapshot) { ran = true }
            assertTrue("[MN7] $cp: a reorder must not be reported", ran)
            assertNull("[MN7] $cp", r.ctx.violation)
        }
        val r = rig()
        r.snapshot.capture()
        r.source.link = link(servers = listOf("192.0.2.55", "192.0.2.53")) // a different set
        assertSame("[MN5] a different DNS set must be reported", GuardReason.NETWORK_CHANGED, violationOf { r.snapshot.beforeConnect { } }.reason)

        // The scripted source can change link addresses, which the production types cannot see. This part only
        // documents the claim: the compared state has no address field (pinned by n30), so such a change cannot
        // reach the comparison. It does not test the Android reader, which belongs to a later stage.
        for ((cp, run) in checkpoints) {
            val a = rig()
            a.snapshot.capture()
            a.source.linkAddresses = listOf("198.51.100.77")
            var ran = false
            run(a.snapshot) { ran = true }
            assertTrue("$cp: an address-only change is not a difference", ran)
        }
    }

    @Test
    fun n24_a_missing_network_or_missing_link_state_is_a_change() {
        // A link state that vanishes between the two reads (null link state for the handle that was just read).
        for ((cp, run) in checkpoints) {
            val r = rig()
            r.snapshot.capture()
            r.source.betweenReads = { r.source.link = null }
            var ran = false
            val v = violationOf { run(r.snapshot) { ran = true } }
            assertSame("[MN10] $cp", GuardReason.NETWORK_CHANGED, v.reason)
            assertFalse("[MN10] $cp action must not run", ran)
            assertEquals(2, r.source.activeCalls) // capture + this checkpoint: one active read each
        }
        // No active network at a checkpoint.
        for ((cp, run) in checkpoints) {
            val r = rig()
            r.snapshot.capture()
            r.source.network = null
            var ran = false
            val v = violationOf { run(r.snapshot) { ran = true } }
            assertSame("[MN9] $cp", GuardReason.NETWORK_CHANGED, v.reason)
            assertFalse("[MN9] $cp action must not run", ran)
        }
        // The same torn read at C0 itself.
        val c0 = rig()
        c0.source.betweenReads = { c0.source.link = null }
        assertSame("[MN10] C0", GuardReason.NETWORK_CHANGED, violationOf { c0.snapshot.capture() }.reason)
    }

    @Test
    fun n25_a_flap_back_to_the_same_network_is_not_reported_but_a_new_handle_is() {
        val r = rig()
        r.snapshot.capture()
        r.source.network = netB
        r.source.network = netA // A to B to A between two checkpoints
        var ran = false
        r.snapshot.beforeSocketCreation { ran = true }
        assertTrue("documented limitation: A-B-A between checkpoints is not reported", ran)
        assertNull(r.ctx.violation)

        // The same network after a reconnect with a new handle is a different network.
        val re = rig()
        re.snapshot.capture()
        re.source.network = NetworkToken("net-A-after-reconnect")
        assertSame("[MN13]", GuardReason.NETWORK_CHANGED, violationOf { re.snapshot.beforeConnect { } }.reason)
    }

    @Test
    fun n26_a_failing_source_is_an_unavailable_state_and_not_a_network_change() {
        class Case(val name: String, val prepare: (Source) -> Unit)
        val cases = listOf(
            Case("security exception on the first read") { it.activeThrows = SecurityException("denied") },
            Case("runtime failure on the first read") { it.activeThrows = IllegalStateException("gone") },
            Case("security exception on the second read") { it.linkThrows = SecurityException("denied") },
            Case("runtime failure on the second read") { it.linkThrows = RuntimeException("dead object") },
        )
        for (c in cases) {
            val c0 = rig()
            c.prepare(c0.source)
            assertSame("[MN11] C0 ${c.name}", GuardReason.NETWORK_STATE_UNAVAILABLE, violationOf { c0.snapshot.capture() }.reason)
            for ((cp, run) in checkpoints) {
                val r = rig()
                r.snapshot.capture()
                c.prepare(r.source)
                var ran = false
                val v = violationOf { run(r.snapshot) { ran = true } }
                assertSame("[MN11] $cp ${c.name}", GuardReason.NETWORK_STATE_UNAVAILABLE, v.reason)
                assertFalse("[MN11] $cp ${c.name}: action must not run", ran)
                assertEquals("[MN11] $cp ${c.name}: the failure message is the reason only", "NETWORK_STATE_UNAVAILABLE", v.message)
            }
        }
    }

    @Test
    fun n27_nothing_is_read_after_c4() {
        val r = rig()
        r.snapshot.capture()
        r.snapshot.beforeFirstRequestByte { }
        val readsAfterC4 = r.source.reads
        r.source.network = netB // a change after C4 is an ordinary I/O matter, not a guard matter
        var ran = 0
        r.snapshot.beforeSocketCreation { ran++ }
        r.snapshot.beforeConnect { ran++ }
        r.snapshot.afterConnect()
        r.snapshot.beforeFirstRequestByte { ran++ }
        assertEquals("[MN15] no read may follow C4", readsAfterC4, r.source.reads)
        assertEquals("actions still run", 3, ran)
        assertNull("[MN15] no violation after C4", r.ctx.violation)
    }

    @Test
    fun n28_a_change_at_c4_stops_the_send_and_a_clean_c4_runs_it_once() {
        val r = rig()
        r.snapshot.capture()
        r.source.link = link(servers = listOf("192.0.2.99"))
        var sent = 0
        val v = violationOf { r.snapshot.beforeFirstRequestByte { sent++ } }
        assertSame("[MN4]", GuardReason.NETWORK_CHANGED, v.reason)
        assertEquals("[MN4] the first request byte must not be written", 0, sent)
        assertEquals(1, r.probe.closed.get())

        val ok = rig()
        ok.snapshot.capture()
        val result = ok.snapshot.beforeFirstRequestByte { sent++; "written" }
        assertEquals("written", result)
        assertEquals(1, sent)
    }

    @Test
    fun n30_the_compared_state_has_exactly_the_five_designed_fields_and_prints_no_values() {
        fun fieldNames(c: Class<*>) = c.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSet()
        assertEquals(
            setOf("network", "interfaceName", "dnsServers", "privateDnsActive", "privateDnsServerName"),
            fieldNames(NetworkFingerprint::class.java),
        )
        assertEquals(
            setOf("interfaceName", "dnsServers", "privateDnsActive", "privateDnsServerName"),
            fieldNames(LinkState::class.java),
        )
        val fp = fingerprintOf(NetworkToken("secret-net-handle"), link(iface = "secret-iface", servers = listOf("203.0.113.7"), pdName = "secret.dns.example"))
        for (text in listOf(fp.toString(), NetworkToken("secret-net-handle").toString())) {
            for (secret in listOf("secret", "203.0.113", "wlan")) assertFalse("'$text' must not print '$secret'", text.contains(secret))
        }
    }
}
