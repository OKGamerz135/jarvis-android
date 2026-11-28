package dev.jarvis.assistant.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier N. t01a and t02a are ported from the S1 prototype suite: same address lists, same
 * assertion strength. Only the call changed, from allowed(a) to classification. The remaining
 * tests are new.
 *
 * The expected values here are typed by hand from the IANA IPv4 Special-Purpose Address Registry
 * (last updated 2025-10-09) and are deliberately independent of the production table. They share
 * one source with it, so a transcription error in both would not be caught here.
 */
class AddressPolicyTest {

    private val policy: AddressPolicy = ProductionAddressPolicy

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)
    private fun cls(s: String): AddressClass = policy.classify(ip(s))

    // ---- test-local CIDR arithmetic, independent of the production code ----
    private fun toInt(s: String): Long {
        val p = s.split('.').map { it.toLong() }
        return (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }
    private fun str(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
    private fun first(cidr: String): Long {
        val (a, l) = cidr.split('/'); val size = 1L shl (32 - l.toInt()); return toInt(a) / size * size
    }
    private fun last(cidr: String): Long = first(cidr) + (1L shl (32 - cidr.substringAfter('/').toInt())) - 1
    private fun classOfValue(v: Long): AddressClass = policy.classify(
        InetAddress.getByAddress(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())),
    )

    // ---------------- ported from S1 ----------------

    @Test
    fun t01a_policy_public_ipv4_allowed__POLICY_ONLY_no_transport() {
        val addrs = listOf(
            "8.8.8.8", "1.1.1.1", "9.9.9.9", "93.184.216.34", "100.63.255.255", "100.128.0.0", "172.15.255.255",
            "172.32.0.0", "192.167.255.255", "192.169.0.0", "169.253.255.255", "169.255.0.0", "11.0.0.0",
            "126.255.255.255", "128.0.0.0", "223.255.255.255", "198.17.255.255", "198.20.0.0", "192.0.3.0",
        )
        assertEquals("data set size", 19, addrs.size)
        for (a in addrs) assertTrue("$a should be allowed", policy.allows(ip(a)))
    }

    private val prohibited = listOf(
        "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.1.1",
        "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.127.255.255", "100.100.100.200", "168.63.129.16",
        "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "0.0.0.0", "0.1.2.3", "192.0.2.1",
        "198.51.100.7", "203.0.113.9", "198.18.0.1", "198.19.255.255", "192.88.99.1", "192.0.0.170", "::1", "::",
        "fe80::1", "fd00::1", "2001:4860:4860::8888", "64:ff9b::808:808", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
    )

    @Test
    fun t02a_policy_prohibited_set_rejected__POLICY_ONLY() {
        assertEquals("data set size", 34, prohibited.size)
        for (a in prohibited) assertFalse("$a must be rejected", policy.allows(ip(a)))
    }

    // ---------------- new ----------------

    /** Hand-typed registry records: blocks and the class the guard is expected to give them. */
    private class Row(val blocks: List<String>, val expected: AddressClass)

    private val registry = listOf(
        Row(listOf("0.0.0.0/8"), AddressClass.UNSPECIFIED),
        Row(listOf("0.0.0.0/32"), AddressClass.UNSPECIFIED),
        Row(listOf("10.0.0.0/8"), AddressClass.PRIVATE),
        Row(listOf("100.64.0.0/10"), AddressClass.SHARED),
        Row(listOf("127.0.0.0/8"), AddressClass.LOOPBACK),
        Row(listOf("169.254.0.0/16"), AddressClass.LINK_LOCAL),
        Row(listOf("172.16.0.0/12"), AddressClass.PRIVATE),
        Row(listOf("192.0.0.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.0.0.0/29"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.0.0.8/32"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.0.0.9/32"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.0.0.10/32"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.0.0.170/32", "192.0.0.171/32"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.0.2.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.31.196.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.52.193.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.88.99.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.88.99.2/32"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("192.168.0.0/16"), AddressClass.PRIVATE),
        Row(listOf("192.175.48.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("198.18.0.0/15"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("198.51.100.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("203.0.113.0/24"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("240.0.0.0/4"), AddressClass.SPECIAL_PURPOSE),
        Row(listOf("255.255.255.255/32"), AddressClass.SPECIAL_PURPOSE),
    )

    @Test
    fun n5_every_registry_record_is_denied_with_the_expected_class() {
        assertEquals("registry records", 25, registry.size)
        assertEquals("registry blocks", 26, registry.sumOf { it.blocks.size })
        for (row in registry) for (b in row.blocks) {
            val lo = first(b); val hi = last(b); val mid = lo + (hi - lo) / 2
            for (v in listOf(lo, mid, hi)) {
                val c = classOfValue(v)
                assertFalse("$b (${str(v)}) must not be PUBLIC", c == AddressClass.PUBLIC)
                assertEquals("$b (${str(v)})", row.expected, c)
            }
        }
        // 255.255.255.255/32 has no table entry of its own: it is covered by 240.0.0.0/4.
        assertEquals(AddressClass.SPECIAL_PURPOSE, cls("255.255.255.255"))
        assertEquals(AddressClass.SPECIAL_PURPOSE, cls("255.255.255.254"))
        assertEquals(AddressClass.SPECIAL_PURPOSE, cls("240.0.0.0"))
        // The globally reachable records are denied too: this is our policy, not an IANA rule.
        for (a in listOf("192.0.0.9", "192.0.0.10", "192.31.196.1", "192.52.193.1", "192.175.48.1")) {
            assertEquals(a, AddressClass.SPECIAL_PURPOSE, cls(a))
        }
    }

    @Test
    fun n6_the_benchmarking_block_198_18_0_0_15_has_exact_edges() {
        assertEquals(AddressClass.PUBLIC, cls("198.17.255.255"))
        assertEquals(AddressClass.SPECIAL_PURPOSE, cls("198.18.0.0"))
        assertEquals(AddressClass.SPECIAL_PURPOSE, cls("198.18.128.7"))
        assertEquals(AddressClass.SPECIAL_PURPOSE, cls("198.19.255.255"))
        assertEquals(AddressClass.PUBLIC, cls("198.20.0.0"))
    }

    @Test
    fun n7_metadata_and_redundant_addresses_classify_as_designed() {
        assertEquals(AddressClass.METADATA, cls("168.63.129.16"))
        assertEquals("neighbour below", AddressClass.PUBLIC, cls("168.63.129.15"))
        assertEquals("neighbour above", AddressClass.PUBLIC, cls("168.63.129.17"))
        assertEquals("no entry of its own: inside link-local", AddressClass.LINK_LOCAL, cls("169.254.169.254"))
        assertEquals("no entry of its own: inside shared space", AddressClass.SHARED, cls("100.100.100.200"))
    }

    @Test
    fun n8_each_class_is_produced_by_a_representative_address() {
        val cases = listOf(
            "0.1.2.3" to AddressClass.UNSPECIFIED,
            "10.1.2.3" to AddressClass.PRIVATE,
            "172.20.0.1" to AddressClass.PRIVATE,
            "192.168.5.5" to AddressClass.PRIVATE,
            "100.64.0.1" to AddressClass.SHARED,
            "127.0.0.2" to AddressClass.LOOPBACK,
            "169.254.1.1" to AddressClass.LINK_LOCAL,
            "168.63.129.16" to AddressClass.METADATA,
            "224.0.0.251" to AddressClass.MULTICAST,
            "239.255.255.250" to AddressClass.MULTICAST,
            "198.51.100.1" to AddressClass.SPECIAL_PURPOSE,
            "8.8.8.8" to AddressClass.PUBLIC,
        )
        assertEquals("data set size", 12, cases.size)
        for ((a, c) in cases) assertEquals(a, c, cls(a))
        val seen = cases.map { it.second }.toSet() + AddressClass.NOT_IPV4
        assertEquals("every class value is exercised (NOT_IPV4 in n9)", AddressClass.values().toSet(), seen)
    }

    @Test
    fun n9_non_ipv4_addresses_are_not_ipv4_and_mapped_ones_follow_the_ipv4_rules() {
        val notV4 = listOf("::1", "::", "fe80::1", "fd00::1", "2001:4860:4860::8888", "64:ff9b::808:808", "::1.2.3.4")
        assertEquals("data set size", 7, notV4.size)
        for (a in notV4) {
            val addr = ip(a)
            assertTrue("$a should be an Inet6Address", addr is Inet6Address)
            assertEquals(a, AddressClass.NOT_IPV4, policy.classify(addr))
        }
        val mappedLoopback = ip("::ffff:127.0.0.1")
        assertTrue("the JDK turns a mapped literal into an Inet4Address", mappedLoopback is Inet4Address)
        assertEquals(AddressClass.LOOPBACK, policy.classify(mappedLoopback))
        assertEquals(AddressClass.PRIVATE, cls("::ffff:10.0.0.1"))
        assertEquals(AddressClass.PUBLIC, cls("::ffff:8.8.8.8"))
    }

    @Test
    fun n10_sweep_agrees_with_an_independent_oracle_and_never_calls_a_matching_address_public() {
        // Independent oracle: hand-typed table. Pairwise disjoint, so any lookup order gives one answer.
        val oracle = listOf(
            "0.0.0.0/8" to AddressClass.UNSPECIFIED, "10.0.0.0/8" to AddressClass.PRIVATE,
            "172.16.0.0/12" to AddressClass.PRIVATE, "192.168.0.0/16" to AddressClass.PRIVATE,
            "100.64.0.0/10" to AddressClass.SHARED, "127.0.0.0/8" to AddressClass.LOOPBACK,
            "169.254.0.0/16" to AddressClass.LINK_LOCAL, "168.63.129.16/32" to AddressClass.METADATA,
            "224.0.0.0/4" to AddressClass.MULTICAST, "192.0.0.0/24" to AddressClass.SPECIAL_PURPOSE,
            "192.0.2.0/24" to AddressClass.SPECIAL_PURPOSE, "192.31.196.0/24" to AddressClass.SPECIAL_PURPOSE,
            "192.52.193.0/24" to AddressClass.SPECIAL_PURPOSE, "192.88.99.0/24" to AddressClass.SPECIAL_PURPOSE,
            "192.175.48.0/24" to AddressClass.SPECIAL_PURPOSE, "198.18.0.0/15" to AddressClass.SPECIAL_PURPOSE,
            "198.51.100.0/24" to AddressClass.SPECIAL_PURPOSE, "203.0.113.0/24" to AddressClass.SPECIAL_PURPOSE,
            "240.0.0.0/4" to AddressClass.SPECIAL_PURPOSE,
        )
        assertEquals("oracle size", 19, oracle.size)
        for (i in oracle.indices) for (j in i + 1 until oracle.size) {
            val (a, b) = oracle[i].first to oracle[j].first
            assertTrue("oracle entries $a and $b must not overlap", last(a) < first(b) || last(b) < first(a))
        }
        fun expected(v: Long): AddressClass {
            var out = AddressClass.PUBLIC
            for ((c, k) in oracle) if (v in first(c)..last(c)) out = k
            return out
        }
        val probes = LinkedHashSet<Long>()
        for ((c, _) in oracle) {
            val lo = first(c); val hi = last(c)
            for (v in listOf(lo - 1, lo, lo + 1, lo + (hi - lo) / 2, hi - 1, hi, hi + 1)) if (v in 0L..0xFFFFFFFFL) probes += v
        }
        for (r in registry) for (b in r.blocks) for (v in listOf(first(b) - 1, first(b), last(b), last(b) + 1)) {
            if (v in 0L..0xFFFFFFFFL) probes += v
        }
        for (a in 0..255) { probes += a.toLong() shl 24; probes += (a.toLong() shl 24) or 0xFFFFFFL }
        for (hi16 in 0 until 65536) { probes += hi16.toLong() shl 16; probes += (hi16.toLong() shl 16) or 0xFFFFL }
        val rnd = Random(0x4A4152564953L)
        repeat(20_000) { probes += rnd.nextInt().toLong() and 0xFFFFFFFFL }
        assertTrue("probe count ${probes.size}", probes.size > 100_000)
        for (v in probes) {
            val want = expected(v)
            val got = classOfValue(v)
            assertEquals(str(v), want, got)
            if (want != AddressClass.PUBLIC) assertFalse("${str(v)} matches a deny entry", got == AddressClass.PUBLIC)
        }
    }

    @Test
    fun n36_multicast_is_denied_and_is_not_an_iana_special_purpose_record() {
        assertEquals(AddressClass.MULTICAST, cls("224.0.0.0"))
        assertEquals(AddressClass.MULTICAST, cls("239.255.255.255"))
        assertEquals(AddressClass.PUBLIC, cls("223.255.255.255"))
        val m = "224.0.0.0/4"
        for (row in registry) for (b in row.blocks) {
            assertTrue("registry block $b must not overlap $m", last(b) < first(m) || first(b) > last(m))
        }
    }
}
