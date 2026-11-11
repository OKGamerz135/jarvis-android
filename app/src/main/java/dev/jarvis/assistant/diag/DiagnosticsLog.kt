package dev.jarvis.assistant.diag

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * The on-phone diagnostics log: the last [MAX_ENTRIES] provider calls, kept in app-private storage
 * (not included in backups). It only ever holds [DiagnosticEntry] metadata.
 */
object DiagnosticsLog : DiagnosticsSink {
    const val MAX_ENTRIES = 100

    private val lock = Any()
    private var file: File? = null
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, "diagnostics.json")
            file = f
            val saved = try {
                if (f.exists()) f.readText() else ""
            } catch (e: IOException) {
                ""
            }
            _entries.value = DiagnosticsCodec.decode(saved)
        }
    }

    override fun record(entry: DiagnosticEntry) {
        synchronized(lock) {
            val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
            _entries.value = updated
            persist(updated)
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            persist(emptyList())
        }
    }

    private fun persist(list: List<DiagnosticEntry>) {
        try {
            file?.writeText(DiagnosticsCodec.encode(list))
        } catch (e: IOException) {
            // Diagnostics are best-effort; never let logging break the app.
        }
    }
}
