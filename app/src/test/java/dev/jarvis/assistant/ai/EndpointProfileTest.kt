package dev.jarvis.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** C3a: the address a generic provider is reached at. Anything that could send the key elsewhere is refused. */
class EndpointProfileTest {
    private fun ok(url: String) = EndpointProfile.parse("L", url)

    @Test fun anHttpsAddressWithAPathPrefixKeepsTheTail() {
        assertEquals("https://api.example.test/v1/chat/completions", ok("https://api.example.test/v1")!!.chatUrl())
        assertEquals("https://api.example.test/v1/models", ok("https://api.example.test/v1")!!.modelsUrl())
        assertEquals("https://api.example.test/api/v1/chat/completions", ok("https://api.example.test/api/v1/")!!.chatUrl())
        assertEquals("https://api.example.test/chat/completions", ok("https://api.example.test")!!.chatUrl())
        assertEquals("https://api.example.test/chat/completions", ok("https://api.example.test/")!!.chatUrl())
        assertEquals("https://api.example.test:8443/openai/chat/completions", ok("https://API.Example.Test:8443/openai")!!.chatUrl())
        assertEquals("https://api.example.test/chat/completions", ok("https://api.example.test:443")!!.chatUrl())
    }

    @Test fun onlyTheOriginAndPathSurviveAndTheDisplayedBaseHasNoQuery() {
        val p = ok("https://api.example.test/v1/")!!
        assertEquals("api.example.test/v1", p.displayBase)
        assertEquals("L", p.label)
    }

    @Test fun addressesThatCouldSendTheKeyElsewhereAreRefused() {
        for (bad in listOf(
            "http://api.example.test/v1", "ftp://api.example.test", "file:///etc/passwd", "//api.example.test/v1", "api.example.test/v1", "",
            "https://user@api.example.test", "https://user:pass@api.example.test/v1",
            "https://api.example.test/v1?x=1", "https://api.example.test/v1?", "https://api.example.test/v1#frag", "https://api.example.test/#",
            "https://api.example.test/../v1", "https://api.example.test/v1/..", "https://api.example.test/./v1", "https://api.example.test/v1//x",
            "https://api.example.test//", "https://api.example.test/v 1", "https://api.example.test/v1\\x", "https://api.example.test/%2e%2e/v1",
            "https://api.example.test/v1\n", " https://api.example.test/v1", "https://api.example.test/v1 ", "https://api.example.test/v1\u0000",
            "https://", "https://:443/v1", "https://api.example.test:0/v1", "https://api.example.test:70000/v1", "https://api.example.test:abc/v1",
        )) assertNull("accepted '$bad'", ok(bad))
    }

    @Test fun aBlankLabelOrAnUnsafeFixedPathIsRefused() {
        assertNull(EndpointProfile.parse(" ", "https://api.example.test"))
        for (bad in listOf("chat", "/", "/chat/", "/../chat", "/chat?x", "/ chat", "/chat//completions", "")) {
            assertNull("chat '$bad'", EndpointProfile.parse("L", "https://api.example.test", chatPath = bad))
            assertNull("models '$bad'", EndpointProfile.parse("L", "https://api.example.test", modelsPath = bad))
        }
        assertNotNull(EndpointProfile.parse("L", "https://api.example.test", modelsPath = null))
        assertNull(EndpointProfile.parse("L", "https://api.example.test", modelsPath = null)!!.modelsUrl())
    }

    @Test fun theLoopbackTestFactoryCanOnlyReachThisMachine() {
        assertNotNull(EndpointProfile.loopbackForTests("L", "http://127.0.0.1:8080/v1"))
        assertNotNull(EndpointProfile.loopbackForTests("L", "http://localhost:8080"))
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", EndpointProfile.loopbackForTests("L", "http://127.0.0.1:8080/v1/")!!.chatUrl())
        for (bad in listOf(
            "http://example.com", "http://10.0.0.1", "http://192.168.1.1", "http://127.0.0.1.evil.test", "http://evil.test/127.0.0.1",
            "http://user@127.0.0.1", "http://localhost.evil.test", "ftp://127.0.0.1", "http://127.0.0.1/v1?x=1",
        )) assertNull("accepted '$bad'", EndpointProfile.loopbackForTests("L", bad))
    }

    @Test fun theProductionParserNeverAcceptsPlainHttpEvenToThisMachine() {
        assertNull(EndpointProfile.parse("L", "http://127.0.0.1:8080"))
        assertNull(EndpointProfile.parse("L", "http://localhost"))
    }
}
