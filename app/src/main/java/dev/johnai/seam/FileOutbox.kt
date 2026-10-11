package dev.johnai.seam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** One file the person shared. [name] is a display name; [size] is -1 if unknown. */
data class SharedFile(val uri: Uri, val name: String, val size: Long, val mime: String?)

/**
 * A shared file whose stream was opened while the startService URI grant was still valid.
 * [stream] is owned by the upload: closed when the upload finishes, or if the executor drops it.
 */
class OpenedShare(
    val name: String,
    val size: Long,
    val mime: String?,
    val stream: InputStream,
)

/**
 * Sends shared files to the computer, one at a time, on the link service's executor.
 *
 * The session id is captured when file_offer is written ([LinkHub.sendOn]). If the link
 * drops and reconnects, that id no longer matches, the upload fails, and no file_chunk,
 * file_done, or file_cancel is written on the new session. Chunks are read from the
 * InputStream opened in acceptSharedFiles (openInputStream or openTypedAssetFileDescriptor),
 * never the whole file and never by reopening the URI. Pings stay on the reader thread;
 * chunks are not queued on the outbox, so they cannot block them.
 *
 * Share grants are not persistable, so this does not take or release persistable URI
 * permission (releasing one could revoke a grant this app already held).
 */
class FileOutbox(
    context: Context,
    private val executor: ExecutorService,
) {
    private val app = context.applicationContext
    private val responses = FileResponseQueue()

    @Volatile private var currentTransfer: String? = null
    @Volatile private var cancelRequested: String? = null
    private var lastProgressAt = 0L

    /** One Cancel PendingIntent per transfer; progress ticks reuse it. */
    @Volatile private var progressCancel: PendingIntent? = null
    @Volatile private var progressCancelTransfer: String? = null

    /** Takes ownership of each [OpenedShare.stream]. Does not reopen the URI. */
    fun enqueueOpened(files: List<OpenedShare>) {
        for (file in files) {
            val job = StreamUpload(file.name, file.stream) { sendOne(file) }
            if (!UploadJobs.submit(executor, job)) {
                job.closeIfQueued()
                notifyNotSent(file.name)
            }
        }
    }

    /**
     * Upload a stream opened on this executor. When [onlySession] is set, the offer is
     * written only if that session is still current (a photo_request must not continue
     * on a later connection). The stream is opened here, not by the caller.
     */
    fun enqueueDeferred(
        name: String,
        size: Long,
        mime: String?,
        onlySession: Long?,
        open: () -> InputStream?,
    ) {
        val safe = FileTransfer.sanitizeName(name)
        val job = object : NamedUpload(safe, {}) {
            override fun run() {
                if (executor.isShutdown || Thread.currentThread().isInterrupted) {
                    notifyNotSent(safe)
                    return
                }
                if (onlySession != null && LinkHub.currentSessionId() != onlySession) return
                val stream = try {
                    open()
                } catch (_: Exception) {
                    null
                }
                if (stream == null) {
                    notifyNotSent(safe)
                    return
                }
                StreamUpload(safe, stream) {
                    sendOne(OpenedShare(safe, size, mime, stream), onlySession)
                }.run()
            }
        }
        if (!UploadJobs.submit(executor, job)) notifyNotSent(safe)
    }

    /** [dropped] is what [ExecutorService.shutdownNow] returned: work that never started. */
    fun onExecutorStopped(dropped: List<Runnable>) {
        cancelProgress()
        for (task in dropped) {
            (task as? StreamUpload)?.closeIfQueued()
            val name = (task as? NamedUpload)?.name ?: continue
            notifyNotSent(name)
        }
    }

    fun deliver(line: String): Boolean = responses.deliver(line)

    fun cancel(transfer: String) {
        if (transfer != currentTransfer) return
        cancelRequested = transfer
        responses.cancel(transfer)
    }

    fun notifyNotSent(name: String) {
        notifyDone(app.getString(R.string.file_not_sent), name)
    }

    private fun sendOne(file: OpenedShare, onlySession: Long? = null) {
        val name = FileTransfer.sanitizeName(file.name)
        if (executor.isShutdown || Thread.currentThread().isInterrupted) {
            notifyNotSent(name)
            return
        }
        // Session ended after the stream was opened, before FileUpload. Close it here.
        // Say nothing: the computer that asked is gone.
        if (onlySession != null && LinkHub.currentSessionId() != onlySession) {
            try {
                file.stream.close()
            } catch (_: Exception) {
            }
            return
        }
        val transfer = FileSend.randomTransferId()
        cancelRequested = null
        currentTransfer = transfer
        lastProgressAt = 0L
        try {
            if (file.size < 0L) {
                notifyFailed(name, FileUpload.ERR_READ)
                return
            }
            if (file.size > Protocol.FILE_MAX_BYTES) {
                notifyFailed(name, FileUpload.ERR_TOO_LARGE)
                return
            }
            showProgress(name, 0L, file.size, transfer)
            val result = FileUpload(ioFor(transfer, onlySession)).send(
                transfer = transfer,
                name = name,
                size = file.size,
                mime = file.mime,
                open = { byteSource(file.stream) },
                onProgress = { sent, total -> showProgress(name, sent, total, transfer) },
            )
            when {
                result.status == FileUpload.Status.SENT -> notifySent(name)
                executor.isShutdown || Thread.currentThread().isInterrupted -> notifyNotSent(name)
                else -> notifyFailed(name, result.detail)
            }
        } catch (_: Exception) {
            if (executor.isShutdown || Thread.currentThread().isInterrupted) notifyNotSent(name)
            else notifyFailed(name, FileUpload.ERR_READ)
        } finally {
            if (currentTransfer == transfer) currentTransfer = null
            cancelProgress()
        }
    }

    private fun ioFor(transfer: String, onlySession: Long? = null) = object : FileUpload.Io {
        override fun sessionId(): Long? {
            val id = LinkHub.currentSessionId() ?: return null
            if (onlySession != null && id != onlySession) return null
            return id
        }

        override fun send(sessionId: Long, frame: String): Boolean {
            if (onlySession != null && sessionId != onlySession) return false
            return LinkHub.sendOn(sessionId, frame)
        }

        override fun await(transfer: String, sessionId: Long, timeoutMs: Long): String? =
            responses.await(transfer, sessionId, { LinkHub.currentSessionId() }, timeoutMs)

        override fun isCancelled(): Boolean =
            cancelRequested == transfer || Thread.currentThread().isInterrupted

        override fun prepare(transfer: String) {
            responses.arm(transfer)
        }

        override fun finish(transfer: String) {
            responses.finish(transfer)
        }
    }

    /**
     * Stream opened in acceptSharedFiles. Does not touch the URI.
     * [StreamUpload] closes it too if send() never opens.
     */
    private fun byteSource(stream: InputStream): FileUpload.ByteSource {
        return object : FileUpload.ByteSource {
            override fun read(buf: ByteArray, off: Int, len: Int): Int = stream.read(buf, off, len)
            override fun close() {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun showProgress(name: String, sent: Long, total: Long, transfer: String) {
        if (executor.isShutdown || !canNotify()) return
        val now = SystemClock.elapsedRealtime()
        if (sent != 0L && sent != total && now - lastProgressAt < 200L) return
        lastProgressAt = now
        val nm = nm() ?: return
        ensureChannel(nm)
        val percent = if (total <= 0L) 0 else ((sent * 100) / total).toInt().coerceIn(0, 100)
        val builder = base(app.getString(R.string.file_sending, name), name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(PROGRESS_ID))
            .addAction(cancelAction(transfer))
        if (total <= 0L) builder.setProgress(0, 0, true) else builder.setProgress(100, percent, false)
        nm.notify(PROGRESS_ID, builder.build())
    }

    private fun notifySent(name: String) {
        notifyDone(app.getString(R.string.file_sent, name), name)
    }

    private fun notifyFailed(name: String, detail: String) {
        val text = if (detail.isEmpty()) name else detail
        notifyDone(app.getString(R.string.file_send_failed, name), text)
    }

    @SuppressLint("MissingPermission")
    private fun notifyDone(title: String, text: String) {
        if (!canNotify()) return
        val nm = nm() ?: return
        ensureChannel(nm)
        val id = nextId.getAndIncrement()
        nm.notify(
            id,
            base(title, text)
                .setAutoCancel(true)
                .setContentIntent(openApp(id))
                .build(),
        )
    }

    private fun cancelProgress() {
        try {
            nm()?.cancel(PROGRESS_ID)
        } catch (_: Exception) {
        }
        progressCancel?.cancel()
        progressCancel = null
        progressCancelTransfer = null
    }

    private fun base(title: String, text: String): Notification.Builder =
        Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seam)
            .setContentTitle(title)
            .setContentText(text)

    private fun cancelAction(transfer: String): Notification.Action {
        val pending = cancelPending(transfer)
        return Notification.Action.Builder(
            Icon.createWithResource(app, R.drawable.ic_stat_seam),
            app.getString(R.string.file_cancel),
            pending,
        ).build()
    }

    /** Same request code and PendingIntent for every progress tick of this transfer. */
    private fun cancelPending(transfer: String): PendingIntent {
        val cached = progressCancel
        if (cached != null && progressCancelTransfer == transfer) return cached
        progressCancel?.cancel()
        val intent = Intent(app, FileSendCancelReceiver::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_TRANSFER, transfer)
        val created = PendingIntent.getBroadcast(
            app,
            FileSend.cancelRequestCode(transfer),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        progressCancel = created
        progressCancelTransfer = transfer
        return created
    }

    private fun openApp(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            app,
            requestCode,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(nm: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.file_sends_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = app.getString(R.string.file_sends_channel_description)
        nm.createNotificationChannel(channel)
    }

    private fun nm(): NotificationManager? =
        app.getSystemService(NotificationManager::class.java)

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_SEND_FILES = "dev.johnai.seam.action.SEND_FILES"
        const val ACTION_CANCEL = "dev.johnai.seam.action.CANCEL_FILE_SEND"
        const val EXTRA_URIS = "dev.johnai.seam.extra.FILE_URIS"
        const val EXTRA_NAMES = "dev.johnai.seam.extra.FILE_NAMES"
        const val EXTRA_SIZES = "dev.johnai.seam.extra.FILE_SIZES"
        const val EXTRA_MIMES = "dev.johnai.seam.extra.FILE_MIMES"
        const val EXTRA_TRANSFER = "dev.johnai.seam.extra.TRANSFER"
        const val CHANNEL_ID = "file_sends"
        const val PROGRESS_ID = 4

        private val nextId = AtomicInteger(5000)

        fun sendIntent(context: Context, files: List<SharedFile>): Intent {
            val intent = Intent(context, LinkService::class.java).setAction(ACTION_SEND_FILES)
            intent.putParcelableArrayListExtra(EXTRA_URIS, ArrayList(files.map { it.uri }))
            intent.putStringArrayListExtra(EXTRA_NAMES, ArrayList(files.map { it.name }))
            intent.putExtra(EXTRA_SIZES, files.map { it.size }.toLongArray())
            intent.putStringArrayListExtra(EXTRA_MIMES, ArrayList(files.map { it.mime.orEmpty() }))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (files.isNotEmpty()) {
                val clip = ClipData.newRawUri(files.first().name, files.first().uri)
                for (file in files.drop(1)) clip.addItem(ClipData.Item(file.uri))
                intent.clipData = clip
            }
            return intent
        }

        @Suppress("DEPRECATION")
        fun sharedFiles(intent: Intent): List<SharedFile> {
            val uris = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS) ?: return emptyList()
            val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: return emptyList()
            val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: return emptyList()
            val mimes = intent.getStringArrayListExtra(EXTRA_MIMES)
            if (uris.size != names.size || uris.size != sizes.size) {
                return names.map { SharedFile(Uri.EMPTY, it, -1L, null) }
            }
            return uris.indices.map { i ->
                SharedFile(uris[i], names[i], sizes[i], mimes?.getOrNull(i)?.takeIf { it.isNotEmpty() })
            }
        }
    }
}

/** Closes [stream] when the upload finishes, or if shutdownNow drops the task before it starts. */
class StreamUpload(
    name: String,
    private val stream: InputStream,
    body: () -> Unit,
) : NamedUpload(name, body) {
    @Volatile private var started = false

    override fun run() {
        started = true
        try {
            super.run()
        } finally {
            closeQuiet()
        }
    }

    fun closeIfQueued() {
        if (!started) closeQuiet()
    }

    private fun closeQuiet() {
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }
}

/** Progress-notification Cancel. Explicit and not exported. */
class FileSendCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != FileOutbox.ACTION_CANCEL) return
        val id = intent.getStringExtra(FileOutbox.EXTRA_TRANSFER) ?: return
        LinkHub.cancelOutgoingFile(id)
    }
}
