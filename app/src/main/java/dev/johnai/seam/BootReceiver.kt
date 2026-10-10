package dev.johnai.seam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects to the computer after the phone restarts or Seam is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            PhoneFinder.onProcessStart(context)
            LinkHub.startService(context)
        }
    }
}
