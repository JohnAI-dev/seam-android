package dev.johnai.seam

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** Remembers which computer this phone is paired with, and the phone's own id. */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("seam_pairing", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    /** Stable id for this phone, created on first use. */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    /** Name shown on the computer, e.g. "Galaxy S24 Ultra". */
    val deviceName: String
        get() = Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    var pairing: PairingInfo?
        get() {
            val hosts = prefs.getString("hosts", null) ?: return null
            return PairingInfo(
                hosts = hosts.split(","),
                port = prefs.getInt("port", 0),
                fingerprint = prefs.getString("fp", "")!!,
                keyB64 = prefs.getString("key", "")!!,
                name = prefs.getString("name", "")!!,
            )
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) {
                e.remove("hosts").remove("port").remove("fp").remove("key").remove("name")
            } else {
                e.putString("hosts", value.hosts.joinToString(","))
                    .putInt("port", value.port)
                    .putString("fp", value.fingerprint)
                    .putString("key", value.keyB64)
                    .putString("name", value.name)
            }
            e.commit()
        }
}
