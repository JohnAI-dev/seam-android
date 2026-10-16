package dev.johnai.seam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipboardManager
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
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
    private lateinit var mediaShare: Switch
    private lateinit var mediaNeedsNotifications: TextView
    private var renderingMediaShare = false
    private lateinit var dndAccess: Button
    private lateinit var calls: Button
    private lateinit var photos: Button
    private lateinit var background: Button
    private lateinit var forget: Button
    private lateinit var allowUpdates: Button
    private lateinit var updateText: TextView
    private val updateListener: (Updater.Status) -> Unit = { runOnUiThread { renderUpdate() } }
    private lateinit var steps: TextView
    private val statusListener: (LinkHub.Status) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PairingStore(this)
        setContentView(buildLayout())
        // Only on a fresh start: a recreated screen (e.g. after rotation) must not pair again.
        if (savedInstanceState == null) handlePairingIntent(intent)
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
        Updater.addListener(updateListener)
        renderUpdate()
        // An update downloaded in the background may be waiting for Android's confirmation.
        val confirm = Updater.takePendingConfirm()
        if (confirm != null) {
            try { startActivity(confirm) } catch (_: Exception) {}
        } else if (System.currentTimeMillis() - Updater.status.checkedAt > 15 * 60 * 1000L) {
            checkForUpdates()
        }
        LinkHub.startService(this)
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            LinkHub.startCallMonitor()
        }
        if (PhotoAccess.granted(this)) LinkHub.startRecentPhotos()
        LinkHub.startNowPlaying()
        render()
    }

    private fun checkForUpdates() {
        Thread({ Updater.checkAndInstall(this) }, "seam-update-check").start()
    }

    private fun renderUpdate() {
        if (!::updateText.isInitialized) return
        val s = Updater.status
        updateText.text = when (s.state) {
            Updater.State.IDLE -> ""
            Updater.State.CHECKING -> getString(R.string.update_checking)
            Updater.State.UP_TO_DATE -> getString(R.string.update_up_to_date)
            Updater.State.DOWNLOADING -> getString(R.string.update_downloading, s.detail)
            Updater.State.INSTALLING -> getString(R.string.update_installing, s.detail)
            Updater.State.WAITING_FOR_CONFIRM -> getString(R.string.update_confirm)
            Updater.State.NEEDS_PERMISSION -> getString(R.string.update_permission, s.detail)
            Updater.State.FAILED -> getString(R.string.update_failed, s.detail)
        }
        updateText.visibility = if (updateText.text.isEmpty()) View.GONE else View.VISIBLE
        if (::allowUpdates.isInitialized) {
            allowUpdates.visibility =
                if (packageManager.canRequestPackageInstalls()) View.GONE else View.VISIBLE
        }
    }

    override fun onPause() {
        Updater.removeListener(updateListener)
        LinkHub.removeListener(statusListener)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALLS) {
            // The permission dialog does not redeliver onStartCommand to a running service.
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                LinkHub.startCallMonitor()
            }
        } else if (requestCode == REQUEST_PHOTOS && PhotoAccess.granted(this)) {
            LinkHub.startService(this)
            LinkHub.startRecentPhotos()
        }
        if (requestCode == REQUEST_CALLS || requestCode == REQUEST_PHOTOS) render()
    }

    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (!data.startsWith("seam://")) return
        pairWith(data)
    }

    /** The open "Pair with …?" question, if any (tests press its buttons). */
    internal var pairingDialog: AlertDialog? = null

    private fun pairWith(link: String) {
        val info = try {
            PairingInfo.parse(link)
        } catch (e: IllegalArgumentException) {
            showMessage(getString(R.string.pair_invalid, e.message ?: ""))
            return
        }
        // Any web page or app can open a seam:// link, so never pair without asking: pairing
        // sends this phone's notifications to that computer.
        val current = store.pairing
        val message = buildString {
            append(getString(R.string.pair_confirm_body))
            append("\n\n")
            append(getString(R.string.pair_confirm_details, info.hosts.joinToString(", "), info.fingerprint.take(16)))
            if (current != null) {
                append("\n\n")
                append(getString(R.string.pair_confirm_replaces, current.name))
            }
        }
        pairingDialog?.dismiss()
        pairingDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pair_confirm_title, info.name))
            .setMessage(message)
            .setPositiveButton(R.string.pair_confirm_yes) { _, _ -> savePairing(info) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showMessage(getString(R.string.pair_cancelled)) }
            .setOnDismissListener { pairingDialog = null }
            .show()
    }

    /** Pair button. End-to-end tests call this instead of the confirmation dialog. */
    internal fun savePairing(info: PairingInfo) {
        LinkHub.stopService(this)
        store.pairing = info
        LinkHub.startService(this)
        showMessage(getString(R.string.pair_started, info.name))
        render()
    }

    private fun onMediaShareChanged(on: Boolean) {
        MediaSharing(this).setEnabled(on)
        if (on && !NotificationAccess.granted(this)) {
            showMessage(getString(R.string.media_needs_notifications))
        }
        if (on) LinkHub.startService(this)
        LinkHub.startNowPlaying()
        render()
    }

    private fun showMessage(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
    }

    /** Read the clipboard while this screen is in front and send it to the computer. */
    private fun sendClipboardToComputer() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            showMessage(getString(R.string.clipboard_nothing))
            return
        }
        val name = LinkHub.sendClipboard(this, text)
        showMessage(
            if (name == null) getString(R.string.clipboard_not_connected)
            else getString(R.string.clipboard_sent, name)
        )
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
        val hasAccess = NotificationAccess.granted(this)
        notificationAccess.visibility = if (hasAccess) View.GONE else View.VISIBLE
        steps.visibility = if (hasAccess) View.GONE else View.VISIBLE
        renderingMediaShare = true
        mediaShare.isChecked = MediaSharing(this).enabled()
        renderingMediaShare = false
        mediaNeedsNotifications.visibility =
            if (mediaShare.isChecked && !hasAccess) View.VISIBLE else View.GONE
        dndAccess.visibility = if (hasDndAccess()) View.GONE else View.VISIBLE
        calls.visibility = if (hasCallPermissions()) View.GONE else View.VISIBLE
        photos.visibility = if (PhotoAccess.granted(this)) View.GONE else View.VISIBLE
        val pm = getSystemService(PowerManager::class.java)
        background.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        forget.visibility = if (pairing == null) View.GONE else View.VISIBLE
        allowUpdates.visibility =
            if (packageManager.canRequestPackageInstalls()) View.GONE else View.VISIBLE
    }

    /** Notification policy access: the computer may set Do Not Disturb only after this. */
    private fun hasDndAccess(): Boolean =
        getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Phone state, call log, and declining. Contacts is optional and only fills in a name. */
    private fun hasCallPermissions(): Boolean {
        val phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val log = checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        val decline = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        return phone && log && decline
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
        column.addView(TextView(this).apply {
            text = getString(R.string.media_share_hint)
            setPadding(0, dp(16), 0, dp(4))
        })
        mediaShare = Switch(this).apply {
            tag = "media_share"
            text = getString(R.string.show_media)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (!renderingMediaShare) onMediaShareChanged(checked)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        column.addView(mediaShare)
        mediaNeedsNotifications = TextView(this).apply {
            tag = "media_needs_notifications"
            text = getString(R.string.media_needs_notifications)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(mediaNeedsNotifications)
        dndAccess = button(R.string.allow_silence) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        column.addView(dndAccess)
        calls = button(R.string.show_calls) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.ANSWER_PHONE_CALLS,
                ),
                REQUEST_CALLS,
            )
        }
        column.addView(calls)
        photos = button(R.string.show_photos) {
            requestPermissions(arrayOf(PhotoAccess.permission()), REQUEST_PHOTOS)
        }
        column.addView(photos)
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
        column.addView(button(R.string.send_clipboard) { sendClipboardToComputer() })
        forget = button(R.string.forget) {
            LinkHub.stopService(this)
            store.pairing = null
            render()
        }
        column.addView(forget)
        allowUpdates = button(R.string.allow_updates) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
        }
        column.addView(allowUpdates)
        column.addView(button(R.string.update_check) {
            val confirm = Updater.takePendingConfirm()
            if (confirm != null) {
                try { startActivity(confirm) } catch (_: Exception) {}
            } else {
                checkForUpdates()
            }
        })
        updateText = TextView(this).apply {
            tag = "update"
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        column.addView(updateText)
        column.addView(TextView(this).apply {
            tag = "version"
            @Suppress("DEPRECATION")
            text = getString(R.string.version_line, packageManager.getPackageInfo(packageName, 0).versionName)
            alpha = 0.6f
            setPadding(0, dp(24), 0, 0)
        })
        return ScrollView(this).apply {
            addView(column)
            padForSystemBars()
        }
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

    private companion object {
        const val REQUEST_CALLS = 2
        const val REQUEST_PHOTOS = 3
    }
}
