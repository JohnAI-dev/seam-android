package dev.johnai.seam

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Sending a file (protocol/PROTOCOL.md, "Sending files"). Plain Kotlin: chunk sizes,
 * frame building inputs, and the offer/accept/chunk/done/result state machine.
 * No Android types, so this runs in JVM unit tests.
 *
 * The send path never buffers a whole file. [chunkLengths] only plans sizes; [FileUpload]
 * reads one chunk at a time.
 */
object FileSend {
    const val CHUNK_BYTES = Protocol.FILE_CHUNK_MAX_BYTES

    /**
     * Length of each chunk for a file of [size] bytes. The last may be shorter.
     * Empty when [size] is 0. Does not read or allocate file bytes.
     */
    fun chunkLengths(size: Long, chunkBytes: Int = CHUNK_BYTES): List<Int> {
        require(size >= 0L) { "size" }
        require(chunkBytes > 0) { "chunk" }
        val out = ArrayList<Int>()
        var left = size
        while (left > 0L) {
            val n = left.coerceAtMost(chunkBytes.toLong()).toInt()
            out.add(n)
            left -= n
        }
        return out
    }

    /** A mime worth putting on file_offer, or null to omit the field. */
    fun usableMime(mime: String?): String? {
        val raw = mime?.substringBefore(';')?.trim() ?: return null
        if (raw.isEmpty() || raw == "*/*" || raw.length > 127) return null
        if ('/' !in raw || raw.startsWith("/") || raw.endsWith("/")) return null
        if (raw.any { it.isISOControl() || it.isWhitespace() }) return null
        return raw
    }

    /**
     * A URI that may be passed to ContentResolver.openInputStream for a shared file.
     * file:// and other schemes are refused: that call ignores URI grants and can read
     * this app's private files. This app's own authorities are refused too, because the
     * owning app can open them without a grant from the caller.
     */
    fun mayOpenSharedUri(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        if (authority.isNullOrBlank()) return false
        if (ownPackage.isEmpty()) return true
        if (authority.equals(ownPackage, ignoreCase = true)) return false
        if (authority.startsWith("$ownPackage.", ignoreCase = true)) return false
        return true
    }

    /**
     * A share the sender actually granted. The system only grants URIs in the intent
     * data or ClipData when FLAG_GRANT_READ_URI_PERMISSION is set; an extra alone is not
     * a grant, and the flag does not restrict file://.
     */
    fun isAcceptedShareUri(
        scheme: String?,
        authority: String?,
        grantRead: Boolean,
        listedForGrant: Boolean,
        ownPackage: String,
    ): Boolean = grantRead && listedForGrant && mayOpenSharedUri(scheme, authority, ownPackage)

    /**
     * Whether an exported share target may upload this URI.
     * [callingHasRead] must be the sharing app (checkCallingUriPermission during an IPC, or
     * checkUriPermission of getLaunchedFromUid with pid [CALLER_PID_UNKNOWN]), never this
     * process: an exported activity already holds unrelated content URIs.
     * A denial from an identified uid is [callerKnown] — see [callerAccess]. Unknown
     * fail-opens only when the URI is not already held.
     */
    fun callerMayUpload(
        scheme: String?,
        authority: String?,
        grantRead: Boolean,
        listedForGrant: Boolean,
        ownPackage: String,
        callingHasRead: Boolean,
        callerKnown: Boolean,
        alreadyHeld: Boolean,
    ): Boolean {
        if (!isAcceptedShareUri(scheme, authority, grantRead, listedForGrant, ownPackage)) return false
        if (callingHasRead) return true
        if (callerKnown) return false
        return !alreadyHeld
    }

    /**
     * How [callerMayUpload] should see the sharing app.
     * [known] stays true when the caller was identified but cannot read: reporting that as
     * unknown fail-opens for a URI this app does not already hold. A grant observed outside
     * an IPC is ignored — that check is this process.
     */
    data class CallerAccess(val hasRead: Boolean, val known: Boolean)

    /**
     * pid for [android.content.Context.checkUriPermission] when the process id is unknown.
     * Never 0: pid 0 is a real process, so the uid check fails even when that uid can read,
     * and the caller looks unknown.
     */
    const val CALLER_PID_UNKNOWN = -1

    fun callerAccess(
        inIpc: Boolean,
        ipcGranted: Boolean,
        launchedFromUid: Int,
        launchedUidCanRead: Boolean,
        myUid: Int,
        invalidUid: Int = -1,
        rootUid: Int = 0,
        systemUid: Int = 1000,
    ): CallerAccess {
        if (inIpc) return CallerAccess(hasRead = ipcGranted, known = true)
        if (launchedFromUid == invalidUid || launchedFromUid == myUid ||
            launchedFromUid == rootUid || launchedFromUid == systemUid
        ) {
            return CallerAccess(hasRead = false, known = false)
        }
        return CallerAccess(hasRead = launchedUidCanRead, known = true)
    }

