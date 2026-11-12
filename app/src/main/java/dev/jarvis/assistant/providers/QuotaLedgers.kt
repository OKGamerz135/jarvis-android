package dev.jarvis.assistant.providers

import android.content.Context
import java.io.File

/** One shared ledger for the whole app, stored privately on the phone. */
object QuotaLedgers {
    @Volatile private var instance: QuotaLedger? = null

    fun get(context: Context): QuotaLedger =
        instance ?: synchronized(this) {
            instance ?: QuotaLedger(FileQuotaStore(File(context.applicationContext.filesDir, "quota_ledger.json"))).also { instance = it }
        }
}
