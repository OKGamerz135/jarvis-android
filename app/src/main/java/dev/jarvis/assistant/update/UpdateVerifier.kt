package dev.jarvis.assistant.update

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.security.MessageDigest

// Pure Kotlin / JVM only (no Android classes), so every rule here is unit-tested on the build server.
//
// What these checks are, and are not:
//  - The URL rules and the size rule stop obviously wrong downloads (wrong host, plain HTTP, truncated or padded file).
//  - The SHA-256 is an INTEGRITY check against the value the release publishes. It comes from the same GitHub release
//    that serves the file, so it does NOT prove who built the file. Whoever can replace the file can replace that value.
//  - Authenticity comes from Android itself: an update is only installed if it is signed by the same key as the
//    installed app. Nothing here claims more than that.

/** Raised for any rule violation. The message is written for the user. */
class UpdateException(message: String) : IOException(message)

/** Which addresses a download may use. [requireHttps] is false only in tests that talk to a local server. */
class UrlPolicy(val allowedHosts: Set<String>, val requireHttps: Boolean = true) {

    /** True when [url] is an absolute https URL to an allowed host, on the default port, without embedded credentials. */
    fun allows(url: String): Boolean {
        val uri = try { URI(url) } catch (e: URISyntaxException) { return false }
        if (!uri.isAbsolute || uri.rawUserInfo != null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (requireHttps) {
            if (scheme != "https") return false
            if (uri.port != -1 && uri.port != 443) return false
        } else if (scheme != "https" && scheme != "http") {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return host in allowedHosts
    }

    companion object {
        /** GitHub's release download hosts. A release asset URL is on github.com and redirects to one of the others. */
        val GITHUB = UrlPolicy(
            setOf(
                "github.com",
                "objects.githubusercontent.com",
                "release-assets.githubusercontent.com",
                "github-releases.githubusercontent.com",
            ),
        )
    }
}

object ReleaseParser {
    const val ASSET_NAME = "jarvis.apk"
    const val TAG_PREFIX = "build-"
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    private val SHA256_DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")

    /** Decides what the "latest release" JSON means for an app that is currently build [currentBuild]. */
    fun parse(json: JSONObject, currentBuild: Int, repo: String, policy: UrlPolicy = UrlPolicy.GITHUB): UpdateState {
        val tag = json.optString("tag_name")
        val build = tag.removePrefix(TAG_PREFIX).toIntOrNull()
            ?: return UpdateState.Error("Latest release has an unexpected tag: \"${tag.take(40)}\".")
        if (build <= currentBuild) return UpdateState.UpToDate

        val assets = json.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets!!.optJSONObject(i) ?: continue
            if (asset.optString("name") != ASSET_NAME) continue

            val url = asset.optString("browser_download_url")
            if (!policy.allows(url)) {
                return UpdateState.Error("Release build $build points to a download address I don't trust, so I won't install it.")
            }
            if (policy.requireHttps && URI(url).path?.startsWith("/$repo/releases/download/") != true) {
                return UpdateState.Error("Release build $build is not served from this project's own releases, so I won't install it.")
            }
            val size = asset.optLong("size", -1L)
            if (size <= 0L || size > MAX_APK_BYTES) {
                return UpdateState.Error("Release build $build reports an unusable file size, so I won't install it.")
            }
            val digest = SHA256_DIGEST.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase()
                ?: return UpdateState.Error("Release build $build has no SHA-256 checksum, so I can't verify the download and won't install it.")
            return UpdateState.Available(build, "0.1.$build", url, size, digest)
        }
        return UpdateState.Error("Release build $build has no $ASSET_NAME file attached.")
    }
}

/** Downloads one APK under the [UrlPolicy], checks size and SHA-256 while streaming, and never writes more than expected. */
class ApkDownloader(
    private val policy: UrlPolicy = UrlPolicy.GITHUB,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val maxRedirects: Int = 5,
) {
    /**
     * Copies the file into [out]. Throws [UpdateException] if any rule is broken. Bytes may already have been written
     * to [out] when that happens, so the caller must discard the output (abandon the install session) on any exception.
     */
    fun download(
        url: String,
        expectedSize: Long,
        expectedSha256: String,
        out: OutputStream,
        onProgress: (copied: Long) -> Unit = {},
    ) {
        if (expectedSize <= 0L || expectedSize > ReleaseParser.MAX_APK_BYTES) throw UpdateException("The expected file size is not usable.")
        if (!Regex("^[0-9a-fA-F]{64}$").matches(expectedSha256)) throw UpdateException("The expected checksum is not usable.")
        val conn = open(url)
        try {
            val declared = conn.contentLengthLong
            if (declared >= 0 && declared != expectedSize) {
                throw UpdateException("The download size ($declared bytes) does not match the release ($expectedSize bytes).")
            }
            conn.inputStream.use { input -> copyVerified(input, expectedSize, expectedSha256, out, onProgress) }
        } finally {
            conn.disconnect()
        }
    }

    /** Follows redirects by hand so that every hop is checked against the policy. */
    private fun open(startUrl: String): HttpURLConnection {
        var current = startUrl
        for (hop in 0..maxRedirects) {
            if (!policy.allows(current)) throw UpdateException("Refusing to download from an address that is not allowed.")
            val conn = connect(URL(current))
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "jarvis-android")
            val code = conn.responseCode
            if (code == 200) return conn
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw UpdateException("The download redirect had no destination.")
                current = try { URL(URL(current), location).toString() } catch (e: java.net.MalformedURLException) {
                    throw UpdateException("The download redirect was not a valid address.")
                }
                continue
            }
            conn.disconnect()
            throw UpdateException("Download failed with HTTP $code.")
        }
        throw UpdateException("The download was redirected too many times.")
    }

    private fun copyVerified(input: InputStream, size: Long, sha256: String, out: OutputStream, onProgress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            copied += n
            if (copied > size) throw UpdateException("The download is larger than the release says. It was stopped.")
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
            onProgress(copied)
        }
        if (copied != size) throw UpdateException("The download is incomplete ($copied of $size bytes).")
        val actual = digest.digest()
        val expected = hexToBytes(sha256)
        if (!MessageDigest.isEqual(actual, expected)) throw UpdateException("The downloaded file's SHA-256 does not match the release. It was not installed.")
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
