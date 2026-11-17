package dev.jarvis.assistant.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Waits for the phone to report a usable network. Needs only the ACCESS_NETWORK_STATE permission. */
class AndroidNetworkWatcher(context: Context) : NetworkWatcher {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun awaitConnected() {
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                callback = cb
                cm.registerDefaultNetworkCallback(cb)
            }
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }
}
