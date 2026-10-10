package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Writes on a link are bound to one session and never interleave. */
class LinkClientTest {
    @Test(timeout = 20000)
    fun concurrentWritersProduceWholeLinesOnly() {
        val stream = FakeOutputStream()
        val client = client()
        val session = client.openSession(SlowWriter(stream))
        val frameA = "A".repeat(40)
        val frameB = "B".repeat(24)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = listOf(
                pool.submit<Unit> {
                    start.await()
                    repeat(6) { if (!session.send(frameA)) error("send failed") }
                },
                pool.submit<Unit> {
                    start.await()
                    repeat(6) { if (!client.sendOn(session.id, frameB)) error("sendOn failed") }
                },
                pool.submit<Unit> {
                    start.await()
                    repeat(6) { if (!session.send(frameB)) error("send failed") }
                },
                pool.submit<Unit> {
                    start.await()
                    repeat(6) { if (!client.sendOn(session.id, frameA)) error("sendOn failed") }
                },
            )
            start.countDown()
            for (future in futures) future.get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        assertFalse(stream.overlapped)
        val text = stream.text()
        assertTrue(text.endsWith("\n"))
        val lines = text.split("\n").filter { it.isNotEmpty() }
        assertEquals(24, lines.size)
        for (line in lines) {
            assertTrue(line == frameA || line == frameB)
        }
    }

    @Test
    fun sendOnWithStaleIdWritesNothing() {
        val client = client()
        assertNull(client.currentSessionId())
        assertFalse(client.sendOn(0L, "nope"))
        assertFalse(client.sendOn(1L, "nope"))

        val oldRaw = ByteArrayOutputStream()
        val newRaw = ByteArrayOutputStream()
        val old = client.openSession(OutputStreamWriter(oldRaw, Charsets.UTF_8))
        val newer = client.openSession(OutputStreamWriter(newRaw, Charsets.UTF_8))
        assertTrue(newer.id > old.id)
        assertEquals(newer.id, client.currentSessionId())

        assertFalse(client.sendOn(old.id, "stale-frame"))
        assertFalse(old.send("direct"))
        assertEquals("", bytes(oldRaw))
        assertEquals("", bytes(newRaw))

        assertTrue(client.sendOn(newer.id, "héllo"))
        assertEquals("héllo\n", bytes(newRaw))
        assertEquals("", bytes(oldRaw))

        newer.end()
        assertNull(client.currentSessionId())
        assertFalse(client.sendOn(newer.id, "after"))
        assertEquals("héllo\n", bytes(newRaw))
    }

    @Test
    fun oldSessionEndingDoesNotClearNewerSession() {
        val client = client()
        val old = client.openSession(OutputStreamWriter(ByteArrayOutputStream(), Charsets.UTF_8))
        val liveRaw = ByteArrayOutputStream()
        val newer = client.openSession(OutputStreamWriter(liveRaw, Charsets.UTF_8))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = List(4) {
                pool.submit<Unit> {
                    start.await()
                    old.end()
                }
            }
            start.countDown()
            for (future in futures) future.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(newer.id, client.currentSessionId())
        assertFalse(client.sendOn(old.id, "from-old"))
        assertTrue(client.sendOn(newer.id, "still-here"))
        assertEquals("still-here\n", bytes(liveRaw))

        newer.end()
        assertNull(client.currentSessionId())
        val nextRaw = ByteArrayOutputStream()
        val next = client.openSession(OutputStreamWriter(nextRaw, Charsets.UTF_8))
        old.end()
        newer.end()
        assertEquals(next.id, client.currentSessionId())
        assertTrue(next.id > newer.id)
        assertTrue(client.sendOn(next.id, "next"))
        assertEquals("next\n", bytes(nextRaw))
    }

    private fun bytes(raw: ByteArrayOutputStream): String = raw.toByteArray().toString(Charsets.UTF_8)

    private fun client(): LinkClient {
        val info = PairingInfo(
            hosts = listOf("127.0.0.1"),
            port = 1,
            fingerprint = "ab".repeat(32),
            keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            name = "test",
        )
        return LinkClient(
            info,
            "device",
            "phone",
            onState = { _, _ -> },
            onDismiss = {},
            onClipboard = {},
            onReply = { _, _ -> "" },
        )
    }
}

/** Records bytes and whether two writes were inside the stream at once. */
private class FakeOutputStream : OutputStream() {
    private val bytes = ArrayList<Byte>()
    private val active = AtomicInteger()

    @Volatile
    var overlapped = false

    override fun write(b: Int) {
        synchronized(bytes) { bytes.add(b.toByte()) }
    }

    fun begin() {
        if (active.incrementAndGet() > 1) overlapped = true
    }

    fun end() {
        active.decrementAndGet()
    }

    fun text(): String = synchronized(bytes) { bytes.toByteArray().toString(Charsets.UTF_8) }
}

/**
 * Does not lock around a frame. Session.send is what keeps concurrent writers from mixing lines.
 */
private class SlowWriter(private val out: FakeOutputStream) : Writer() {
    override fun write(str: String) {
        out.begin()
        try {
            Thread.sleep(10)
            for (ch in str) out.write(ch.code)
        } finally {
            out.end()
        }
    }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        write(String(cbuf, off, len))
    }

    override fun flush() {}
    override fun close() {}
}
