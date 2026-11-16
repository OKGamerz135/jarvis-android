package dev.jarvis.assistant.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.jarvis.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the latest GitHub release of the project repo and installs it through
 * Android's PackageInstaller. Only contacts github.com, and only when the user
 * taps a button. Android always shows its own install confirmation unless it
 * decides it can skip it.
 */
object UpdateManager {
    private val downloader = ApkDownloader()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    internal fun publish(newState: UpdateState) {
        _state.value = newState
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Checking) return@withContext
        _state.value = UpdateState.Checking
        try {
            val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "jarvis-android")
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val json = JSONObject(conn.inputStream.bufferedReader().readText())
                        _state.value = parseRelease(json)
                    }
                    404 -> _state.value = UpdateState.Error("No release has been published yet.")
                    403, 429 -> _state.value =
                        UpdateState.Error("GitHub is rate-limiting this phone. Try again in a while.")
                    else -> _state.value = UpdateState.Error("GitHub answered with HTTP $code.")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            _state.value = UpdateState.Error("Can't reach GitHub. Check your internet connection.")
        } catch (e: JSONException) {
            _state.value = UpdateState.Error("GitHub sent a reply I couldn't read.")
        }
    }

    private fun parseRelease(json: JSONObject): UpdateState =
        ReleaseParser.parse(json, BuildConfig.VERSION_CODE, BuildConfig.UPDATE_REPO)

    /** Streams the APK straight into an installer session, then asks Android to install it. */
    suspend fun install(context: Context, update: UpdateState.Available) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update)
            return@withContext
        }

        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setSize(update.sizeBytes)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                _state.value = UpdateState.Downloading(0f)
                session.openWrite("jarvis.apk", 0, update.sizeBytes).use { out ->
                    var lastPercent = -1
                    downloader.download(update.url, update.sizeBytes, update.sha256, out) { copied ->
                        val percent = (copied * 100 / update.sizeBytes).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = UpdateState.Downloading(copied.toFloat() / update.sizeBytes)
                        }
                    }
                    session.fsync(out)
                }

                _state.value = UpdateState.Installing
                val callback = PendingIntent.getBroadcast(
                    app,
                    sessionId,
                    Intent(app, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: IOException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            _state.value = UpdateState.Error(e.message ?: "Download failed. Check your connection.")
        } catch (e: SecurityException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            _state.value = UpdateState.Error("Android refused the install: ${e.message}")
        }
    }
}
