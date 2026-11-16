package dev.jarvis.assistant.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest

class UpdateVerifierTest {
    private val repo = "OKGamerz135/jarvis-android"
    private val good = "https://github.com/$repo/releases/download/build-41/jarvis.apk"
    private val digestHex = "a".repeat(64)

    // ------------------------------------------------------------------ URL policy

    @Test fun policyAcceptsGithubReleaseHosts() {
        val p = UrlPolicy.GITHUB
        assertTrue(p.allows(good))
        assertTrue(p.allows("https://objects.githubusercontent.com/x/y?z=1"))
        assertTrue(p.allows("https://release-assets.githubusercontent.com/x"))
        assertTrue(p.allows("https://GitHub.com/$repo/releases/download/build-41/jarvis.apk"))
        assertTrue(p.allows("https://github.com:443/a"))
    }

    @Test fun policyRejectsPlainHttpOtherSchemesAndOddPorts() {
        val p = UrlPolicy.GITHUB
        assertFalse(p.allows("http://github.com/a"))
        assertFalse(p.allows("ftp://github.com/a"))
        assertFalse(p.allows("file:///sdcard/x.apk"))
        assertFalse(p.allows("https://github.com:8443/a"))
        assertFalse(p.allows("//github.com/a"))
        assertFalse(p.allows("github.com/a"))
        assertFalse(p.allows(""))
    }

    @Test fun policyRejectsLookalikeAndCredentialHosts() {
        val p = UrlPolicy.GITHUB
        assertFalse(p.allows("https://github.com.evil.example/a"))
        assertFalse(p.allows("https://evilgithub.com/a"))
        assertFalse(p.allows("https://github.com@evil.example/a"))
        assertFalse(p.allows("https://user:pw@github.com/a"))
        assertFalse(p.allows("https://raw.githubusercontent.com/a"))
        assertFalse(p.allows("https://127.0.0.1/a"))
        assertFalse(p.allows("https://githubusercontent.com/a"))
    }

    // ------------------------------------------------------------------ release parsing

    private fun release(
        tag: String = "build-41",
        name: String = "jarvis.apk",
        url: String = good,
        size: Any? = 1000L,
        digest: Any? = "sha256:$digestHex",
    ): JSONObject {
        val asset = JSONObject().put("name", name).put("browser_download_url", url)
        if (size != null) asset.put("size", size)
        if (digest != null) asset.put("digest", digest)
        return JSONObject().put("tag_name", tag).put("assets", JSONArray().put(asset))
    }

    private fun parse(j: JSONObject, current: Int = 40) = ReleaseParser.parse(j, current, repo)

    @Test fun validReleaseIsAvailableWithLowercaseDigest() {
        val r = parse(release(digest = "sha256:" + "AB".repeat(32)))
        r as UpdateState.Available
        assertEquals(41, r.build)
        assertEquals(1000L, r.sizeBytes)
        assertEquals("ab".repeat(32), r.sha256)
    }

    @Test fun olderOrSameBuildIsUpToDate() {
        assertEquals(UpdateState.UpToDate, parse(release(tag = "build-40"), current = 40))
        assertEquals(UpdateState.UpToDate, parse(release(tag = "build-39"), current = 40))
    }

    @Test fun missingOrMalformedDigestIsRefused() {
        for (d in listOf(null, "", "sha256:", "sha256:abc", "md5:" + "a".repeat(32), "sha1:" + "a".repeat(40), "a".repeat(64), "sha256:" + "g".repeat(64), "sha256:" + "a".repeat(65))) {
            val r = parse(release(digest = d))
            assertTrue("digest [$d] should be refused but was $r", r is UpdateState.Error)
            assertTrue((r as UpdateState.Error).message.contains("checksum"))
        }
    }

    @Test fun badSizeIsRefused() {
        for (s in listOf<Any?>(null, 0L, -5L, 150L * 1024 * 1024 + 1)) {
            assertTrue("size [$s]", parse(release(size = s)) is UpdateState.Error)
        }
    }

    @Test fun untrustedOrForeignUrlIsRefused() {
        val bad = listOf(
            "http://github.com/$repo/releases/download/build-41/jarvis.apk",
            "https://evil.example/$repo/releases/download/build-41/jarvis.apk",
            "https://github.com/someone-else/jarvis-android/releases/download/build-41/jarvis.apk",
            "https://github.com/$repo/raw/main/jarvis.apk",
            "https://github.com/$repo/releases/download/build-41/jarvis.apk".replace("https://", "https://u:p@"),
        )
        for (u in bad) assertTrue("url [$u]", parse(release(url = u)) is UpdateState.Error)
    }

    @Test fun missingAssetOrBadTagIsAnErrorNotACrash() {
        assertTrue(parse(release(name = "other.apk")) is UpdateState.Error)
        assertTrue(parse(release(tag = "v1.2")) is UpdateState.Error)
        assertTrue(parse(JSONObject()) is UpdateState.Error)
        assertTrue(parse(JSONObject().put("tag_name", "build-41").put("assets", JSONArray().put("junk"))) is UpdateState.Error)
    }

    // ------------------------------------------------------------------ downloading, against a real local server

    private var server: HttpServer? = null
    @After fun stop() { server?.stop(0) }

    private val local = UrlPolicy(setOf("127.0.0.1"), requireHttps = false)

