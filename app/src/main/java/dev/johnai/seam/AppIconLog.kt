package dev.johnai.seam

/**
 * Which app icons have been sent on the current connection
 * (protocol/PROTOCOL.md, "App icons").
 *
 * An icon is sent at most once per package per connection, before that
 * package's first forwarded notification. [sessionId] is the link session:
 * a different id, including null while disconnected, is a new connection
 * and clears the set.
 *
 * [sendOnce] claims the package before loading the PNG, so a second caller
 * cannot also send. The package is remembered only when [send] returns true
 * and this log is still on that session. A failed load, a false return, a throw,
 * or a session that has already moved on is not remembered. [send] is not called
 * once this log has moved on. The caller must also write nothing if the link
 * itself has moved: use [iconSendAllowed] and [LinkHub.sendOn] with the claimed
 * id, never the id of whatever connection is live now. A completion for a session
 * that is no longer current does not suppress the new connection.
 *
 * [queueIfNeeded] waits out an in-flight send before returning, so the caller
 * can queue the notification on the same path and it cannot overtake the icon.
 * It returns true when this connection has the icon (just queued, or already
 * sent). A true result is not permission to write on a newer live session.
 * An interrupt does not end that wait.
 *
 * [forwardWithIcon] sends the icon and the notification as one session-scoped
 * pair. Both frames use the captured session id ([LinkHub.sendOn], never the
 * live-connection outbox). If the live session changes before both are queued,
 * the notification is not written on the new id until this retries and the icon
 * is queued there first.
 *
 * No Android types, so this runs in JVM unit tests.
 */
class AppIconLog {
    private val lock = java.lang.Object()
    private val sent = HashSet<String>()
    private val inflight = HashSet<String>()
    private var aligned = false
    private var session: Long? = null

    /**
     * True when [app] still needs an icon on [sessionId].
     * False while a send is already in flight, so a second caller does not also send.
     */
    fun needsSend(sessionId: Long?, app: String): Boolean {
        if (app.isEmpty()) return false
        return synchronized(lock) {
            align(sessionId)
            app !in sent && app !in inflight
        }
    }

    /**
     * Build [app]'s icon and hand the frame to [send], at most once per [sessionId].
     *
     * Returns false without calling [loadPng] when the package was already sent
     * or another caller already claimed it. Returns false without calling [send]
     * when this log has already moved to another connection. Returns true only
     * when [send] returns true and this connection is still current.
     * [loadPng] and [send] run outside the lock; the claim is held until they finish.
     * [send] must not call [sendOnce] again for this same [sessionId] on this thread,
     * and must not write if [iconSendAllowed] is false for the claimed id.
     */
    fun sendOnce(
        sessionId: Long?,
        app: String,
        loadPng: () -> ByteArray?,
        send: (String) -> Boolean,
    ): Boolean {
        if (!claim(sessionId, app)) return false
        var queued = false
        try {
            if (!isSession(sessionId)) return false
            val frame = loadPng()?.let { Protocol.appIcon(app, it) } ?: return false
            if (!isSession(sessionId)) return false
            queued = send(frame) && isSession(sessionId)
            return queued
        } finally {
            if (queued) commit(sessionId, app) else release(sessionId, app)
        }
    }

    /**
     * Queue [app]'s icon at most once, then return whether [sessionId] has it.
     *
     * True if this call queued the icon for [sessionId], or it was already sent
     * on that connection (including an in-flight send this waited out). False if
     * it was not queued for [sessionId]: the load failed, [send] returned false,
     * or the log moved to another connection. A true result does not authorize a
     * write on a newer live session — check that id before writing.
     *
     * If another caller is already sending this package on [sessionId], this
     * waits until that send has queued or given up. The caller then queues the
     * notification on the same path, so it cannot be queued first.
     * [send] must not call back into this log for the same [sessionId] on this thread.
     * An interrupt does not end the wait.
     */
    fun queueIfNeeded(
        sessionId: Long?,
        app: String,
        loadPng: () -> ByteArray?,
        send: (String) -> Boolean,
    ): Boolean {
        if (app.isEmpty()) return false
        var failed: Exception? = null
        try {
            sendOnce(sessionId, app, loadPng, send)
        } catch (t: Exception) {
            failed = t
        } finally {
            awaitIdle(sessionId, app)
        }
        failed?.let { throw it }
        return wasSent(sessionId, app)
    }

