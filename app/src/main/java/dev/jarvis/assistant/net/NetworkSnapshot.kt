package dev.jarvis.assistant.net

/**
 * An opaque handle for "the network the device uses by default". Only equality is available:
 * two tokens are equal when the wrapped handles are equal.
 *
 * Assumption A-N2, not validated: that the platform's network handle compares equal for the same
 * network and unequal for a different one, including after a disconnect and reconnect. The
 * Android reader that wraps real handles belongs to a later stage and is where this is checked.
 *
 * The handle is never printed.
 */
internal class NetworkToken(private val handle: Any) {
    override fun equals(other: Any?): Boolean = other is NetworkToken && other.handle == handle
    override fun hashCode(): Int = handle.hashCode()
    override fun toString(): String = "NetworkToken"
}

/**
 * What the source reports about one network's link, limited to the fields the guard compares.
 * Link addresses, routes, the validated flag, captive-portal state and signal strength are not
 * represented here on purpose.
 */
internal class LinkState(
    val interfaceName: String?,
    val dnsServers: List<String>,
    val privateDnsActive: Boolean,
    val privateDnsServerName: String?,
)

/**
 * Two synchronous reads, in this order: the default network, then that network's link state.
 * They are two calls so that a state that changes between them can be modelled.
 *
 * [linkState] returns null when the platform has no link state for the handle.
 * Either call may throw; the snapshot treats that as an unavailable state.
 */
internal interface NetworkStateSource {
    fun activeNetwork(): NetworkToken?
    fun linkState(network: NetworkToken): LinkState?
}

/**
 * The compared state: exactly five fields. The DNS list is sorted on construction, so a reorder
 * is not a difference and a different set is. The printed form carries no field values.
 */
internal class NetworkFingerprint(
    private val network: NetworkToken,
    private val interfaceName: String?,
    private val dnsServers: List<String>,
    private val privateDnsActive: Boolean,
    private val privateDnsServerName: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is NetworkFingerprint &&
            other.network == network &&
            other.interfaceName == interfaceName &&
            other.dnsServers == dnsServers &&
            other.privateDnsActive == privateDnsActive &&
            other.privateDnsServerName == privateDnsServerName

    override fun hashCode(): Int {
        var h = network.hashCode()
        h = 31 * h + (interfaceName?.hashCode() ?: 0)
        h = 31 * h + dnsServers.hashCode()
        h = 31 * h + privateDnsActive.hashCode()
        h = 31 * h + (privateDnsServerName?.hashCode() ?: 0)
        return h
    }

    override fun toString(): String = "NetworkFingerprint"
}

internal fun fingerprintOf(network: NetworkToken, link: LinkState): NetworkFingerprint =
    NetworkFingerprint(
        network = network,
        interfaceName = link.interfaceName,
        dnsServers = link.dnsServers.sorted(),
        privateDnsActive = link.privateDnsActive,
        privateDnsServerName = link.privateDnsServerName,
    )

/** The result of the first read (checkpoint C0). */
internal sealed interface Baseline {
    /** No active network. Not a violation: the caller reports the device as offline and sends nothing. */
    object NoNetwork : Baseline

    /** The state the later checkpoints compare against. */
    class Captured(internal val fingerprint: NetworkFingerprint) : Baseline
}

/**
 * The checkpoint logic of H1 design, section 5, as pure code.
 *
 * [capture] takes the baseline (C0). C1 ([beforeSocketCreation]), C2 ([beforeConnect]) and C3
 * ([afterConnect]) re-read the state and compare it with the baseline; C4
 * ([beforeFirstRequestByte]) does the same immediately before the first request byte is written.
 * A difference, a missing network or link state, or a failing source records a violation through
 * the context, which closes its resources and runs its cancel hook. The guarded action does not
 * run after a violation.
 *
 * By design a checkpoint compares two readings taken at two moments. A network that changes and
 * changes back between two checkpoints (A to B to A) is not reported. After C4 no further reads
 * are made: a later change is an ordinary I/O failure.
 *
 * Not thread-safe. One instance belongs to one call.
 */
internal class NetworkSnapshot(
    private val ctx: GuardCtx,
    private val source: NetworkStateSource,
) {
    private var baseline: NetworkFingerprint? = null
    private var captured = false
    private var finished = false

    /**
     * C0. A single call, before anything is sent. No active network is not a violation: it returns
     * [Baseline.NoNetwork] and the caller sends nothing. A source that fails, or a network that has
     * no link state, is a violation.
     */
    fun capture(): Baseline {
        check(!captured) { "capture() was already called" }
        captured = true
        val net = activeNetworkOrViolate() ?: return Baseline.NoNetwork
        val current = fingerprintOf(net, linkStateOrViolate(net))
        baseline = current
        return Baseline.Captured(current)
    }

    /** C1: before a socket is created or bound. */
    fun <T> beforeSocketCreation(action: () -> T): T {
        verify()
        return action()
    }

    /** C2: after the address checks, before the TCP connect. */
    fun <T> beforeConnect(action: () -> T): T {
        verify()
        return action()
    }

    /** C3: after the TCP connect. */
    fun afterConnect() {
        verify()
    }

    /** C4: after TLS, immediately before the first request byte. Later checkpoints read nothing. */
    fun <T> beforeFirstRequestByte(send: () -> T): T {
        verify()
        finished = true
        return send()
    }

    private fun verify() {
        check(captured) { "capture() must come first" }
        if (finished) return
        val net = activeNetworkOrViolate() ?: ctx.violate(GuardReason.NETWORK_CHANGED)
        val current = fingerprintOf(net, linkStateOrViolate(net))
        if (current != baseline) ctx.violate(GuardReason.NETWORK_CHANGED)
    }

    private fun activeNetworkOrViolate(): NetworkToken? {
        val result: NetworkToken?
        try {
            result = source.activeNetwork()
        } catch (e: Exception) {
            ctx.violate(GuardReason.NETWORK_STATE_UNAVAILABLE)
        }
        return result
    }

    private fun linkStateOrViolate(net: NetworkToken): LinkState {
        val result: LinkState?
        try {
            result = source.linkState(net)
        } catch (e: Exception) {
            ctx.violate(GuardReason.NETWORK_STATE_UNAVAILABLE)
        }
        return result ?: ctx.violate(GuardReason.NETWORK_CHANGED)
    }
}
