package dev.jarvis.assistant.memory

/** One result row. Columns are read by position. */
interface Row {
    fun string(i: Int): String?
    fun long(i: Int): Long
    fun double(i: Int): Double
}

/**
 * The tiny slice of SQL access the memory database needs. The app implements it with Android's SQLite; the unit
 * tests implement it with a real SQLite file through JDBC, so the exact same SQL and schema run in both.
 */
interface Sql {
    fun exec(sql: String, vararg args: Any?)
    fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T>
    fun <T> inTransaction(block: () -> T): T
    var userVersion: Int
}
