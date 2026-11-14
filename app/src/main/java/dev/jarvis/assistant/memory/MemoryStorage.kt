package dev.jarvis.assistant.memory

/** Where memories live. Independent of chat history and of every AI provider. */
interface MemoryStorage {
    fun all(): List<Memory>
    fun get(id: String): Memory?
    fun upsert(m: Memory)
    fun delete(id: String)
    fun deleteAll()
}

/** Used in tests and as a safe stand-in. */
class InMemoryMemoryStorage : MemoryStorage {
    private val map = LinkedHashMap<String, Memory>()
    override fun all() = map.values.toList()
    override fun get(id: String) = map[id]
    override fun upsert(m: Memory) { map[m.id] = m }
    override fun delete(id: String) { map.remove(id) }
    override fun deleteAll() = map.clear()
}

/**
 * SQLite storage. Schema versioning uses `PRAGMA user_version`. A database from a NEWER app version is refused
 * (never silently downgraded or modified); an older one is migrated step by step in [migrate].
 */
class SqlMemoryStorage(private val sql: Sql) : MemoryStorage {
    init {
        ensureSchema()
    }

    private fun ensureSchema() {
        val v = sql.userVersion
        if (v > VERSION) throw IllegalStateException("The memory database is from a newer version of the app")
        if (v < VERSION) sql.inTransaction { migrate(v) }
    }

    private fun migrate(from: Int) {
        if (from < 1) {
            sql.exec(
                "CREATE TABLE IF NOT EXISTS memories(" +
                    "id TEXT PRIMARY KEY, category TEXT NOT NULL, mkey TEXT, content TEXT NOT NULL, source TEXT NOT NULL, " +
                    "confidence REAL NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                    "reason TEXT, replaces_id TEXT)",
            )
            sql.exec("CREATE INDEX IF NOT EXISTS idx_memories_key ON memories(mkey)")
            sql.exec("CREATE INDEX IF NOT EXISTS idx_memories_status ON memories(status)")
        }
        // Future versions: add `if (from < 2) { ... }` blocks here, each only adding to the schema.
        sql.userVersion = VERSION
    }

    private val columns = "id, category, mkey, content, source, confidence, status, created_at, updated_at, reason, replaces_id"

    private fun read(r: Row): Memory = Memory(
        id = r.string(0).orEmpty(),
        category = MemoryCategory.entries.firstOrNull { it.name == r.string(1) } ?: MemoryCategory.OTHER,
        key = r.string(2),
        content = r.string(3).orEmpty(),
        source = MemorySource.entries.firstOrNull { it.name == r.string(4) } ?: MemorySource.AUTO,
        confidence = r.double(5),
        status = MemoryStatus.entries.firstOrNull { it.name == r.string(6) } ?: MemoryStatus.ACTIVE,
        createdAt = r.long(7),
        updatedAt = r.long(8),
        reason = r.string(9),
        replacesId = r.string(10),
    )

    override fun all(): List<Memory> = sql.query("SELECT $columns FROM memories ORDER BY updated_at DESC, id", map = ::read)

    override fun get(id: String): Memory? = sql.query("SELECT $columns FROM memories WHERE id = ?", id, map = ::read).firstOrNull()

    override fun upsert(m: Memory) {
        sql.exec(
            "INSERT OR REPLACE INTO memories($columns) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            m.id, m.category.name, m.key, m.content, m.source.name, m.confidence, m.status.name,
            m.createdAt, m.updatedAt, m.reason, m.replacesId,
        )
    }

    override fun delete(id: String) = sql.exec("DELETE FROM memories WHERE id = ?", id)

    override fun deleteAll() = sql.exec("DELETE FROM memories")

    companion object {
        const val VERSION = 1
    }
}
