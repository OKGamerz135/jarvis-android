package dev.jarvis.assistant.ai

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

// Pure Kotlin on purpose (no android.* imports) so it can be unit-tested on the build server.

/**
 * Where an OpenAI-compatible provider is reached. Every request URL is built from this and nothing else:
 * `origin + basePath + fixed relative path`. The adapter never takes a URL from a request or a reply.
 *
 * This is a backstop, not the endpoint validation of a custom provider (that, and the paid-endpoint consent, are
 * separate and come later). It refuses anything that could send a request, and the key, somewhere other than the
 * address that was meant: anything but https, a user name or password, a query, a fragment, dot segments, empty
 * segments, spaces, backslashes or control characters.
 *
 * The path prefix is kept: https://host/v1 and https://host/api/v1/ both work, as `/v1` and `/api/v1`.
 */
class EndpointProfile private constructor(
    /** The provider's name as the diagnostics, cooldown and usage records know it. */
    val label: String,
    /** `https://host[:port]`, lower case host, default port dropped. */
    val origin: String,
    /** `""` or `/a/b` (no trailing slash). */
    val basePath: String,
    val chatPath: String,
    /** Null when the endpoint has no model list to ask for. */
    val modelsPath: String?,
) {
    /** The host and path a diagnostics line may show (never a query, never a key). */
    val displayBase: String get() = origin.removePrefix("https://").removePrefix("http://") + basePath

    fun chatUrl(): String = origin + basePath + chatPath
    fun modelsUrl(): String? = modelsPath?.let { origin + basePath + it }

    companion object {
        const val DEFAULT_CHAT_PATH = "/chat/completions"
        const val DEFAULT_MODELS_PATH = "/models"

        /** Returns null for an address that may not be used. Never throws. */
        fun parse(
            label: String,
            baseUrl: String,
            chatPath: String = DEFAULT_CHAT_PATH,
            modelsPath: String? = DEFAULT_MODELS_PATH,
        ): EndpointProfile? = build(label, baseUrl, chatPath, modelsPath, loopbackTestsOnly = false)

        /**
         * FOR UNIT TESTS ONLY: allows plain http, but only to this machine (127.0.0.1 or localhost), so a test can talk to a
         * local fake server. It can never point anywhere else. Production code uses [parse].
         */
        internal fun loopbackForTests(label: String, baseUrl: String): EndpointProfile? =
            build(label, baseUrl, DEFAULT_CHAT_PATH, DEFAULT_MODELS_PATH, loopbackTestsOnly = true)

        private fun fixedPath(p: String): Boolean = p.startsWith("/") && !p.endsWith("/") && p.length > 1 && safeSegments(p.substring(1))

        private fun safeSegments(path: String): Boolean {
            if (path.any { it.isISOControl() || it.isWhitespace() || it == '\\' || it == '?' || it == '#' || it == '%' }) return false
            return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        }

        private fun build(label: String, baseUrl: String, chat: String, models: String?, loopbackTestsOnly: Boolean): EndpointProfile? {
            if (label.isBlank() || !fixedPath(chat) || (models != null && !fixedPath(models))) return null
            if (baseUrl != baseUrl.trim() || baseUrl.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) return null
            val uri = try { URI(baseUrl) } catch (e: URISyntaxException) { return null }
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
            if (loopbackTestsOnly) {
                if (scheme != "http" && scheme != "https") return null
                if (host != "127.0.0.1" && host != "localhost") return null
            } else if (scheme != "https") return null
            val port = uri.port
            if (port != -1 && port !in 1..65535) return null
            // Only the origin and path are kept; nothing else of the original text survives.
            val rawPath = uri.rawPath.orEmpty()
            val basePath = rawPath.trimEnd('/')
            if (basePath.isNotEmpty() && (!basePath.startsWith("/") || !safeSegments(basePath.substring(1)))) return null
            if (rawPath.contains("//")) return null
            val defaultPort = if (scheme == "https") 443 else 80
            val origin = "$scheme://$host" + (if (port != -1 && port != defaultPort) ":$port" else "")
            return EndpointProfile(label, origin, basePath, chat, models)
        }
    }
}
