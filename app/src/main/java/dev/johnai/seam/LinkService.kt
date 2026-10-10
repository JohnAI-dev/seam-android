package dev.johnai.seam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder

/** Foreground service that keeps the connection to the computer alive in the background. */
class LinkService : Service() {
    private var lastBattery: Pair<Int, Boolean>? = null
    private val statusListener: (LinkHub.Status) -> Unit = { updateNotification(it) }
    private var callActions: CallActions? = null
    private val callMonitor = CallMonitor()
    private val onCallAction: (String) -> Unit = { action -> callActions?.handle(action) }
    private val startCalls: () -> Unit = {
        callActions?.let { actions ->
            callMonitor.onLineState = { state -> actions.onLineState(state) }
        }
        callMonitor.start(this)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reportBattery(intent, force = false)
    }

    override fun onCreate() {
        super.onCreate()
        PhoneFinder.onProcessStart(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Connection to computer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Seam keeps your phone connected to your computer"
                setShowBadge(false)
            }
        )
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.status_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        LinkHub.onConnected = {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.let { reportBattery(it, force = true) }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startUpdateChecks()
        callActions = CallActions(this)
        LinkHub.onCallAction = onCallAction
        LinkHub.onStartCalls = startCalls
        LinkHub.addListener(statusListener)
        LinkHub.start(this)
        // A permission grant does not redeliver onStartCommand; the activity calls this.
        // Main looper: register call listening synchronously and read the current state.
        startCalls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (PairingStore(this).pairing == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        LinkHub.onCallAction = onCallAction
        LinkHub.onStartCalls = startCalls
        LinkHub.start(this)
        startCalls()
        return START_STICKY
    }

    @Volatile private var updateThread: Thread? = null

    /** Look for a new Seam version shortly after start and then every hour. */
    private fun startUpdateChecks() {
        if (updateThread != null) return
        updateThread = Thread({
            try {
                Thread.sleep(60_000)
                while (true) {
                    Updater.checkAndInstall(this)
                    Thread.sleep(60 * 60 * 1000L)
                }
            } catch (_: InterruptedException) {
            }
        }, "seam-updates").apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        if (LinkHub.onStartCalls === startCalls) LinkHub.onStartCalls = null
        if (LinkHub.onCallAction === onCallAction) LinkHub.onCallAction = null
        // Unmute a silenced ringer before the service is gone. Never leave it muted.
        callActions?.release()
        callMonitor.onLineState = null
        callMonitor.stop()
        callActions = null
        updateThread?.interrupt()
        updateThread = null
        LinkHub.removeListener(statusListener)
        unregisterReceiver(batteryReceiver)
        LinkHub.onConnected = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reportBattery(intent: Intent, force: Boolean) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val now = percent to charging
        if (force || now != lastBattery) {
            lastBattery = now
            LinkHub.send(Protocol.battery(percent, charging))
        }
    }

    private fun updateNotification(status: LinkHub.Status) {
        val text = when (status.state) {
            LinkClient.State.CONNECTED -> getString(R.string.status_connected, status.detail ?: "")
            LinkClient.State.REJECTED -> getString(R.string.status_rejected)
            else -> getString(R.string.status_connecting)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_seam)
            .setContentTitle("Seam")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL = "link"
        const val NOTIFICATION_ID = 1
    }
}
