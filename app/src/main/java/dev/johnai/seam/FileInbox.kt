package dev.johnai.seam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives a file into Downloads (protocol/PROTOCOL.md, "Sending files").
 * The row is inserted with IS_PENDING=1 so it stays hidden until the hash checks out.
 * MediaStore adds " (1)", " (2)", ... when that name already exists.
 * No sending: this only accepts files the computer offers.
 */
class FileInbox(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()
    private val active = HashMap<String, Active>()

    private class Active(
        val incoming: FileTransfer.Incoming,
        val uri: Uri,
        val stream: OutputStream,
        val mime: String,
    ) {
        val transfer: String get() = incoming.transfer
    }

    /** One incoming frame. Returns a line to write back, or null. */
    fun handle(frame: String): String? = synchronized(lock) {
        when (Protocol.parse(frame)?.opt("type")) {
            "file_offer" -> offer(frame)
            "file_chunk" -> chunk(frame)
            "file_done" -> done(frame)
            "file_cancel" -> cancel(frame)
            else -> null
        }
    }

    /** Drop every partial file. Does not send file_result; the link may already be down. */
    fun abandon() {
        synchronized(lock) {
            val all = active.values.toList()
            active.clear()
            for (item in all) release(item, delete = true)
        }
    }

    private fun offer(frame: String): String? {
        val offer = Protocol.parseFileOffer(frame) ?: return null
        if (offer.size !in 0..FileTransfer.MAX_BYTES) {
            return Protocol.fileReject(offer.transfer, "file is too large")
        }
        if (active.containsKey(offer.transfer)) {
            return Protocol.fileReject(offer.transfer, "transfer already exists")
        }
        val incoming = FileTransfer.Incoming(offer.transfer, offer.name, offer.size, offer.mime)
        val mime = mimeOf(incoming.name, offer.mime)
        val created = try {
            insertPending(incoming.name, mime)
        } catch (_: Exception) {
            return Protocol.fileReject(offer.transfer, "could not save")
        }
        active[offer.transfer] = Active(incoming, created.first, created.second, mime)
        return Protocol.fileAccept(offer.transfer)
    }

    private fun chunk(frame: String): String? {
        val part = Protocol.parseFileChunk(frame) ?: return null
        val item = active[part.transfer] ?: return null
        if (!item.incoming.chunk(part.seq, part.data)) {
            return fail(item, item.incoming.failure ?: "transfer failed")
        }
        return try {
            item.stream.write(part.data)
            null
        } catch (_: Exception) {
            fail(item, "could not save")
        }
    }

    private fun done(frame: String): String? {
        val end = Protocol.parseFileDone(frame) ?: return null
        val item = active[end.transfer] ?: return null
        if (!item.incoming.done(end.sha256)) {
            return fail(item, item.incoming.failure ?: "transfer failed")
        }
        return succeed(item)
    }

    private fun cancel(frame: String): String? {
        val id = Protocol.parseFileCancel(frame) ?: return null
        val item = active[id] ?: return null
        return fail(item, "cancelled")
    }

    private fun succeed(item: Active): String {
        active.remove(item.transfer)
        try {
            item.stream.flush()
            item.stream.close()
        } catch (_: Exception) {
            deleteQuiet(item.uri)
            return Protocol.fileResult(item.transfer, false, "could not save")
        }
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        val updated = try {
            app.contentResolver.update(item.uri, values, null, null)
        } catch (_: Exception) {
            -1
        }
        if (updated < 1) {
            deleteQuiet(item.uri)
            return Protocol.fileResult(item.transfer, false, "could not save")
        }
        val name = displayName(item.uri, item.incoming.name)
        try {
            notifyReceived(name, item.uri, item.mime)
        } catch (_: Exception) {
        }
        return Protocol.fileResult(item.transfer, true)
    }

    private fun fail(item: Active, error: String): String {
        active.remove(item.transfer)
        release(item, delete = true)
        return Protocol.fileResult(item.transfer, false, error)
    }

    private fun release(item: Active, delete: Boolean) {
        try {
            item.stream.close()
        } catch (_: Exception) {
        }
        if (delete) deleteQuiet(item.uri)
    }

    private fun insertPending(name: String, mime: String): Pair<Uri, OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("no download entry")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("no stream")
            return uri to stream
        } catch (e: Exception) {
            deleteQuiet(uri)
            throw e
        }
    }

    private fun deleteQuiet(uri: Uri) {
        try {
            app.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun displayName(uri: Uri, fallback: String): String {
        try {
            app.contentResolver.query(
                uri,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val i = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                if (i >= 0 && cursor.moveToFirst()) {
                    val found = cursor.getString(i)
                    if (!found.isNullOrEmpty()) return found
                }
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    private fun mimeOf(name: String, offered: String?): String {
        val given = offered?.trim()
        if (!given.isNullOrEmpty() && given.length <= 127 && '/' in given &&
            given.none { it.isISOControl() || it.isWhitespace() }
        ) {
            return given
        }
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return "application/octet-stream"
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        if (ext.length > 16) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    @SuppressLint("MissingPermission")
    private fun notifyReceived(name: String, uri: Uri, mime: String) {
        if (!canNotify()) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.files_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = app.getString(R.string.files_channel_description)
        nm.createNotificationChannel(channel)
        val text = app.getString(R.string.file_received, name)
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(name, uri)
        }
        val id = nextId.getAndIncrement()
        val pending = PendingIntent.getActivity(
            app,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            id,
            Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seam)
                .setContentTitle(text)
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build(),
        )
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "files"
        private val nextId = AtomicInteger(1000)
    }
}
