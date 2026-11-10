package dev.jarvis.assistant.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the installer's result: shows Android's confirmation screen or reports a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    UpdateManager.publish(UpdateState.Error("Android didn't give me an install screen."))
                }
            }

            // Usually never seen: a successful update replaces and restarts the app.
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.publish(UpdateState.Idle)

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                UpdateManager.publish(UpdateState.Error("Update cancelled."))

            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                UpdateManager.publish(UpdateState.Error("Install failed (code $status): $reason"))
            }
        }
    }
}
