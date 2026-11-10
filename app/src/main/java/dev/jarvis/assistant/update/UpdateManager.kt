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

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(
        val build: Int,
        val versionName: String,
        val url: String,
        val sizeBytes: Long,
    ) : UpdateState

    /** progress is 0f..1f, or negative when the size is unknown. */
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class NeedsPermission(val update: Available) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Checks the latest GitHub release of the project repo and installs it through
 * Android's PackageInstaller. Only contacts github.com, and only when the user
 * taps a button. Android always shows its own install confirmation unless it
 * decides it can skip it.
 */
object UpdateManager {
    private const val ASSET_NAME = "jarvis.apk"
    private const val TAG_PREFIX = "build-"

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

    private fun parseRelease(json: JSONObject): UpdateState {
        val tag = json.optString("tag_name")
        val build = tag.removePrefix(TAG_PREFIX).toIntOrNull()
            ?: return UpdateState.Error("Latest release has an unexpected tag: \"$tag\".")
        if (build <= BuildConfig.VERSION_CODE) return UpdateState.UpToDate

        val assets = json.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets!!.getJSONObject(i)
            if (asset.optString("name") == ASSET_NAME) {
                return UpdateState.Available(
                    build = build,
                    versionName = "0.1.$build",
                    url = asset.getString("browser_download_url"),
                    sizeBytes = asset.optLong("size", -1L),
                )
            }
        }
        return UpdateState.Error("Release build $build has no $ASSET_NAME file attached.")
    }

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
            if (update.sizeBytes > 0) params.setSize(update.sizeBytes)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                val conn = URL(update.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "jarvis-android")
                try {
                    if (conn.responseCode != 200) {
                        throw IOException("Download failed with HTTP ${conn.responseCode}.")
                    }
                    val total = if (update.sizeBytes > 0) update.sizeBytes else conn.contentLengthLong
                    _state.value = UpdateState.Downloading(if (total > 0) 0f else -1f)

                    conn.inputStream.use { input ->
                        session.openWrite("jarvis.apk", 0, total).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            var lastPercent = -1
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                copied += n
                                if (total > 0) {
                                    val percent = (copied * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        _state.value = UpdateState.Downloading(copied.toFloat() / total)
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                } finally {
                    conn.disconnect()
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
