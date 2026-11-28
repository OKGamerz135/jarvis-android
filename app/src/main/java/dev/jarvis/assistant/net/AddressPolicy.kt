package dev.jarvis.assistant.net

import java.net.Inet4Address
import java.net.InetAddress

/**
 * What a destination address is, as far as the transport guard is concerned.
 *
 * PUBLIC means only that no deny-table entry matches. It is not a statement that the address is
 * safe or reachable. Every other value is a deny verdict.
 *
 * NOT_IPV4 is internal to the policy: the guard drops IPv6 results before it classifies them
 * (decision D1), so it never reaches a user-facing category. SPECIAL_PURPOSE is the catch-all for
 * the reserved, documentation, benchmarking, protocol-assignment and class E ranges.
 */
internal enum class AddressClass {
    PUBLIC,
    UNSPECIFIED,
    PRIVATE,
    SHARED,
    LOOPBACK,
    LINK_LOCAL,
    METADATA,
    MULTICAST,
    SPECIAL_PURPOSE,
    NOT_IPV4,
}

/**
 * Classifies a destination address. Only PUBLIC allows a connection. There is exactly one
 * implementation in production code, [ProductionAddressPolicy], and it is restrictive.
 */
internal interface AddressPolicy {
    fun classify(address: InetAddress): AddressClass
}

internal fun AddressPolicy.allows(address: InetAddress): Boolean =
    classify(address) == AddressClass.PUBLIC

/**
 * The production deny table.
 *
 * Policy, not registry fact. Every record of the IANA IPv4 Special-Purpose Address Registry
 * (last updated 2025-10-09, 25 records) is denied, whatever its "Globally Reachable" or
 * "Forwardable" flags say, because no provider endpoint lives in those ranges and a DNS answer
 * that points there is anomalous. Three entries are our own additions and are not registry
 * rows: 224.0.0.0/4 (multicast, from the IPv4 multicast registry) and 168.63.129.16/32 (a
 * cloud platform endpoint that looks like a public address).
 *
 * Explicit redundancy removals, not omissions: 0.0.0.0/32 sits inside 0.0.0.0/8; 192.0.0.0/29,
 * .8, .9, .10, .170 and .171 sit inside 192.0.0.0/24; 192.88.99.2/32 sits inside 192.88.99.0/24;
 * 255.255.255.255/32 sits inside 240.0.0.0/4. 169.254.169.254 sits inside 169.254.0.0/16 and
 * 100.100.100.200 inside 100.64.0.0/10, so they have no entry of their own.
 *
 * Precedence: the most specific matching entry names the class. The table is pairwise disjoint
 * and has no allow entries, so on this table the precedence rule cannot change any result and
 * is not independently validated by the tests. What is validated is the invariant: an address
 * inside any entry is never PUBLIC.
 *
 * An unmatched IPv4 address is PUBLIC. Anything that is not an Inet4Address is NOT_IPV4. The JDK
 * turns an IPv4-mapped IPv6 literal into an Inet4Address, so it is judged by the IPv4 rules.
 */
internal object ProductionAddressPolicy : AddressPolicy {
    private class Entry(val network: Int, val prefixLength: Int, val mask: Int, val addressClass: AddressClass)

    private fun entry(cidr: String, addressClass: AddressClass): Entry {
        val (ip, len) = cidr.split('/')
        val octets = ip.split('.').map { it.toInt() }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "bad table entry" }
        val prefix = len.toInt()
        require(prefix in 0..32) { "bad table entry" }
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val value = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        require(value and mask == value) { "bad table entry" }
        return Entry(value, prefix, mask, addressClass)
    }

    private val TABLE: List<Entry> = listOf(
        entry("0.0.0.0/8", AddressClass.UNSPECIFIED),
        entry("10.0.0.0/8", AddressClass.PRIVATE),
        entry("172.16.0.0/12", AddressClass.PRIVATE),
        entry("192.168.0.0/16", AddressClass.PRIVATE),
        entry("100.64.0.0/10", AddressClass.SHARED),
        entry("127.0.0.0/8", AddressClass.LOOPBACK),
        entry("169.254.0.0/16", AddressClass.LINK_LOCAL),
        entry("168.63.129.16/32", AddressClass.METADATA),
        entry("224.0.0.0/4", AddressClass.MULTICAST),
        entry("192.0.0.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("192.0.2.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("192.31.196.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("192.52.193.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("192.88.99.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("192.175.48.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("198.18.0.0/15", AddressClass.SPECIAL_PURPOSE),
        entry("198.51.100.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("203.0.113.0/24", AddressClass.SPECIAL_PURPOSE),
        entry("240.0.0.0/4", AddressClass.SPECIAL_PURPOSE),
    )

    override fun classify(address: InetAddress): AddressClass {
        if (address !is Inet4Address) return AddressClass.NOT_IPV4
        val b = address.address
        val v = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
        var best: Entry? = null
        for (e in TABLE) {
            if ((v and e.mask) == e.network && (best == null || e.prefixLength > best.prefixLength)) best = e
        }
        return best?.addressClass ?: AddressClass.PUBLIC
    }
}