    private fun start(routes: Map<String, (HttpExchange) -> Unit>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, h) in routes) s.createContext(path) { ex -> try { h(ex) } finally { ex.close() } }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val payload = ByteArray(300_000) { (it * 31).toByte() }

    private fun serveBytes(ex: HttpExchange, body: ByteArray, declaredLength: Long = body.size.toLong()) {
        ex.sendResponseHeaders(200, declaredLength)
        ex.responseBody.write(body)
    }

    private fun download(url: String, size: Long = payload.size.toLong(), digest: String = sha(payload), out: ByteArrayOutputStream = ByteArrayOutputStream()): ByteArrayOutputStream {
        ApkDownloader(local).download(url, size, digest, out)
        return out
    }

    @Test fun correctFileIsCopiedExactly() {
        val base = start(mapOf("/apk" to { ex -> serveBytes(ex, payload) }))
        val out = download("$base/apk")
        assertTrue(payload.contentEquals(out.toByteArray()))
    }

    @Test fun progressReportsEveryChunkUpToTheFullSize() {
        val base = start(mapOf("/apk" to { ex -> serveBytes(ex, payload) }))
        var last = 0L
        ApkDownloader(local).download("$base/apk", payload.size.toLong(), sha(payload), ByteArrayOutputStream()) { last = it }
        assertEquals(payload.size.toLong(), last)
    }

    @Test fun oneFlippedByteIsRefusedByDigest() {
        val tampered = payload.clone().also { it[1234] = (it[1234] + 1).toByte() }
        val base = start(mapOf("/apk" to { ex -> serveBytes(ex, tampered) }))
        val e = assertThrows(UpdateException::class.java) { download("$base/apk") }
        assertTrue(e.message!!.contains("SHA-256"))
    }

    @Test fun wrongExpectedDigestIsRefused() {
        val base = start(mapOf("/apk" to { ex -> serveBytes(ex, payload) }))
        assertThrows(UpdateException::class.java) { download("$base/apk", digest = "0".repeat(64)) }
    }

    @Test fun bodyShorterThanReleaseSizeIsRefused() {
        val short = payload.copyOf(payload.size - 10)
        // Chunked response (no Content-Length), so only our own size check can catch it.
        val base = start(mapOf("/apk" to { ex -> serveBytes(ex, short, declaredLength = 0) }))
        val e = assertThrows(UpdateException::class.java) { download("$base/apk") }
        assertTrue(e.message!!.contains("incomplete"))
    }

    @Test fun bodyLongerThanReleaseSizeIsStopped() {
        val long = payload + ByteArray(5000)
        val base = start(mapOf("/apk" to { ex -> serveBytes(ex, long, declaredLength = 0) }))
        val out = ByteArrayOutputStream()
        val e = assertThrows(UpdateException::class.java) { download("$base/apk", out = out) }
        assertTrue(e.message!!.contains("larger"))
        assertTrue("never writes more than the expected size", out.size() <= payload.size)
    }

    @Test fun contentLengthThatDisagreesWithReleaseIsRefusedBeforeReading() {
        val bigger = payload + ByteArray(100)
        val base = start(mapOf("/apk" to { ex -> serveBytes(ex, bigger) }))
        val out = ByteArrayOutputStream()
        assertThrows(UpdateException::class.java) { download("$base/apk", out = out) }
        assertEquals(0, out.size())
    }

    @Test fun truncatedConnectionFailsAsAnIoError() {
        val base = start(mapOf("/apk" to { ex ->
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload, 0, 1000)
        }))
        assertThrows(IOException::class.java) { download("$base/apk") }
    }

    @Test fun httpErrorStatusIsRefused() {
        val base = start(mapOf("/apk" to { ex -> ex.sendResponseHeaders(404, -1) }))
        val e = assertThrows(UpdateException::class.java) { download("$base/apk") }
        assertTrue(e.message!!.contains("404"))
    }

    @Test fun redirectWithinAllowedHostIsFollowedAndStillVerified() {
        val base = start(mapOf(
            "/a" to { ex -> ex.responseHeaders.add("Location", "/b"); ex.sendResponseHeaders(302, -1) },
            "/b" to { ex -> serveBytes(ex, payload) },
        ))
        assertTrue(payload.contentEquals(download("$base/a").toByteArray()))
    }

    @Test fun redirectToAForeignHostIsRefused() {
        val base = start(mapOf("/a" to { ex ->
            ex.responseHeaders.add("Location", "http://localhost:${ex.localAddress.port}/b") // "localhost" is not on the list
            ex.sendResponseHeaders(302, -1)
        }, "/b" to { ex -> serveBytes(ex, payload) }))
        val e = assertThrows(UpdateException::class.java) { download("$base/a") }
        assertTrue(e.message!!.contains("not allowed"))
    }

    @Test fun redirectDowngradingToPlainHttpIsRefusedUnderTheRealPolicy() {
        // Real policy: even the first hop is refused because the test server is http and not an allowed host.
        val base = start(mapOf("/a" to { ex -> serveBytes(ex, payload) }))
        assertThrows(UpdateException::class.java) {
            ApkDownloader(UrlPolicy.GITHUB).download("$base/a", payload.size.toLong(), sha(payload), ByteArrayOutputStream())
        }
    }

    @Test fun redirectLoopIsStopped() {
        val base = start(mapOf("/a" to { ex -> ex.responseHeaders.add("Location", "/a"); ex.sendResponseHeaders(302, -1) }))
        val e = assertThrows(UpdateException::class.java) { download("$base/a") }
        assertTrue(e.message!!.contains("too many"))
    }

    @Test fun unusableExpectationsAreRefusedBeforeAnyRequest() {
        val dl = ApkDownloader(local)
        assertThrows(UpdateException::class.java) { dl.download("http://127.0.0.1:1/x", 0, sha(payload), ByteArrayOutputStream()) }
        assertThrows(UpdateException::class.java) { dl.download("http://127.0.0.1:1/x", 10, "xyz", ByteArrayOutputStream()) }
    }
}