    /**
     * True when this app can read the provider without a per-URI grant from the sender.
     * A non-exported provider is not readable this way. An empty [readPermission] means the
     * provider does not require one, unless a matching path permission denies read.
     * A null entry in [matchingPathReadPermissions] does not grant read.
     */
    fun readableWithoutUriGrant(
        exported: Boolean,
        readPermission: String?,
        holds: (String) -> Boolean,
        matchingPathReadPermissions: List<String?>,
    ): Boolean {
        if (!exported) return false
        if (!readPermission.isNullOrEmpty() && holds(readPermission)) return true
        var pathDenied = false
        for (perm in matchingPathReadPermissions) {
            if (perm.isNullOrEmpty()) continue
            if (holds(perm)) return true
            pathDenied = true
        }
        return readPermission.isNullOrEmpty() && !pathDenied
    }

    /**
     * Authorities this app can read without a share grant because of a manifest permission.
     * Package visibility can hide the provider from resolveContentProvider; these are still
     * refused when the caller cannot be identified. Keep in step with the read permissions
     * in AndroidManifest.xml (contacts, call log, phone state).
     */
    fun authorityReadableByHeldPermission(
        authority: String?,
        holdsContacts: Boolean,
        holdsCallLog: Boolean,
        holdsPhoneState: Boolean,
    ): Boolean {
        if (authority.isNullOrBlank()) return false
        val a = authority.lowercase()
        if (holdsContacts && (a == "com.android.contacts" || a.startsWith("com.android.contacts."))) {
            return true
        }
        if (holdsCallLog && (a == "call_log" || a == "com.android.calllog" || a.startsWith("call_log."))) {
            return true
        }
        if (holdsPhoneState && (a == "telephony" || a.startsWith("telephony."))) return true
        return false
    }

    /**
     * Stable Cancel-action request code for [transfer]. Progress ticks must reuse this so
     * the notification does not allocate a new PendingIntent on every update.
     */
    fun cancelRequestCode(transfer: String): Int =
        CANCEL_REQUEST_BASE or (transfer.hashCode() and 0x0fff_ffff)

    private const val CANCEL_REQUEST_BASE = 0x5000_0000

    fun randomTransferId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val random = SecureRandom()
}

/**
 * One outgoing file. [Io.send] must write only when [sessionId] is still current
 * ([LinkClient.sendOn] / [LinkHub.sendOn]). The id is captured when file_offer is sent.
 * If it changes, the transfer fails and no further frame is written: a reconnect does
 * not continue the upload and does not get file_chunk, file_done, or file_cancel.
 *
 * Not thread-safe. The link service runs one upload at a time.
 */
class FileUpload(private val io: Io) {
    interface Io {
        fun sessionId(): Long?

        /** Write [frame] only on [sessionId]. False writes nothing. */
        fun send(sessionId: Long, frame: String): Boolean

        /**
         * Next reply for [transfer], or null on timeout, cancel, or a dead session.
         * May still return a late frame after the session has changed; the machine ignores it.
         */
        fun await(transfer: String, sessionId: Long, timeoutMs: Long): String?

        fun isCancelled(): Boolean
        fun prepare(transfer: String)
        fun finish(transfer: String)
    }

    /** Read up to [len] bytes into [buf] at [off]. -1 at EOF. 0 is treated as a short read. */
    interface ByteSource {
        fun read(buf: ByteArray, off: Int, len: Int): Int
        fun close()
    }

    enum class Status { SENT, REJECTED, CANCELLED, FAILED }

    data class Result(val status: Status, val detail: String)

    fun send(
        transfer: String,
        name: String,
        size: Long,
        mime: String?,
        open: () -> ByteSource,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        chunkBytes: Int = FileSend.CHUNK_BYTES,
    ): Result {
        cancelSent = false
        if (!isTransferId(transfer)) return Result(Status.FAILED, ERR_FAILED)
        if (size < 0L) return Result(Status.FAILED, ERR_READ)
        if (size > Protocol.FILE_MAX_BYTES) return Result(Status.FAILED, ERR_TOO_LARGE)
        if (io.isCancelled()) return Result(Status.CANCELLED, ERR_CANCELLED)
        io.prepare(transfer)
        try {
            return run(transfer, name, size, mime, open, onProgress, chunkBytes.coerceIn(1, FileSend.CHUNK_BYTES))
        } finally {
            try {
                io.finish(transfer)
            } catch (_: Exception) {
            }
        }
    }

