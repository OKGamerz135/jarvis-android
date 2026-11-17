package dev.jarvis.assistant.providers

import java.net.URI
import java.util.Locale

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * The consent rule for custom (user-supplied) endpoints. Unlike the built-in providers, JARVIS can't restrict a custom
 * endpoint to free models, so it may charge money. Therefore:
 *  - nothing is ever sent to a custom endpoint until the user has accepted the charges warning for THAT endpoint;
 *  - the acceptance is tied to the endpoint's address (scheme, host and port), so editing the address needs a new one;
 *  - the wording never claims or implies that such an endpoint is free.
 *
 * Phase A provides only this rule and its tests. The screens and storage that use it come with custom providers.
 */
object PaidEndpointPolicy {
    /** What the user accepted: one specific endpoint. */
    data class Consent(val endpoint: String)

    sealed interface Decision {
        /** The user has accepted the warning for exactly this endpoint. */
        data class Allowed(val endpoint: String) : Decision

        /** Ask first, showing [warning]. Nothing may be sent until the user accepts. */
        data class NeedsConsent(val endpoint: String, val warning: String) : Decision

        /** The address can't be used at all. */
        data class Rejected(val reason: String) : Decision
    }

    fun warning(endpoint: String): String =
        "This is a custom endpoint ($endpoint). JARVIS cannot tell whether it is free or paid, and it cannot limit what " +
            "it charges. Using it may cost you money, possibly for every message. Your messages and your API key will be " +
            "sent to $endpoint. Only continue if you accept that, and check the provider's pricing first."

    /** The address as it is compared and stored: https://host[:port], lower case, with the default port dropped. */
    fun normalize(url: String): String? {
        val uri = try { URI(url.trim()) } catch (e: Exception) { return null }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        if (uri.userInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it != -1 && it != 443 }
        return "https://" + host + (port?.let { ":$it" } ?: "")
    }

    fun evaluate(url: String, consent: Consent?): Decision {
        val endpoint = normalize(url)
            ?: return Decision.Rejected("A custom endpoint must be a plain https:// address with a host name and no user name or password in it.")
        return if (consent != null && consent.endpoint == endpoint) {
            Decision.Allowed(endpoint)
        } else {
            Decision.NeedsConsent(endpoint, warning(endpoint))
        }
    }
}
