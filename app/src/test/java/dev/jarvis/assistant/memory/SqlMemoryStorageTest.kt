package dev.jarvis.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** Runs the real SQL against sqlite-jdbc, the same engine Android uses. */
class JdbcSql(private val c: Connection) : Sql {
    private class JRow(val rs: java.sql.ResultSet) : Row {
        override fun string(i: Int): String? = rs.getString(i + 1)
        override fun long(i: Int): Long = rs.getLong(i + 1)
        override fun double(i: Int): Double = rs.getDouble(i + 1)
    }

    private fun bind(st: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    }

    override fun exec(sql: String, vararg args: Any?) {
        c.prepareStatement(sql).use { bind(it, args); it.execute() }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> =
        c.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(JRow(rs))) } }
        }

    override fun <T> inTransaction(block: () -> T): T {
        c.autoCommit = false
        try { val r = block(); c.commit(); return r } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    override var userVersion: Int
        get() = query("PRAGMA user_version") { it.long(0).toInt() }.first()
        set(v) { exec("PRAGMA user_version = $v") }
}

class SqlMemoryStorageTest {
    private val file: File = Files.createTempFile("mem", ".db").toFile()
    private fun open() = SqlMemoryStorage(JdbcSql(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")))
    private fun mem(id: String, content: String = "Uses Kotlin", status: MemoryStatus = MemoryStatus.ACTIVE) =
        Memory(id, MemoryCategory.TECH, "my stack", content, MemorySource.AUTO, 0.75, status, 10, 20, "r", null)

    @Test fun savedMemoryPersistsAcrossReopen() {
        open().upsert(mem("a"))
        val back = open().get("a")!!
        assertEquals(mem("a"), back)
    }

    @Test fun upsertReplacesAndDeleteRemoves() {
        val s = open()
        s.upsert(mem("a")); s.upsert(mem("a", "Uses Rust"))
        assertEquals(1, s.all().size)
        assertEquals("Uses Rust", s.get("a")!!.content)
        s.delete("a")
        assertNull(s.get("a"))
    }

    @Test fun deleteAllEmptiesTheTable() {
        val s = open()
        s.upsert(mem("a")); s.upsert(mem("b", status = MemoryStatus.PENDING))
        s.deleteAll()
        assertTrue(s.all().isEmpty())
    }

    @Test fun nullableColumnsRoundTrip() {
        val s = open()
        s.upsert(mem("a").copy(key = null, reason = null))
        val m = s.get("a")!!
        assertNull(m.key); assertNull(m.reason); assertNull(m.replacesId)
    }

    @Test fun schemaVersionIsSetAndNewerDatabasesAreRefused() {
        open()
        val c = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        assertEquals(SqlMemoryStorage.VERSION, JdbcSql(c).userVersion)
        JdbcSql(c).userVersion = SqlMemoryStorage.VERSION + 1
        c.close()
        try { open(); fail("expected refusal") } catch (e: IllegalStateException) { }
    }

    @Test fun serviceWorksOnRealSql() = kotlinx.coroutines.runBlocking {
        val svc = MemoryService({ open() }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        svc.observe("My favourite colour is orange.")
        val again = MemoryService({ open() }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(again.contextFor(listOf("favourite colour")).contains("orange"))
    }
}