    private fun run(
        transfer: String,
        name: String,
        size: Long,
        mime: String?,
        open: () -> ByteSource,
        onProgress: (Long, Long) -> Unit,
        limit: Int,
    ): Result {
        val captured = io.sessionId() ?: return Result(Status.FAILED, ERR_NOT_CONNECTED)
        val offer = Protocol.fileOffer(
            transfer,
            FileTransfer.sanitizeName(name),
            size,
            FileSend.usableMime(mime),
        )
        if (!write(captured, offer)) return Result(Status.FAILED, ERR_NOT_CONNECTED)

        val accept = awaitSameSession(captured, transfer, ACCEPT_TIMEOUT_MS)
            ?: return failWait(captured, transfer)
        when (Protocol.parse(accept)?.opt("type")) {
            "file_reject" -> {
                return Result(Status.REJECTED, Protocol.parseFileReject(accept)?.reason ?: ERR_REJECTED)
            }
            "file_cancel" -> return Result(Status.CANCELLED, ERR_CANCELLED)
            "file_accept" -> {
                if (Protocol.parseFileAccept(accept) != transfer) {
                    writeCancel(captured, transfer)
                    return Result(Status.FAILED, ERR_FAILED)
                }
            }
            else -> {
                writeCancel(captured, transfer)
                return Result(Status.FAILED, ERR_FAILED)
            }
        }
        if (io.sessionId() != captured) return Result(Status.FAILED, ERR_NOT_CONNECTED)
        if (io.isCancelled()) {
            writeCancel(captured, transfer)
            return Result(Status.CANCELLED, ERR_CANCELLED)
        }

        val source = try {
            open()
        } catch (_: Exception) {
            writeCancel(captured, transfer)
            return Result(Status.FAILED, ERR_READ)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (size > 0L) {
                val buf = ByteArray(limit)
                var sent = 0L
                var seq = 0L
                while (sent < size) {
                    if (io.sessionId() != captured) return Result(Status.FAILED, ERR_NOT_CONNECTED)
                    if (io.isCancelled()) {
                        writeCancel(captured, transfer)
                        return Result(Status.CANCELLED, ERR_CANCELLED)
                    }
                    val want = (size - sent).coerceAtMost(limit.toLong()).toInt()
                    if (!readExactly(source, buf, want)) {
                        if (io.sessionId() != captured) return Result(Status.FAILED, ERR_NOT_CONNECTED)
                        writeCancel(captured, transfer)
                        return Result(Status.FAILED, ERR_READ)
                    }
                    digest.update(buf, 0, want)
                    val payload = if (want == buf.size) buf else buf.copyOf(want)
                    if (!write(captured, Protocol.fileChunk(transfer, seq, payload))) {
                        return Result(Status.FAILED, ERR_NOT_CONNECTED)
                    }
                    sent += want
                    seq += 1L
                    try {
                        onProgress(sent, size)
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            try {
                source.close()
            } catch (_: Exception) {
            }
        }

        if (io.sessionId() != captured) return Result(Status.FAILED, ERR_NOT_CONNECTED)
        if (io.isCancelled()) {
            writeCancel(captured, transfer)
            return Result(Status.CANCELLED, ERR_CANCELLED)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!write(captured, Protocol.fileDone(transfer, hash))) {
            return Result(Status.FAILED, ERR_NOT_CONNECTED)
        }

        val resultLine = awaitSameSession(captured, transfer, RESULT_TIMEOUT_MS)
            ?: return failWait(captured, transfer)
        if (Protocol.parseFileCancel(resultLine) == transfer) {
            return Result(Status.CANCELLED, ERR_CANCELLED)
        }
        val result = Protocol.parseFileResult(resultLine)
        if (result == null || result.transfer != transfer) {
            writeCancel(captured, transfer)
            return Result(Status.FAILED, ERR_FAILED)
        }
        if (!result.ok) return Result(Status.FAILED, result.error ?: ERR_FAILED)
        return Result(Status.SENT, "")
    }

    private enum class WaitKind { OK, LOST, CANCELLED, TIMEOUT }

    private var outcome = WaitKind.LOST
    private var cancelSent = false

    private fun awaitSameSession(captured: Long, transfer: String, timeoutMs: Long): String? {
        val line = try {
            io.await(transfer, captured, timeoutMs)
        } catch (_: Exception) {
            null
        }
        if (io.sessionId() != captured) {
            outcome = WaitKind.LOST
            return null
        }
        if (io.isCancelled()) {
            outcome = WaitKind.CANCELLED
            return null
        }
        if (line == null) {
            outcome = WaitKind.TIMEOUT
            return null
        }
        outcome = WaitKind.OK
        return line
    }

    /** LOST sends nothing else. Cancel and timeout tell the computer, on the same session only. */
    private fun failWait(captured: Long, transfer: String): Result = when (outcome) {
        WaitKind.CANCELLED -> {
            writeCancel(captured, transfer)
            Result(Status.CANCELLED, ERR_CANCELLED)
        }
        WaitKind.TIMEOUT -> {
            writeCancel(captured, transfer)
            Result(Status.FAILED, ERR_TIMEOUT)
        }
        WaitKind.LOST, WaitKind.OK -> Result(Status.FAILED, ERR_NOT_CONNECTED)
    }

    private fun write(session: Long, frame: String): Boolean {
        if (io.sessionId() != session) return false
        return try {
            io.send(session, frame)
        } catch (_: Exception) {
            false
        }
    }

    private fun writeCancel(session: Long, transfer: String) {
        if (cancelSent) return
        cancelSent = true
        write(session, Protocol.fileCancel(transfer))
    }

    private fun readExactly(source: ByteSource, buf: ByteArray, want: Int): Boolean {
        var off = 0
        while (off < want) {
            val n = try {
                source.read(buf, off, want - off)
            } catch (_: Exception) {
                return false
            }
            if (n <= 0 || n > want - off) return false
            off += n
        }
        return true
    }

    private fun isTransferId(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    companion object {
        const val ERR_NOT_CONNECTED = "not connected"
        const val ERR_TIMEOUT = "timed out"
        const val ERR_READ = "could not read"
        const val ERR_TOO_LARGE = "file is too large"
        const val ERR_CANCELLED = "cancelled"
        const val ERR_REJECTED = "rejected"
        const val ERR_FAILED = "transfer failed"
        const val ACCEPT_TIMEOUT_MS = 60_000L
        const val RESULT_TIMEOUT_MS = 180_000L
    }
}

/**
 * Replies to an upload this phone started. The reader thread [deliver]s; the upload thread
 * [await]s. A changed session unblocks the wait so the upload can fail without further frames.
 */
class FileResponseQueue {
    private class Slot {
        val frames = ArrayDeque<String>()
        val lock = Object()
        @Volatile var cancelled = false

        fun push(line: String) {
            synchronized(lock) {
                frames.add(line)
                lock.notifyAll()
            }
        }

        fun cancel() {
            synchronized(lock) {
                cancelled = true
                lock.notifyAll()
            }
        }

        fun await(sessionId: Long, sessionNow: () -> Long?, timeoutMs: Long): String? {
            val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
            synchronized(lock) {
                while (true) {
                    if (cancelled) return null
                    if (sessionNow() != sessionId) return null
                    frames.poll()?.let { return it }
                    val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (leftMs <= 0L) return null
                    try {
                        lock.wait(leftMs.coerceAtMost(200L))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    fun arm(transfer: String) {
        slots[transfer] = Slot()
    }

    fun finish(transfer: String) {
        slots.remove(transfer)
    }

    fun cancel(transfer: String) {
        slots[transfer]?.cancel()
    }

    /** True if this frame belonged to an armed upload and was queued for it. */
    fun deliver(line: String): Boolean {
        val type = Protocol.parse(line)?.opt("type") ?: return false
        val id = when (type) {
            "file_accept" -> Protocol.parseFileAccept(line)
            "file_reject" -> Protocol.parseFileReject(line)?.transfer
            "file_result" -> Protocol.parseFileResult(line)?.transfer
            "file_cancel" -> Protocol.parseFileCancel(line)
            else -> return false
        } ?: return false
        val slot = slots[id] ?: return false
        slot.push(line)
        return true
    }

    fun await(transfer: String, sessionId: Long, sessionNow: () -> Long?, timeoutMs: Long): String? {
        val slot = slots[transfer] ?: return null
        return slot.await(sessionId, sessionNow, timeoutMs)
    }
}

/** A named unit of work so shutting the executor down can name what never started. */
open class NamedUpload(val name: String, private val body: () -> Unit) : Runnable {
    open override fun run() = body()
}

/**
 * Submits [NamedUpload]s to a single-thread executor. [shutdownNow] returns the names of
 * tasks that had not started; it does not use a hand-rolled worker or a stopped flag.
 * A submit after shutdown returns false so the caller can still report the file.
 */
object UploadJobs {
    fun submit(executor: ExecutorService, job: NamedUpload): Boolean =
        try {
            executor.execute(job)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    fun shutdownNow(executor: ExecutorService): List<String> =
        executor.shutdownNow().mapNotNull { (it as? NamedUpload)?.name }
}