    /**
     * Queue [app]'s icon and [notification] on one connection.
     *
     * Captures the live session, queues the icon through [send] on that id, then
     * queues [notification] through [send] on that same id. [send] must write only
     * while the id is still current ([iconSendAllowed], [LinkHub.sendOn]) — never
     * onto whatever connection is live now.
     *
     * If [sessionNow] changes before both are queued, the notification is not
     * written for the new id. The loop retries both there, so a notification is
     * not emitted on a connection unless this app's icon was queued on it first
     * (or was already sent on it). A missing icon does not drop [notification],
     * but only while [sessionNow] is still the captured id.
     *
     * Returns true if [notification] was written and that session was still current.
     */
    fun forwardWithIcon(
        app: String,
        notification: String,
        sessionNow: () -> Long?,
        loadPng: () -> ByteArray?,
        send: (sessionId: Long, frame: String) -> Boolean,
    ): Boolean {
        var attempts = 0
        while (attempts++ < FORWARD_ATTEMPTS) {
            val sessionId = sessionNow() ?: return false
            val iconReady = try {
                queueIfNeeded(sessionId, app, loadPng) { frame ->
                    if (sessionNow() != sessionId) false
                    else send(sessionId, frame)
                }
            } catch (_: Exception) {
                false
            }
            // Reconnect during load: do not emit the notification on the new session.
            // The next attempt queues the icon there first.
            if (sessionNow() != sessionId) continue
            // iconReady: queued or already sent. A missing icon still forwards, but
            // only because this session is still current — never onto a later one.
            if (!iconReady && sessionNow() != sessionId) continue
            if (sessionNow() != sessionId) continue
            val wrote = try {
                send(sessionId, notification)
            } catch (_: Exception) {
                false
            }
            if (sessionNow() != sessionId) continue
            return wrote
        }
        return false
    }

    /**
     * Block until [app] is not in flight on [sessionId].
     * An interrupt does not return early: the notification must stay behind the icon.
     * The interrupt flag is restored after the wait.
     */
    private fun awaitIdle(sessionId: Long?, app: String) {
        if (app.isEmpty()) return
        var interrupted = false
        try {
            synchronized(lock) {
                while (aligned && session == sessionId && app in inflight) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun claim(sessionId: Long?, app: String): Boolean {
        if (app.isEmpty()) return false
        return synchronized(lock) {
            align(sessionId)
            if (app in sent || app in inflight) {
                false
            } else {
                inflight.add(app)
                true
            }
        }
    }

    /** Remember a queued send. A stale [sessionId] is ignored. Wakes [awaitIdle]. */
    private fun commit(sessionId: Long?, app: String) {
        synchronized(lock) {
            if (aligned && session == sessionId) {
                inflight.remove(app)
                sent.add(app)
            }
            lock.notifyAll()
        }
    }

    /** Drop an in-flight claim so a later notification can retry. Stale ids are ignored. */
    private fun release(sessionId: Long?, app: String) {
        synchronized(lock) {
            if (aligned && session == sessionId) inflight.remove(app)
            lock.notifyAll()
        }
    }

    private fun align(sessionId: Long?) {
        if (aligned && session == sessionId) return
        sent.clear()
        inflight.clear()
        session = sessionId
        aligned = true
        lock.notifyAll()
    }

    private fun isSession(sessionId: Long?): Boolean = synchronized(lock) {
        aligned && session == sessionId
    }

    /** True when [app] was queued on [sessionId] and that is still this log's connection. */
    private fun wasSent(sessionId: Long?, app: String): Boolean = synchronized(lock) {
        aligned && session == sessionId && app in sent
    }

    private companion object {
        /** Reconnects during one icon load; bounds the retry so a flapping link cannot spin. */
        const val FORWARD_ATTEMPTS = 8
    }
}

/**
 * Whether an icon claimed for [captured] may still be written.
 * [live] is [LinkHub.currentSessionId]. A null or replaced connection must not
 * receive the frame. Never write with [live] in place of [captured]: that would
 * deliver the icon on the next connection.
 */
fun iconSendAllowed(captured: Long?, live: Long?): Boolean =
    captured != null && live == captured

/**
 * Square sides to encode, largest first, ending at 1.
 * Each step is 3/4 of the last and always decreases, so a short fixed cap
 * cannot stop while the bitmap is still larger than 1×1.
 */
fun iconSidesToTry(startSide: Int): List<Int> {
    val out = ArrayList<Int>()
    var side = if (startSide < 1) 1 else startSide
    while (true) {
        out.add(side)
        if (side <= 1) return out
        val scaled = side * 3 / 4
        val next = if (scaled < side) scaled.coerceAtLeast(1) else side - 1
        if (next >= side) return out
        side = next
    }
}
