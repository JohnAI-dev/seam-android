package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Once per app per connection, and the app_icon frame. See protocol/PROTOCOL.md, "App icons". */
class AppIconTest {
    @Test
    fun limitsMatchTheProtocol() {
        assertEquals(16 * 1024, Protocol.APP_ICON_MAX_BYTES)
        assertEquals(96, Protocol.APP_ICON_SIDE)
    }

    @Test
    fun frameIsBase64PngWithNoNewlines() {
        val raw = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0xfb.toByte(), 0xff.toByte(), 0xbf.toByte(),
        )
        val frame = checkNotNull(Protocol.appIcon("com.example.app", raw))
        assertFalse(frame.contains("\n"))
        val o = JSONObject(frame)
        assertEquals("app_icon", o.getString("type"))
        assertEquals("com.example.app", o.getString("app"))
        assertEquals(3, o.length())
        val icon = o.getString("icon")
        assertEquals(Base64.getEncoder().encodeToString(raw), icon)
        assertTrue(raw.contentEquals(Base64.getDecoder().decode(icon)))
        assertNull(Protocol.dismissId(frame))
        assertFalse(Protocol.isRing(frame))
        assertFalse(Protocol.isRingStop(frame))
        assertNull(Protocol.parsePhotoRequest(frame))
        assertNull(Protocol.clipboardText(frame))
    }

    @Test
    fun frameRejectsEmptyAndOversized() {
        assertNull(Protocol.appIcon("", byteArrayOf(1)))
        assertNull(Protocol.appIcon("com.example", ByteArray(0)))
        assertNull(Protocol.appIcon("com.example", ByteArray(Protocol.APP_ICON_MAX_BYTES + 1)))
        val exact = ByteArray(Protocol.APP_ICON_MAX_BYTES) { 7 }
        val frame = checkNotNull(Protocol.appIcon("com.example", exact))
        assertEquals(exact.size, Base64.getDecoder().decode(JSONObject(frame).getString("icon")).size)
    }

    @Test
    fun sentOncePerAppUntilReconnect() {
        val log = AppIconLog()
        val first = checkNotNull(queued(log, 1L, "com.chat", byteArrayOf(1, 2, 3)))
        assertEquals("app_icon", JSONObject(first).getString("type"))
        assertEquals("com.chat", JSONObject(first).getString("app"))
        var loads = 0
        assertFalse(log.sendOnce(1L, "com.chat", { loads++; byteArrayOf(9) }) { true })
        assertEquals("already sent: do not rebuild the icon", 0, loads)

        val other = checkNotNull(queued(log, 1L, "com.mail", byteArrayOf(4)))
        assertEquals("com.mail", JSONObject(other).getString("app"))

        // A new session is a reconnect, even without a disconnected gap.
        val again = checkNotNull(queued(log, 2L, "com.chat", byteArrayOf(1)))
        assertEquals("com.chat", JSONObject(again).getString("app"))
        assertNotNull(queued(log, 2L, "com.mail", byteArrayOf(4)))
        assertNull(queued(log, 2L, "com.chat", byteArrayOf(1)))
        assertNull(queued(log, 2L, "com.mail", byteArrayOf(4)))
    }

    @Test
    fun disconnectedGenerationResetsOnTheNextSession() {
        val log = AppIconLog()
        assertNotNull(queued(log, 3L, "com.a"))
        assertNotNull(queued(log, null, "com.a"))
        assertNull(queued(log, null, "com.a"))
        assertNotNull(queued(log, 4L, "com.a"))
        assertNull(queued(log, 4L, "com.a"))
    }

    @Test
    fun failedIconIsRetriedAndNotTreatedAsSent() {
        val log = AppIconLog()
        assertFalse(log.sendOnce(1L, "com.a", { null }) { true })
        assertFalse(log.sendOnce(1L, "com.a", { ByteArray(0) }) { true })
        assertFalse(log.sendOnce(1L, "com.a", { ByteArray(Protocol.APP_ICON_MAX_BYTES + 1) }) { true })
        assertNotNull(queued(log, 1L, "com.a"))
        assertNull(queued(log, 1L, "com.a"))
    }

    @Test
    fun failedQueueIsRetriedAndNotTreatedAsSent() {
        val log = AppIconLog()
        var loads = 0
        assertFalse(log.sendOnce(1L, "com.a", { loads++; byteArrayOf(1) }) { false })
        assertEquals(1, loads)
        assertTrue(log.needsSend(1L, "com.a"))
        assertTrue(log.sendOnce(1L, "com.a", { loads++; byteArrayOf(1) }) { true })
        assertEquals(2, loads)
        assertFalse(log.needsSend(1L, "com.a"))
        assertFalse(log.sendOnce(1L, "com.a", { loads++; byteArrayOf(1) }) { true })
        assertEquals("remembered only after the queue accepts", 2, loads)
    }

    @Test
    fun sendThrowReleasesTheClaim() {
        val log = AppIconLog()
        try {
            log.sendOnce(1L, "com.a", { byteArrayOf(1) }) { throw IllegalStateException("queue") }
            throw AssertionError("send failure must surface")
        } catch (e: IllegalStateException) {
            assertEquals("queue", e.message)
        }
        assertTrue(log.needsSend(1L, "com.a"))
        assertNotNull(queued(log, 1L, "com.a"))
    }

    @Test
    fun loadThrowReleasesTheClaim() {
        val log = AppIconLog()
        try {
            log.sendOnce(1L, "com.a", { throw IllegalStateException("icon") }) { true }
            throw AssertionError("load failure must surface")
        } catch (e: IllegalStateException) {
            assertEquals("icon", e.message)
        }
        assertTrue(log.needsSend(1L, "com.a"))
        assertNotNull(queued(log, 1L, "com.a"))
    }

    @Test
    fun emptyPackageIsNeverSent() {
        val log = AppIconLog()
        var loads = 0
        assertFalse(log.sendOnce(1L, "", { loads++; byteArrayOf(1) }) { true })
        assertEquals(0, loads)
        assertFalse(log.needsSend(1L, ""))
    }

    @Test
    fun needsSendDoesNotRememberUntilQueued() {
        val log = AppIconLog()
        assertTrue(log.needsSend(1L, "com.a"))
        assertTrue(log.needsSend(1L, "com.a"))
        var during: Boolean? = null
        assertTrue(
            log.sendOnce(1L, "com.a", { byteArrayOf(1) }) {
                during = log.needsSend(1L, "com.a")
                true
            },
        )
        assertEquals(false, during)
        assertFalse(log.needsSend(1L, "com.a"))
        assertTrue(log.needsSend(1L, "com.b"))
    }

    @Test
    fun remembersEveryAppOnTheConnection() {
        val log = AppIconLog()
        for (i in 0 until 40) {
            assertNotNull(queued(log, 1L, "com.app$i"))
        }
        for (i in 0 until 40) {
            assertFalse(log.needsSend(1L, "com.app$i"))
        }
        assertTrue(log.needsSend(1L, "com.other"))
        assertTrue(log.needsSend(2L, "com.app0"))
    }

    @Test
    fun staleFailureDoesNotSuppressTheNewConnection() {
        val log = AppIconLog()
        assertFalse(
            log.sendOnce(1L, "com.a", { byteArrayOf(1) }) {
                assertTrue(log.sendOnce(2L, "com.a", { byteArrayOf(2) }) { true })
                false
            },
        )
        assertFalse(log.needsSend(2L, "com.a"))
    }

    @Test
    fun staleCompletionDoesNotSuppressTheNewConnection() {
        val log = AppIconLog()
        assertFalse(
            "a callback that returns after the log moved on is not a successful send",
            log.sendOnce(1L, "com.a", { byteArrayOf(1) }) {
                assertTrue(log.sendOnce(2L, "com.a", { byteArrayOf(2) }) { true })
                true
            },
        )
        assertFalse(log.needsSend(2L, "com.a"))
    }

    @Test(timeout = 10000)
    fun inFlightClaimBlocksASecondSend() {
        val log = AppIconLog()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Boolean> {
                log.sendOnce(1L, "com.a", {
                    loads.incrementAndGet()
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) error("not released")
                    byteArrayOf(1)
                }) { true }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(log.sendOnce(1L, "com.a", { loads.incrementAndGet(); byteArrayOf(9) }) { true })
            assertEquals(1, loads.get())
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertEquals(1, loads.get())
            assertFalse(log.needsSend(1L, "com.a"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun iconFrameIsQueuedBeforeTheNotificationFrame() {
        val log = AppIconLog()
        val sent = mutableListOf<String>()
        assertTrue(
            log.queueIfNeeded(7L, "com.chat", { byteArrayOf(1, 2, 3) }) { frame ->
                sent.add(frame)
                true
            },
        )
        sent.add(Protocol.notification("k", "com.chat", "Chat", "Hi", "There", 9L))
        assertEquals(2, sent.size)
        val icon = JSONObject(sent[0])
        assertEquals("app_icon", icon.getString("type"))
        assertEquals("com.chat", icon.getString("app"))
        assertEquals("notification", JSONObject(sent[1]).getString("type"))
        assertEquals("com.chat", JSONObject(sent[1]).getString("app"))
        assertTrue(
            "already sent counts as queued for this connection",
            log.queueIfNeeded(7L, "com.chat", { byteArrayOf(9) }) { sent.add("again"); true },
        )
        sent.add("note-2")
        assertFalse(sent.contains("again"))
        assertEquals("note-2", sent.last())
    }

    @Test
    fun failedIconQueueDoesNotBlockTheNotificationOrStick() {
        val log = AppIconLog()
        val sent = mutableListOf<String>()
        assertFalse(log.queueIfNeeded(1L, "com.a", { byteArrayOf(1) }) { false })
        sent.add("note")
        assertEquals(listOf("note"), sent)
        assertTrue(log.needsSend(1L, "com.a"))
        assertTrue(
            log.queueIfNeeded(1L, "com.a", { byteArrayOf(1) }) { frame ->
                sent.add(frame)
                true
            },
        )
        sent.add("note-2")
        assertEquals("app_icon", JSONObject(sent[1]).getString("type"))
        assertEquals("note-2", sent[2])
    }

    @Test
    fun queueIfNeededReleasesAndRethrows() {
        val log = AppIconLog()
        try {
            log.queueIfNeeded(1L, "com.a", { throw IllegalStateException("icon") }) { true }
            throw AssertionError("load failure must surface")
        } catch (e: IllegalStateException) {
            assertEquals("icon", e.message)
        }
        assertTrue(log.needsSend(1L, "com.a"))
        assertNotNull(queued(log, 1L, "com.a"))
    }

    @Test(timeout = 10000)
    fun notificationStaysBehindAnInFlightIcon() {
        val log = AppIconLog()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Unit> {
                log.queueIfNeeded(1L, "com.a", {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) error("not released")
                    byteArrayOf(1)
                }) {
                    order.add("icon")
                    true
                }
                order.add("note-1")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Unit> {
                log.queueIfNeeded(1L, "com.a", { byteArrayOf(9) }) {
                    order.add("icon-2")
                    true
                }
                order.add("note-2")
            }
            Thread.sleep(150)
            assertFalse(order.contains("note-2"))
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals("icon", order.first())
            assertTrue(order.indexOf("icon") < order.indexOf("note-1"))
            assertTrue(order.indexOf("icon") < order.indexOf("note-2"))
            assertFalse(order.contains("icon-2"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun iconShrinksUntilOnePixelPastAShortCap() {
        val sides = iconSidesToTry(Protocol.APP_ICON_SIDE)
        assertEquals(96, sides.first())
        assertEquals(1, sides.last())
        assertTrue("must keep shrinking past a short fixed cap", sides.size > 9)
        for (i in 1 until sides.size) {
            assertTrue("${sides[i - 1]} -> ${sides[i]}", sides[i] < sides[i - 1])
        }
        assertEquals(listOf(1), iconSidesToTry(1))
        assertEquals(listOf(1), iconSidesToTry(0))
    }

    @Test
    fun iconSendAllowedOnlyForTheCapturedConnection() {
        assertFalse(iconSendAllowed(null, null))
        assertFalse(iconSendAllowed(null, 1L))
        assertFalse(iconSendAllowed(1L, null))
        assertFalse(iconSendAllowed(1L, 2L))
        assertFalse(iconSendAllowed(2L, 1L))
        assertTrue(iconSendAllowed(1L, 1L))
    }

    @Test
    fun reconnectBeforeSendDoesNotWriteTheCapturedIcon() {
        var live: Long? = 1L
        val log = AppIconLog()
        val written = mutableListOf<Long>()
        assertFalse(
            log.sendOnce(1L, "com.a", {
                live = 2L
                byteArrayOf(1, 2, 3)
            }) { frame ->
                if (!iconSendAllowed(1L, live)) {
                    false
                } else {
                    written.add(1L)
                    assertEquals("app_icon", JSONObject(frame).getString("type"))
                    true
                }
            },
        )
        assertTrue("stale callback must not write the next connection", written.isEmpty())
        assertTrue(log.needsSend(1L, "com.a"))
        assertTrue(
            log.sendOnce(2L, "com.a", { byteArrayOf(4) }) {
                if (!iconSendAllowed(2L, live)) {
                    false
                } else {
                    written.add(2L)
                    true
                }
            },
        )
        assertEquals(listOf(2L), written)
        assertFalse(log.needsSend(2L, "com.a"))
    }

    @Test
    fun staleInFlightSendIsNotDeliveredOnTheNextConnection() {
        val log = AppIconLog()
        var calls = 0
        assertFalse(
            log.sendOnce(1L, "com.a", {
                assertTrue(log.sendOnce(2L, "com.a", { byteArrayOf(2) }) { true })
                byteArrayOf(1)
            }) {
                calls++
                true
            },
        )
        assertEquals("log moved on during load: do not transmit", 0, calls)
        assertFalse(log.needsSend(2L, "com.a"))
    }

    @Test(timeout = 10000)
    fun interruptDoesNotLetTheNotificationPassTheIcon() {
        val log = AppIconLog()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Unit> {
                log.queueIfNeeded(1L, "com.a", {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) error("not released")
                    byteArrayOf(1)
                }) {
                    order.add("icon")
                    true
                }
                order.add("note-1")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Boolean> {
                Thread.currentThread().interrupt()
                log.queueIfNeeded(1L, "com.a", { byteArrayOf(9) }) {
                    order.add("icon-2")
                    true
                }
                order.add("note-2")
                Thread.currentThread().isInterrupted
            }
            Thread.sleep(200)
            assertFalse("interrupt must not queue the notification ahead of the icon", order.contains("note-2"))
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertTrue("interrupt flag is restored after the wait", second.get(5, TimeUnit.SECONDS))
            assertEquals("icon", order.first())
            assertTrue(order.indexOf("icon") < order.indexOf("note-1"))
            assertTrue(order.indexOf("icon") < order.indexOf("note-2"))
            assertFalse(order.contains("icon-2"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun forwardWithIconQueuesIconThenNotificationOnThatSession() {
        val log = AppIconLog()
        val written = mutableListOf<Pair<Long, String>>()
        val note = Protocol.notification("k", "com.chat", "Chat", "Hi", "There", 9L)
        var loads = 0
        assertTrue(
            log.forwardWithIcon("com.chat", note, { 5L }, {
                loads++
                byteArrayOf(1, 2, 3)
            }) { sessionId, frame ->
                written.add(sessionId to frame)
                true
            },
        )
        assertEquals(1, loads)
        assertEquals(
            listOf("app_icon", "notification"),
            written.map { JSONObject(it.second).getString("type") },
        )
        assertTrue(written.all { it.first == 5L })
        assertIconBeforeNote(written)
        val second = Protocol.notification("k2", "com.chat", "Chat", "Yo", "There", 10L)
        assertTrue(
            log.forwardWithIcon("com.chat", second, { 5L }, {
                loads++
                byteArrayOf(9)
            }) { sessionId, frame ->
                written.add(sessionId to frame)
                true
            },
        )
        assertEquals("already sent: do not rebuild", 1, loads)
        assertEquals(3, written.size)
        assertEquals("notification", JSONObject(written.last().second).getString("type"))
        assertEquals(5L, written.last().first)
    }

    @Test
    fun sessionChangeDuringLoadDoesNotEmitTheNotificationOnTheNewSessionUnlessTheIconIsFirst() {
        val log = AppIconLog()
        var live: Long? = 1L
        val written = mutableListOf<Pair<Long, String>>()
        var loads = 0
        val note = Protocol.notification("k", "com.chat", "Chat", "Hi", "There", 9L)
        assertTrue(
            log.forwardWithIcon(
                "com.chat",
                note,
                sessionNow = { live },
                loadPng = {
                    loads++
                    live = 2L
                    byteArrayOf(1, 2, 3)
                },
                send = { sessionId, frame ->
                    if (!iconSendAllowed(sessionId, live)) {
                        false
                    } else {
                        written.add(sessionId to frame)
                        true
                    }
                },
            ),
        )
        assertTrue("retries on the connection that is actually live", loads >= 2)
        assertTrue(written.none { it.first == 1L })
        assertEquals(
            listOf("app_icon", "notification"),
            written.map { JSONObject(it.second).getString("type") },
        )
        assertTrue(written.all { it.first == 2L })
        assertEquals("com.chat", JSONObject(written[0].second).getString("app"))
        assertEquals("notification", JSONObject(written[1].second).getString("type"))
        assertIconBeforeNote(written)
    }

    @Test
    fun sessionChangeAfterIconDoesNotPutTheNotificationOnTheNewSessionFirst() {
        val log = AppIconLog()
        var live: Long? = 1L
        val written = mutableListOf<Pair<Long, String>>()
        val note = Protocol.notification("k", "com.chat", "Chat", "Hi", "There", 9L)
        assertTrue(
            log.forwardWithIcon(
                "com.chat",
                note,
                sessionNow = { live },
                loadPng = { byteArrayOf(4, 5) },
                send = { sessionId, frame ->
                    if (!iconSendAllowed(sessionId, live)) {
                        false
                    } else {
                        written.add(sessionId to frame)
                        if (sessionId == 1L && JSONObject(frame).getString("type") == "app_icon") {
                            live = 2L
                        }
                        true
                    }
                },
            ),
        )
        assertTrue(
            written.none {
                it.first == 1L && JSONObject(it.second).getString("type") == "notification"
            },
        )
        val onNew = written.filter { it.first == 2L }
        assertEquals("app_icon", JSONObject(onNew.first().second).getString("type"))
        assertTrue(onNew.any { JSONObject(it.second).getString("type") == "notification" })
        assertIconBeforeNote(written)
    }

    @Test
    fun queueIfNeededReportsTheCapturedSessionNotTheNextOne() {
        val log = AppIconLog()
        var live: Long? = 1L
        assertFalse(
            log.queueIfNeeded(1L, "com.a", {
                live = 2L
                byteArrayOf(1)
            }) { iconSendAllowed(1L, live) },
        )
        assertTrue(log.needsSend(1L, "com.a"))
        assertTrue(
            log.queueIfNeeded(2L, "com.a", { byteArrayOf(2) }) { iconSendAllowed(2L, live) },
        )
        assertFalse(log.needsSend(2L, "com.a"))
        var loads = 0
        assertTrue(
            "already sent is ready, without loading again",
            log.queueIfNeeded(2L, "com.a", { loads++; byteArrayOf(3) }) { error("send") },
        )
        assertEquals(0, loads)
    }

    private fun assertIconBeforeNote(written: List<Pair<Long, String>>) {
        val ready = mutableSetOf<Long>()
        for ((session, frame) in written) {
            val type = JSONObject(frame).getString("type")
            if (type == "app_icon") ready.add(session)
            if (type == "notification") {
                assertTrue(
                    "notification on session $session without a preceding app_icon",
                    session in ready,
                )
            }
        }
    }

    /** Frame queued as if the link accepted it, or null when it was not sent. */
    private fun queued(
        log: AppIconLog,
        session: Long?,
        app: String,
        png: ByteArray = byteArrayOf(1),
    ): String? {
        var frame: String? = null
        val ok = log.sendOnce(session, app, { png }) {
            frame = it
            true
        }
        return if (ok) frame else null
    }
}
