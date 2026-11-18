package dev.jarvis.assistant.ai

import dev.jarvis.assistant.diag.Redactor
import java.net.URI
import java.net.URISyntaxException

/**
 * Provider requests carry the user's API key, so JARVIS never follows a redirect on them.
 *
 * Following one would send the request (and, depending on the header, the key) to an address nobody chose, and
 * would let that address answer in the provider's place. Every provider connection therefore switches automatic
 * redirects off, and any 3xx answer is turned into an error here. Nothing is sent a second time.
 *
 * The message names only the HOST a redirect pointed at. The path and query are left out because they can carry
 * anything, and so is the userinfo part of an address.
 */
object RedirectRefusal {
    fun isRedirect(status: Int): Boolean = status in 300..399

    private val hostChars = Regex("[a-z0-9.:\\[\\]-]{1,100}")

    /** The host a Location header points at, or null if there is none or it is not a plain host name or address. */
    fun targetHost(location: String?, secrets: List<String> = emptyList()): String? {
        val uri = parse(location) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!hostChars.matches(host)) return null
        // A host that changes when secrets are scrubbed out of it was carrying a secret: say nothing about it.
        if (Redactor.clean(host, secrets, 100) != host) return null
        return host
    }

    /** True for a Location with no host at all (for example "/other"): the same server, another address. */
    private fun isRelative(location: String?): Boolean {
        val uri = parse(location) ?: return false
        return uri.scheme == null && uri.rawAuthority == null
    }

    private fun parse(location: String?): URI? {
        val text = location?.trim()
        if (text.isNullOrEmpty()) return null
        return try { URI(text) } catch (e: URISyntaxException) { null }
    }

    fun message(status: Int, location: String?, secrets: List<String> = emptyList()): String {
        val pointing = when {
            targetHost(location, secrets) != null -> " pointing to ${targetHost(location, secrets)}"
            isRelative(location) -> " pointing to another address on the same server"
            else -> ""
        }
        return "The server answered with a redirect-class response (HTTP $status)$pointing. " +
            "JARVIS does not follow redirects on requests that carry your API key, so nothing was sent anywhere else."
    }

    fun error(status: Int, location: String?, secrets: List<String> = emptyList()): LlmError.Other =
        LlmError.Other(message(status, location, secrets))
}
