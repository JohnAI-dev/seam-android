package dev.johnai.seam

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The only screen: pairing, status, and the permissions Seam needs.
 * Opened by the camera when scanning the computer's QR code (seam://pair?...).
 */
class MainActivity : Activity() {
    private lateinit var store: PairingStore
    private lateinit var status: TextView
    private lateinit var notice: TextView
    private lateinit var paired: TextView
    private lateinit var notificationAccess: Button
    private lateinit var background: Button
    private lateinit var forget: Button
    private lateinit var steps: TextView
    private val statusListener: (LinkHub.Status) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PairingStore(this)
        setContentView(buildLayout())
        handlePairingIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        LinkHub.addListener(statusListener)
        LinkHub.startService(this)
        render()
    }

    override fun onPause() {
        LinkHub.removeListener(statusListener)
        super.onPause()
    }

    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (!data.startsWith("seam://")) return
        pairWith(data)
        // Don't re-pair if the screen is recreated.
        intent.data = null
    }

    private fun pairWith(link: String) {
        val info = try {
            PairingInfo.parse(link)
        } catch (e: IllegalArgumentException) {
            showMessage(getString(R.string.pair_invalid, e.message ?: ""))
            return
        }
        LinkHub.stopService(this)
        store.pairing = info
        LinkHub.startService(this)
        showMessage(getString(R.string.pair_started, info.name))
        render()
    }

    private fun showMessage(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
    }

    private fun render() {
        val pairing = store.pairing
        val s = LinkHub.status
        paired.text = if (pairing == null) getString(R.string.not_paired)
        else getString(R.string.paired_with, pairing.name)
        status.text = when {
            pairing == null -> getString(R.string.how_to_pair)
            s.state == LinkClient.State.CONNECTED -> getString(R.string.status_connected, s.detail ?: pairing.name)
            s.state == LinkClient.State.REJECTED -> getString(R.string.status_rejected_detail, s.detail ?: "")
            else -> getString(R.string.status_connecting)
        }
        val hasAccess = hasNotificationAccess()
        notificationAccess.visibility = if (hasAccess) View.GONE else View.VISIBLE
        steps.visibility = if (hasAccess) View.GONE else View.VISIBLE
        val pm = getSystemService(PowerManager::class.java)
        background.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        forget.visibility = if (pairing == null) View.GONE else View.VISIBLE
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, SeamNotificationListener::class.java).flattenToString()
        return enabled.split(":").any { it == me }
    }

    private fun buildLayout(): View {
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.tagline)
            alpha = 0.7f
        })
        paired = TextView(this).apply {
            tag = "paired"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(24), 0, dp(4))
        }
        column.addView(paired)
        status = TextView(this).apply {
            tag = "status"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        column.addView(status)
        notice = TextView(this).apply {
            tag = "notice"
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(notice)

        steps = TextView(this).apply {
            text = getString(R.string.notification_access_steps)
            setPadding(0, dp(24), 0, dp(8))
        }
        column.addView(steps)
        notificationAccess = button(R.string.allow_notifications) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        column.addView(notificationAccess)
        background = button(R.string.keep_running) {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        column.addView(background)
        column.addView(button(R.string.paste_link) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) showMessage(getString(R.string.clipboard_empty)) else pairWith(text)
        })
        forget = button(R.string.forget) {
            LinkHub.stopService(this)
            store.pairing = null
            render()
        }
        column.addView(forget)
        return ScrollView(this).apply { addView(column) }
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(label)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
