package dev.jarvis.assistant.net

import java.io.IOException

/**
 * Why the transport guard stopped a request (H1 design, section 7).
 *
 * The set is fixed at ten values. Adding or removing a value is a design change, not a
 * refactor, and is pinned by GuardReasonTest.
 *
 * Only SECOND_NETWORK_PASS can occur after the first request byte was written. Every other
 * reason is raised before it.
 */
internal enum class GuardReason {
    DNS_PROHIBITED_ADDRESS,
    DNS_NO_IPV4,
    BIND_FAILED,
    CONNECT_PROHIBITED,
    PEER_MISMATCH,
    DNS_SECOND_LOOKUP,
    PROTOCOL_NOT_HTTP11,
    SECOND_NETWORK_PASS,
    NETWORK_CHANGED,
    NETWORK_STATE_UNAVAILABLE,
}

/**
 * A guard violation as it travels inside net/. It is an IOException because OkHttp requires
 * that. The message is the reason code only. It never holds an address, a DNS server, a
 * network name or a header value.
 *
 * [category] is set only for DNS_PROHIBITED_ADDRESS. It names the kind of range, never the
 * address, and is not part of the message.
 */
internal class GuardViolation(
    val reason: GuardReason,
    val category: AddressClass? = null,
) : IOException(reason.name)

/**
 * The same violation at the transport's public edge. It extends RuntimeException and is not
 * an IOException, so a provider that does not handle it fails loudly instead of being
 * treated as an ordinary network failure. The message is the reason code only.
 */
internal class GuardViolationException(
    val reason: GuardReason,
    val category: AddressClass? = null,
) : RuntimeException(reason.name)
