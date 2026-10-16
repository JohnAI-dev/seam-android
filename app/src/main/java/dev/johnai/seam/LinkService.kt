package dev.johnai.seam

import android.annotation.SuppressLint
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
import android.os.Build
import android.os.IBinder
import java.io.InputStream
import java.util.concurrent.Executors

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

    /**
     * File uploads, one at a time. Owned here so onDestroy can shut it down and
     * [FileOutbox] can say "Not sent" for anything still queued. Not a hand-rolled worker.
     */
    private val fileSends = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seam-file-send").apply { isDaemon = true }
    }
    private var fileOutbox: FileOutbox? = null
    private val onOutgoingFile: (String) -> Boolean = { line ->
        fileOutbox?.deliver(line) == true
    }
    private val onCancelOutgoing: (String) -> Unit = { transfer ->
        fileOutbox?.cancel(transfer)
    }

    private var photoWatcher: PhotoWatcher? = null
    private val onPhotoRequest: (String) -> Unit = { id -> photoWatcher?.onRequest(id) }
    private val startPhotos: () -> Unit = { photoWatcher?.start() }
    private val onDndSet: (Boolean) -> Unit = { on -> applyDnd(on) }
    private var nowPlaying: NowPlaying? = null
    private val onMediaControl: (String) -> Unit = { action -> nowPlaying?.onControl(action) }
    private val startMedia: () -> Unit = { nowPlaying?.start() }
    private val mediaAccessLost: () -> Unit = { nowPlaying?.onAccessLost() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reportBattery(intent, force = false)
    }

    /** Interruption filter changed: tell the computer whether Do Not Disturb is on. */
    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reportDnd()
    }

    override fun onCreate() {
        super.onCreate()
        fileOutbox = FileOutbox(this, fileSends)
        photoWatcher = PhotoWatcher(this, fileOutbox!!)
        nowPlaying = NowPlaying(this)
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
            reportDnd()
            photoWatcher?.onConnected()
            nowPlaying?.onConnected()
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerDndReceiver()
        startUpdateChecks()
        callActions = CallActions(this)
        LinkHub.onCallAction = onCallAction
        LinkHub.onStartCalls = startCalls
        LinkHub.onOutgoingFile = onOutgoingFile
        LinkHub.onCancelOutgoingFile = onCancelOutgoing
        LinkHub.onPhotoRequest = onPhotoRequest
        LinkHub.onStartPhotos = startPhotos
        LinkHub.onDndSet = onDndSet
        LinkHub.onMediaControl = onMediaControl
        LinkHub.onStartMedia = startMedia
        LinkHub.onMediaAccessLost = mediaAccessLost
        photoWatcher?.start()
        nowPlaying?.start()
        LinkHub.addListener(statusListener)
        LinkHub.start(this)
        // A permission grant does not redeliver onStartCommand; the activity calls this.
        // Main looper: register call listening synchronously and read the current state.
        startCalls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FileOutbox.ACTION_SEND_FILES) {
            acceptSharedFiles(intent)
        }
        if (PairingStore(this).pairing == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        LinkHub.onCallAction = onCallAction
        LinkHub.onStartCalls = startCalls
        LinkHub.onOutgoingFile = onOutgoingFile
        LinkHub.onCancelOutgoingFile = onCancelOutgoing
        LinkHub.onPhotoRequest = onPhotoRequest
        LinkHub.onStartPhotos = startPhotos
        LinkHub.onDndSet = onDndSet
        LinkHub.onMediaControl = onMediaControl
        LinkHub.onStartMedia = startMedia
        LinkHub.onMediaAccessLost = mediaAccessLost
        photoWatcher?.start()
        nowPlaying?.start()
        LinkHub.start(this)
        startCalls()
        return START_STICKY
    }

    /**
     * Open every stream before this method returns. The FLAG_GRANT_READ_URI_PERMISSION
     * on the startService intent is dropped when onStartCommand returns, and the activity
     * grant is already gone after finish(). Chunks are streamed later from these streams.
     * openFileDescriptor is not used: virtual providers (Drive, Gmail) have no fd.
     */
    private fun acceptSharedFiles(intent: Intent) {
        val described = FileOutbox.sharedFiles(intent)
        val outbox = fileOutbox
        if (outbox == null || PairingStore(this).pairing == null || fileSends.isShutdown) {
            described.forEach { outbox?.notifyNotSent(it.name) }
            return
        }
        val opened = ArrayList<OpenedShare>(described.size)
        var handedOff = false
        try {
            for (file in described) {
                openGranted(file, outbox)?.let { opened.add(it) }
            }
            outbox.enqueueOpened(opened)
            handedOff = true
        } finally {
            if (!handedOff) {
                for (share in opened) {
                    try {
                        share.stream.close()
                    } catch (_: Exception) {
                    }
                    try {
                        outbox.notifyNotSent(share.name)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /** Null when this file will not be sent. Caller must close a non-null stream. */
    private fun openGranted(file: SharedFile, outbox: FileOutbox): OpenedShare? {
        if (!FileSend.mayOpenSharedUri(file.uri.scheme, file.uri.authority, packageName)) {
            outbox.notifyNotSent(file.name)
            return null
        }
        val opened = openForUpload(file)
        if (opened == null) {
            outbox.notifyNotSent(file.name)
            return null
        }
        return OpenedShare(file.name, opened.second, file.mime, opened.first)
    }

    /**
     * Opens while the startService URI grant is still valid. openInputStream covers ordinary
     * files; openTypedAssetFileDescriptor covers virtual documents that have no fd.
     * The returned stream owns any descriptor. Nothing is read here.
     */
    @SuppressLint("Recycle")
    private fun openForUpload(file: SharedFile): Pair<InputStream, Long>? {
        val plain = try {
            contentResolver.openInputStream(file.uri)
        } catch (_: Exception) {
            null
        }
        if (plain != null) return plain to file.size
        val mimes = ArrayList<String>(2)
        mimes.add("*/*")
        val specific = file.mime
        if (!specific.isNullOrEmpty() && specific != "*/*") mimes.add(specific)
        for (mime in mimes) {
            val afd = try {
                contentResolver.openTypedAssetFileDescriptor(file.uri, mime, null)
            } catch (_: Exception) {
                null
            } ?: continue
            val stream = try {
                HeldStream(afd.createInputStream(), afd)
            } catch (_: Exception) {
                try {
                    afd.close()
                } catch (_: Exception) {
                }
                continue
            }
            val size = when {
                file.size >= 0L -> file.size
                afd.length >= 0L -> afd.length
                else -> file.size
            }
            return stream to size
        }
        return null
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
        try {
            photoWatcher?.stop()
        } catch (_: Exception) {
        }
        photoWatcher = null
        val dropped = try {
            fileSends.shutdownNow()
        } catch (_: Exception) {
            emptyList()
        }
        try {
            fileOutbox?.onExecutorStopped(dropped)
        } catch (_: Exception) {
        }
        fileOutbox = null
        if (LinkHub.onOutgoingFile === onOutgoingFile) LinkHub.onOutgoingFile = null
        if (LinkHub.onCancelOutgoingFile === onCancelOutgoing) LinkHub.onCancelOutgoingFile = null
        if (LinkHub.onStartCalls === startCalls) LinkHub.onStartCalls = null
        if (LinkHub.onCallAction === onCallAction) LinkHub.onCallAction = null
        if (LinkHub.onPhotoRequest === onPhotoRequest) LinkHub.onPhotoRequest = null
        if (LinkHub.onStartPhotos === startPhotos) LinkHub.onStartPhotos = null
        if (LinkHub.onDndSet === onDndSet) LinkHub.onDndSet = null
        try {
            nowPlaying?.stop()
        } catch (_: Exception) {
        }
        nowPlaying = null
        if (LinkHub.onMediaControl === onMediaControl) LinkHub.onMediaControl = null
        if (LinkHub.onStartMedia === startMedia) LinkHub.onStartMedia = null
        if (LinkHub.onMediaAccessLost === mediaAccessLost) LinkHub.onMediaAccessLost = null
        // Unmute a silenced ringer before the service is gone. Never leave it muted.
        callActions?.release()
        callMonitor.onLineState = null
        callMonitor.stop()
        callActions = null
        updateThread?.interrupt()
        updateThread = null
        LinkHub.removeListener(statusListener)
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(dndReceiver)
        LinkHub.onConnected = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reportDnd() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val on = try {
            Dnd.isOn(nm.currentInterruptionFilter)
        } catch (_: Exception) {
            return
        }
        LinkHub.send(Protocol.dnd(on))
    }

    /** dnd_set: priority when on, all when off. Ignored without policy access. */
    @SuppressLint("MissingPermission")
    private fun applyDnd(on: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        try {
            nm.setInterruptionFilter(Dnd.filterFor(on))
        } catch (_: Exception) {
            return
        }
        reportDnd()
    }

    @SuppressLint("NewApi", "UnspecifiedRegisterReceiverFlag")
    private fun registerDndReceiver() {
        val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Re-read the filter, so a spoofed broadcast cannot lie. Exported so the
            // system broadcast is delivered.
            registerReceiver(dndReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(dndReceiver, filter)
        }
    }

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

/** Closes [extra] (an AssetFileDescriptor) after the provider stream. Double-close is ignored. */
private class HeldStream(
    private val inner: InputStream,
    private val extra: AutoCloseable?,
) : InputStream() {
    override fun read(): Int = inner.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

    override fun close() {
        try {
            inner.close()
        } catch (_: Exception) {
        }
        try {
            extra?.close()
        } catch (_: Exception) {
        }
    }
}
