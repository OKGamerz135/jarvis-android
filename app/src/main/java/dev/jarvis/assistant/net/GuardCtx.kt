package dev.jarvis.assistant.net

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-call guard state (H1 design, section 1): a poison flag, the resources to close on a
 * violation, one cancel hook, and two counters.
 *
 * The first violation wins. Once it is recorded the context is poisoned: every later [violate]
 * throws that same first violation, [check] throws it, and a resource registered afterwards is
 * closed at once. A violation closes every registered resource and runs the cancel hook once.
 *
 * Pure logic: no OkHttp type and no android.net import. The cancel hook is a plain function; the
 * transport supplies the code that cancels its call.
 *
 * The counters only count. Acting on them (refusing a second DNS lookup, reporting a second
 * network pass) belongs to the transport classes that read them.
 */
internal class GuardCtx(private val cancelHook: () -> Unit = {}) {

    private val first = AtomicReference<GuardViolation?>(null)
    private val lock = Any()
    private val resources = ArrayList<Closeable>() // guarded by lock
    private val hookRan = AtomicBoolean(false)
    private val lookupCount = AtomicInteger()
    private val networkPassCount = AtomicInteger()

    /** The first recorded violation, or null if the context is not poisoned. */
    val violation: GuardViolation? get() = first.get()

    val lookups: Int get() = lookupCount.get()
    val networkPasses: Int get() = networkPassCount.get()

    fun incrementLookups(): Int = lookupCount.incrementAndGet()
    fun incrementNetworkPasses(): Int = networkPassCount.incrementAndGet()

    /** Throws the stored violation if the context is poisoned. */
    fun check() {
        first.get()?.let { throw it }
    }

    /**
     * Registers a resource to be closed on a violation. If the context is already poisoned, the
     * resource is closed at once and the stored violation is thrown.
     */
    fun register(resource: Closeable) {
        val poisoned: GuardViolation?
        synchronized(lock) {
            poisoned = first.get()
            if (poisoned == null) resources.add(resource)
        }
        if (poisoned != null) {
            closeQuietly(resource)
            throw poisoned
        }
    }

    /**
     * Records a violation (the first one wins), closes every registered resource, runs the cancel
     * hook once, and throws the stored first violation. A resource or hook that fails does not
     * stop the others and does not replace the violation.
     */
    fun violate(reason: GuardReason, category: AddressClass? = null): Nothing {
        first.compareAndSet(null, GuardViolation(reason, category))
        val stored = first.get()!!
        val toClose: List<Closeable>
        synchronized(lock) {
            toClose = ArrayList(resources)
            resources.clear()
        }
        for (r in toClose) closeQuietly(r)
        if (hookRan.compareAndSet(false, true)) {
            try {
                cancelHook()
            } catch (_: Exception) {
                // The violation is what the caller needs to see.
            }
        }
        throw stored
    }

    private fun closeQuietly(c: Closeable) {
        try {
            c.close()
        } catch (_: Exception) {
            // Closing is best effort; the violation is what the caller needs to see.
        }
    }
}
