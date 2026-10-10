package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Writes on one link session stay whole lines, and a stale session cannot write or clear a newer one. */
class LinkClientTest {
    @Test
    fun concurrentWritersProduceWholeLinesOnly() {
        val stream = SlowOutputStream()
        val client = newClient()
        val session = client.startSession(stream)
        val frames = (0 until 8).map { i -> "F%02d:%s".format(i, "x".repeat(48)) }
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val threads = frames.map { frame ->
            Thread {
                try {
                    start.await()
                    repeat(12) {
                        check(session.send(frame))
                        check(client.sendOn(session.id, frame))
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(20_000) }
        assertTrue(threads.all { !it.isAlive })
        failure.get()?.let { throw it }

        assertEquals(1, stream.peak())
        val text = stream.text()
        assertTrue(text.endsWith("\n"))
        val lines = text.split("\n").dropLast(1)
        assertEquals(frames.size * 24, lines.size)
        assertEquals(frames.toSet(), lines.toSet())
        for (frame in frames) assertEquals(24, lines.count { it == frame })
    }

    @Test
    fun sendOnWithStaleIdWritesNothing() {
        val client = newClient()
        assertFalse(client.sendOn(1L, "no-session"))
        assertNull(client.currentSessionId())

        val endedOut = ByteArrayOutputStream()
        val ended = client.startSession(endedOut)
        val endedId = client.currentSessionId()
        assertEquals(ended.id, endedId)
        client.endSession(ended)
        assertNull(client.currentSession.get())
        assertFalse(client.sendOn(ended.id, "after-end"))
        assertFalse(ended.send("direct"))
        assertEquals(0, endedOut.size())

        val oldOut = ByteArrayOutputStream()
        val old = client.startSession(oldOut)
        val currentOut = ByteArrayOutputStream()
        val current = client.startSession(currentOut)
        assertFalse(client.sendOn(old.id, "stale"))
        assertFalse(client.sendOn(ended.id, "older"))
        assertEquals(0, oldOut.size())
        assertEquals(0, currentOut.size())
        assertEquals(current.id, client.currentSessionId())
        assertTrue(client.sendOn(current.id, "fresh"))
        assertEquals("fresh\n", String(currentOut.toByteArray(), Charsets.UTF_8))
        assertEquals(0, oldOut.size())
    }

    @Test
    fun oldSessionEndingDoesNotClearNewerSession() {
        val client = newClient()
        val old = client.startSession(ByteArrayOutputStream())
        val newerOut = ByteArrayOutputStream()
        val newer = client.startSession(newerOut)
        assertTrue(newer.id > old.id)
        assertSame(newer, client.currentSession.get())

        val start = CountDownLatch(1)
        val done = CountDownLatch(6)
        repeat(6) {
            Thread {
                start.await()
                client.endSession(old)
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertSame(newer, client.currentSession.get())
        assertTrue(client.sendOn(newer.id, "kept"))
        assertEquals("kept\n", String(newerOut.toByteArray(), Charsets.UTF_8))
        assertFalse(client.sendOn(old.id, "gone"))

        client.endSession(newer)
        assertNull(client.currentSession.get())
        assertFalse(client.sendOn(newer.id, "after"))
        val third = client.startSession(ByteArrayOutputStream())
        client.endSession(old)
        client.endSession(newer)
        assertSame(third, client.currentSession.get())
        assertTrue(third.id > newer.id)
    }

    private fun newClient(): LinkClient = LinkClient(
        pairing = PairingInfo(
            hosts = listOf("127.0.0.1"),
            port = 47100,
            fingerprint = "ab".repeat(32),
            keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            name = "test",
        ),
        deviceId = "device",
        deviceName = "phone",
        onState = { _, _ -> },
        onDismiss = {},
        onClipboard = {},
        onReply = { _, _ -> "" },
    )

    /**
     * Copies one byte at a time and yields, so writers that do not share a lock mix lines.
     * [peak] is how many writers were inside [write] at once.
     */
    private class SlowOutputStream : OutputStream() {
        private val buf = ByteArrayOutputStream()
        private val inside = AtomicInteger()
        private val peakInside = AtomicInteger()

        override fun write(b: Int) {
            val now = inside.incrementAndGet()
            peakInside.updateAndGet { prev -> maxOf(prev, now) }
            try {
                Thread.yield()
                synchronized(buf) { buf.write(b) }
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in 0 until len) write(b[off + i].toInt())
        }

        fun peak(): Int = peakInside.get()

        fun text(): String = synchronized(buf) { String(buf.toByteArray(), Charsets.UTF_8) }
    }
}
