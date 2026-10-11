package dev.johnai.seam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** READ_MEDIA_IMAGES on API 33+, READ_EXTERNAL_STORAGE before that. */
object PhotoAccess {
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
}

/**
 * Announces new photos and screenshots while the link service runs
 * (protocol/PROTOCOL.md, "Recent photos").
 *
 * Without [PhotoAccess] the feature is off: no observer, no photo_new, no upload.
 * On connect the 12 most recently saved images are sent oldest first. Later saves are
 * announced from a [ContentObserver] on MediaStore images. Thumbnails come from
 * [android.content.ContentResolver.loadThumbnail] at 256 px, JPEG at most 32 KiB.
 *
 * A photo_request is uploaded with the existing file transfer, bound to the session that
 * asked, using the MediaStore display name.
 *
 * An id is marked announced only after photo_new is written. A thumbnail that is not
 * ready yet is retried, even after a newer photo would otherwise move the date watermark
 * past it.
 */
@SuppressLint("MissingPermission")
class PhotoWatcher(
    context: Context,
    private val uploads: FileOutbox,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seam-photos").apply { isDaemon = true }
    }
    private val lock = Any()
    private var registered = false
    private var generation = 0
    private var readyGen = -1
    private var sentForSession: Long? = null
    private val book = PhotoAnnounceLog()
    private var retryDelayMs = RETRY_DELAY_MS

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (synchronized(lock) { registered }) scheduleNew()
        }
    }

    private val scanSoon = Runnable { submit { publishNew() } }
    private val scanLater = Runnable { submit { publishNew() } }
    private val retryScan = Runnable { submit { publishNew() } }

    /** Register the observer if permission is granted. Idempotent. */
    fun start() {
        if (!PhotoAccess.granted(app)) {
            unregisterObserver()
            return
        }
        if (!register()) return
        if (LinkHub.status.state == LinkClient.State.CONNECTED) beginRecent()
    }

    /** A new link session: send the 12 most recent, oldest first. */
    fun onConnected() {
        if (!PhotoAccess.granted(app)) return
        if (!synchronized(lock) { registered }) return
        beginRecent()
    }

    /** Computer asked for the full image. No-op without permission or a live session. */
    fun onRequest(id: String) {
        if (!PhotoAccess.granted(app)) return
        val session = LinkHub.currentSessionId() ?: return
        val wanted = id.trim()
        if (wanted.isEmpty()) return
        submit {
            if (LinkHub.currentSessionId() != session) return@submit
            if (!PhotoAccess.granted(app)) return@submit
            offer(wanted, session)
        }
    }

    /** Unregister and drop queued scans. Idempotent. */
    fun stop() {
        unregisterObserver()
        try {
            work.shutdownNow()
        } catch (_: Exception) {
        }
    }

    private fun register(): Boolean {
        synchronized(lock) {
            if (registered) return true
        }
        return try {
            app.contentResolver.registerContentObserver(imagesUri(), true, observer)
            synchronized(lock) { registered = true }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun unregisterObserver() {
        main.removeCallbacks(scanSoon)
        main.removeCallbacks(scanLater)
        main.removeCallbacks(retryScan)
        val was = synchronized(lock) {
            val on = registered
            registered = false
            generation++
            book.reset()
            readyGen = -1
            sentForSession = null
            retryDelayMs = RETRY_DELAY_MS
            on
        }
        if (!was) return
        try {
            app.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {
        }
    }

    private fun beginRecent() {
        val session = LinkHub.currentSessionId() ?: return
        val gen = synchronized(lock) {
            if (!registered) return
            if (sentForSession == session) return
            sentForSession = session
            generation++
            book.reset()
            readyGen = -1
            retryDelayMs = RETRY_DELAY_MS
            generation
        }
        main.removeCallbacks(retryScan)
        submit { publishRecent(gen) }
    }

    private fun scheduleNew() {
        if (work.isShutdown) return
        main.removeCallbacks(scanSoon)
        main.removeCallbacks(scanLater)
        main.postDelayed(scanSoon, SCAN_DELAY_MS)
        main.postDelayed(scanLater, SCAN_AGAIN_MS)
    }

    private fun publishRecent(gen: Int) {
        if (!still(gen) || !PhotoAccess.granted(app)) return
        val session = LinkHub.currentSessionId() ?: return
        val rows = query(
            notPendingClause(),
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
            RECENT_QUERY_CAP,
        )
        if (!still(gen) || LinkHub.currentSessionId() != session) return
        val picked = pickRecentPhotos(rows.map { it.candidate() })
        val byId = rows.associateBy { it.id }
        val ordered = picked.mapNotNull { byId[it.id] }
        val sending = ordered.map { it.id }.toSet()
        synchronized(lock) {
            if (generation != gen) return
            book.reset()
            for (row in rows) {
                if (row.id !in sending) book.skip(row.addedSec)
            }
            readyGen = gen
        }
        for (row in ordered) {
            if (!deliverNew(row, session, gen)) return
        }
        syncRetryTimer()
    }

    private fun publishNew() {
        if (LinkHub.status.state != LinkClient.State.CONNECTED) return
        if (!PhotoAccess.granted(app)) return
        val gen = synchronized(lock) { generation }
        val ready = synchronized(lock) { registered && readyGen == gen }
        if (!ready) {
            if (synchronized(lock) { registered && generation == gen }) {
                main.postDelayed({
                    if (synchronized(lock) { registered && generation == gen }) submit { publishNew() }
                }, SCAN_DELAY_MS)
            }
            return
        }
        val session = LinkHub.currentSessionId() ?: return
        val since = synchronized(lock) { (book.watermarkSec - 1).coerceAtLeast(0L) }
        val pendingIds = synchronized(lock) { book.pendingIds() }
        val rows = query(
            "${notPendingClause()} AND ${MediaStore.Images.Media.DATE_ADDED}>=?",
            arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC",
            0,
        )
        val seen = rows.map { it.id }.toSet()
        val extras = ArrayList<ImageRow>()
        for (id in pendingIds) {
            if (id in seen) continue
            if (id.toLongOrNull() == null) {
                synchronized(lock) { book.drop(id) }
                continue
            }
            // Date window should still include a pending photo. This covers a miss.
            imageById(id)?.let { extras.add(it) }
        }
        val fresh = (rows + extras).filter { row -> synchronized(lock) { !book.isAnnounced(row.id) } }
        if (fresh.isEmpty()) {
            syncRetryTimer()
            return
        }
        val ordered = pickRecentPhotos(fresh.map { it.candidate() }, fresh.size)
        val byId = fresh.associateBy { it.id }
        for (item in ordered) {
            val row = byId[item.id] ?: continue
            if (!deliverNew(row, session, gen)) return
        }
        syncRetryTimer()
    }

    /**
     * Send one photo_new. False when this batch should stop (session changed).
     * A failed thumbnail is not announced, so a later scan can retry it.
     */
    private fun deliverNew(row: ImageRow, session: Long, gen: Int): Boolean {
        if (!still(gen) || LinkHub.currentSessionId() != session) return false
        when (sendNew(row, session)) {
            SendOutcome.SENT -> synchronized(lock) {
                if (generation == gen && registered) book.markSent(row.id, row.addedSec)
            }
            SendOutcome.RETRY -> synchronized(lock) {
                if (generation == gen && registered) book.markRetry(row.id, row.addedSec)
            }
            SendOutcome.STOPPED -> return false
        }
        return true
    }

    private enum class SendOutcome { SENT, RETRY, STOPPED }

    /** SENT only after [LinkHub.sendOn] accepts the frame. Thumbnail failure is RETRY, not success. */
    private fun sendNew(row: ImageRow, session: Long): SendOutcome {
        if (LinkHub.currentSessionId() != session) return SendOutcome.STOPPED
        val thumb = thumbJpeg(row.uri) ?: return SendOutcome.RETRY
        val frame = Protocol.photoNew(row.id, row.name, row.taken, thumb) ?: return SendOutcome.RETRY
        if (LinkHub.currentSessionId() != session) return SendOutcome.STOPPED
        return if (LinkHub.sendOn(session, frame)) SendOutcome.SENT else SendOutcome.STOPPED
    }

    /** One image by MediaStore id, or null if it is gone or still pending. */
    private fun imageById(id: String): ImageRow? {
        val numeric = id.toLongOrNull() ?: return null
        return query(
            "${MediaStore.Images.Media._ID}=? AND ${notPendingClause()}",
            arrayOf(numeric.toString()),
            null,
            1,
        ).firstOrNull()
    }

    private fun syncRetryTimer() {
        val delay = synchronized(lock) {
            if (!registered || book.pendingIds().isEmpty()) {
                retryDelayMs = RETRY_DELAY_MS
                null
            } else {
                val wait = retryDelayMs
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
                wait
            }
        }
        main.removeCallbacks(retryScan)
        if (delay != null && !work.isShutdown) main.postDelayed(retryScan, delay)
    }

    private fun offer(id: String, session: Long) {
        val numeric = id.toLongOrNull() ?: return
        val selection = "${MediaStore.Images.Media._ID}=? AND ${notPendingClause()}"
        val row = query(selection, arrayOf(numeric.toString()), null, 1).firstOrNull() ?: return
        if (LinkHub.currentSessionId() != session) return
        var size = row.size
        if (size < 0L) size = fileLength(row.uri)
        if (size < 0L || size > Protocol.FILE_MAX_BYTES) return
        uploads.enqueueDeferred(row.name, size, row.mime, session) {
            try {
                app.contentResolver.openInputStream(row.uri)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun fileLength(uri: Uri): Long = try {
        app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun query(
        selection: String?,
        args: Array<String>?,
        sort: String?,
        cap: Int,
    ): List<ImageRow> {
        if (!PhotoAccess.granted(app)) return emptyList()
        return try {
            readImages(selection, args, sort, cap)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readImages(
        selection: String?,
        args: Array<String>?,
        sort: String?,
        cap: Int,
    ): List<ImageRow> {
        val uri = imagesUri()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val out = ArrayList<ImageRow>()
        app.contentResolver.query(uri, projection, selection, args, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            if (idCol < 0) return emptyList()
            while (cursor.moveToNext()) {
                if (cap > 0 && out.size >= cap) break
                val id = cursor.getLong(idCol)
                if (id < 0L) continue
                val addedSec = if (addedCol >= 0 && !cursor.isNull(addedCol)) cursor.getLong(addedCol) else 0L
                val dateTaken = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol) else 0L
                val addedMs = addedSec.coerceAtLeast(0L) * 1000L
                val taken = if (dateTaken > 0L) dateTaken else addedMs
                val recent = if (addedMs > 0L) addedMs else taken
                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                if (size == 0L) continue
                val rawName = if (nameCol >= 0) cursor.getString(nameCol) else null
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                out.add(
                    ImageRow(
                        id = id.toString(),
                        name = rawName?.takeIf { it.isNotBlank() } ?: "photo.jpg",
                        taken = taken,
                        recent = recent,
                        addedSec = addedSec.coerceAtLeast(0L),
                        size = size,
                        mime = mime,
                        uri = ContentUris.withAppendedId(uri, id),
                    ),
                )
            }
        }
        return out
    }

    private fun thumbJpeg(uri: Uri): ByteArray? {
        val loaded = try {
            app.contentResolver.loadThumbnail(
                uri,
                Size(Protocol.PHOTO_THUMB_SIDE, Protocol.PHOTO_THUMB_SIDE),
                null,
            )
        } catch (_: Exception) {
            return null
        }
        val bitmap = softwareCopy(loaded)
        return try {
            jpegAtMost(bitmap, Protocol.PHOTO_THUMB_MAX_BYTES)
        } catch (_: Exception) {
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            if (bitmap !== loaded && !loaded.isRecycled) loaded.recycle()
        }
    }

    private fun softwareCopy(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    }

    /** JPEG of at most [maxBytes], scaling down if quality alone is not enough. */
    private fun jpegAtMost(source: Bitmap, maxBytes: Int): ByteArray? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        var current = source
        var owned: Bitmap? = null
        try {
            var guard = 0
            while (guard++ < 8) {
                var quality = 85
                while (quality >= 20) {
                    val out = ByteArrayOutputStream()
                    if (!current.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                    val bytes = out.toByteArray()
                    if (bytes.isNotEmpty() && bytes.size <= maxBytes) return bytes
                    quality -= 15
                }
                val longSide = maxOf(current.width, current.height)
                if (longSide <= 16) return null
                val w = maxOf(1, current.width * 3 / 4)
                val h = maxOf(1, current.height * 3 / 4)
                if (w == current.width && h == current.height) return null
                val scaled = Bitmap.createScaledBitmap(current, w, h, true)
                val previous = owned
                if (previous != null && previous !== source && previous !== scaled) previous.recycle()
                owned = if (scaled === source) null else scaled
                current = scaled
            }
            return null
        } finally {
            val extra = owned
            if (extra != null && extra !== source && !extra.isRecycled) extra.recycle()
        }
    }

    private fun still(gen: Int): Boolean = synchronized(lock) { registered && generation == gen }

    private fun submit(block: () -> Unit) {
        if (work.isShutdown) return
        try {
            work.execute {
                if (work.isShutdown) return@execute
                try {
                    block()
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun imagesUri(): Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun notPendingClause(): String =
        "(${MediaStore.MediaColumns.IS_PENDING} IS NULL OR ${MediaStore.MediaColumns.IS_PENDING}=0)"

    private class ImageRow(
        val id: String,
        val name: String,
        val taken: Long,
        val recent: Long,
        val addedSec: Long,
        val size: Long,
        val mime: String?,
        val uri: Uri,
    ) {
        fun candidate(): PhotoCandidate = PhotoCandidate(id, name, taken, recent)
    }

    private companion object {
        const val SCAN_DELAY_MS = 600L
        const val SCAN_AGAIN_MS = 1_800L
        const val RETRY_DELAY_MS = 600L
        const val RETRY_MAX_MS = 30_000L
        const val RECENT_QUERY_CAP = 48
    }
}
