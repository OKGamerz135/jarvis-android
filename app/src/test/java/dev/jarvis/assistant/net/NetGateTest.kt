package dev.jarvis.assistant.net

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier N. Static gates over the source text of the H1a-3a files, as listed in the H1 plan:
 * one AddressPolicy implementation, no OkHttp or android imports in the pure files, none of the
 * banned wording, and none of the identifiers that would name a test bypass.
 *
 * These read source files. They fail loudly if the files cannot be found, so a bad working
 * directory can never turn them into passes. They are textual: they catch the patterns described
 * in each test and nothing else. The scope is the five H1a-3a files only. A later stage that adds
 * files to net/ extends this list on purpose, with its own approval.
 */
class NetGateTest {

    private val files = listOf("GuardReason.kt", "HostRule.kt", "AddressPolicy.kt", "GuardCtx.kt", "NetworkSnapshot.kt")

    private fun mainRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull { File(it, "java/dev/jarvis/assistant/net").isDirectory }
            ?: throw AssertionError("cannot find src/main/java/dev/jarvis/assistant/net from ${File(".").absoluteFile}")
    }

    private fun netDir() = File(mainRoot(), "java/dev/jarvis/assistant/net")

    private fun text(name: String): String {
        val f = File(netDir(), name)
        assertTrue("${f.path} must exist", f.isFile)
        return f.readText()
    }

    private fun stripComments(s: String): String =
        s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\\n]*"), " ")

    // Class header with a supertype list naming AddressPolicy. The optional (...) swallows a flat
    // constructor parameter list so a parameter of type AddressPolicy does not count.
    private val implementer = Regex(
        "\\b(?:object|class)\\s+\\w+(?:\\s*\\([^)]*\\))?\\s*:\\s*[^{=]*?\\bAddressPolicy\\b|\\bobject\\s*:\\s*[^{=]*?\\bAddressPolicy\\b",
    )

    @Test
    fun n11_production_code_has_exactly_one_address_policy_implementation() {
        // The detector must see what it is meant to see.
        for (positive in listOf(
            "internal object X : AddressPolicy {",
            "class Y(val a: Int) : Foo, AddressPolicy {",
            "val p = object : AddressPolicy {",
        )) assertTrue("detector must match: $positive", implementer.containsMatchIn(positive))
        for (negative in listOf(
            "class Z(private val policy: AddressPolicy) {",
            "fun f(p: AddressPolicy) {",
            "val q: AddressPolicy = X",
            "internal interface AddressPolicy {",
        )) assertFalse("detector must not match: $negative", implementer.containsMatchIn(negative))

        val sources = mainRoot().walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue("main sources found", sources.size > 5)
        val hits = sources.flatMap { f -> implementer.findAll(stripComments(f.readText())).map { f.name to it.value }.toList() }
        assertEquals("implementations: $hits", 1, hits.size)
        assertTrue(hits[0].second, hits[0].second.contains("ProductionAddressPolicy"))
    }

    @Test
    fun n31_the_pure_files_import_no_okhttp_and_no_android_type() {
        for (name in files) {
            val code = stripComments(text(name))
            val imports = code.lines().map { it.trim() }.filter { it.startsWith("import ") }
            for (i in imports) {
                assertFalse("$name: '$i'", i.startsWith("import okhttp3") || i.startsWith("import okio") || i.startsWith("import android."))
            }
            assertFalse("$name must not name okhttp3 in code", Regex("\\bokhttp3\\.").containsMatchIn(code))
            assertFalse("$name must not name an android package in code", Regex("\\bandroid\\.\\w").containsMatchIn(code))
        }
    }

    @Test
    fun n32_the_files_use_none_of_the_banned_wording() {
        val banned = listOf("guarantee", "ensures", "a network change is detected")
        for (name in files) {
            val t = text(name).lowercase()
            for (b in banned) assertFalse("$name must not contain '$b'", t.contains(b))
        }
    }

    @Test
    fun n33_the_files_name_no_identifier_that_suggests_a_test_bypass() {
        val token = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val loopbackLike = ArrayList<String>()
        for (name in files) {
            for (t in token.findAll(stripComments(text(name))).map { it.value }) {
                assertFalse("$name: identifier '$t'", t.contains("allowLoopback") || t.contains("TestGuardConfig"))
                assertFalse("$name: identifier '$t' (mixed or lower case)", t.contains("loopback") || t.contains("Loopback"))
                if (t.contains("loopback", ignoreCase = true)) loopbackLike += t
            }
        }
        // The one exemption: the class name for 127.0.0.0/8, the loopback category of the H1 design
        // (section 7). It labels a denied range and allows nothing.
        assertEquals(setOf("LOOPBACK"), loopbackLike.toSet())
    }

    @Test
    fun n34_the_net_directory_holds_exactly_the_five_files_of_this_stage() {
        for (name in files) assertTrue("$name must exist", File(netDir(), name).isFile)
        val present = netDir().listFiles()!!.map { it.name }.toSet()
        assertEquals(files.toSet(), present)
    }
}
