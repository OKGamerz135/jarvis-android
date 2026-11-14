package dev.jarvis.assistant.memory

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Android's own SQLite, behind the small [Sql] interface. */
class AndroidSql(private val db: SQLiteDatabase) : Sql {
    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, arrayOf(*args))
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> {
        val strings = args.map { it?.toString().orEmpty() }.toTypedArray()
        db.rawQuery(sql, strings).use { c ->
            val row = CursorRow(c)
            val out = ArrayList<T>()
            while (c.moveToNext()) out += map(row)
            return out
        }
    }

    override fun <T> inTransaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    override var userVersion: Int
        get() = db.version
        set(value) { db.version = value }

    private class CursorRow(private val c: Cursor) : Row {
        override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
        override fun long(i: Int): Long = c.getLong(i)
        override fun double(i: Int): Double = c.getDouble(i)
    }
}

/** One memory service for the whole app, backed by a private database file inside the app's sandbox. */
object Memories {
    @Volatile private var instance: MemoryService? = null

    fun get(context: Context): MemoryService =
        instance ?: synchronized(this) {
            instance ?: MemoryService(storageProvider = { open(context.applicationContext) }).also { instance = it }
        }

    private fun open(context: Context): MemoryStorage {
        val file = context.getDatabasePath("jarvis_memory.db")
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        return try {
            SqlMemoryStorage(AndroidSql(db))
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }
}
