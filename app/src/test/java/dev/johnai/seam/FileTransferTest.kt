package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Name rules and the incoming file state machine. See protocol/PROTOCOL.md. */
class FileTransferTest {
    private val id = "ab".repeat(16)

    @Test
    fun sanitizeParentPath() {
        assertEquals("x", FileTransfer.sanitizeName("../x"))
        assertEquals("x", FileTransfer.Incoming(id, "../x", 0).name)
    }

    @Test
    fun sanitizeSlashPath() {
        assertEquals("b", FileTransfer.sanitizeName("a/b"))
        assertEquals("b", FileTransfer.sanitizeName("a" + '\\' + "b"))
        assertEquals("passwd", FileTransfer.sanitizeName("foo/../../etc/passwd"))
        assertEquals(
            "photo.jpg",
            FileTransfer.sanitizeName("C:" + '\\' + "Users" + '\\' + "photo.jpg"),
        )
    }

    @Test
    fun sanitizeHidden() {
        assertEquals("hidden", FileTransfer.sanitizeName(".hidden"))
        assertEquals("hidden.txt", FileTransfer.sanitizeName(".hidden.txt"))
        assertEquals("hidden", FileTransfer.sanitizeName("..hidden"))
    }

    @Test
    fun sanitizeEmptyName() {
        assertEquals("file", FileTransfer.sanitizeName(""))
        assertEquals("file", FileTransfer.sanitizeName("."))
        assertEquals("file", FileTransfer.sanitizeName(".."))
        assertEquals("file", FileTransfer.sanitizeName("/"))
        assertEquals("file", FileTransfer.sanitizeName("   "))
    }

    @Test
    fun sanitizeLongName() {
        val plain = "a".repeat(300)
        assertEquals(300, plain.length)
        assertEquals(200, FileTransfer.sanitizeName(plain).length)
        assertEquals("a".repeat(200), FileTransfer.sanitizeName(plain))

        val withExt = "b".repeat(296) + ".txt"
        assertEquals(300, withExt.length)
        val kept = FileTransfer.sanitizeName(withExt)
        assertEquals(200, kept.length)
        assertEquals("b".repeat(196) + ".txt", kept)
        assertTrue(kept.endsWith(".txt"))
    }

    @Test
    fun wrongSeq() {
        val incoming = FileTransfer.Incoming(id, "a.bin", 4)
        assertFalse(incoming.chunk(1L, byteArrayOf(1, 2, 3, 4)))
        assertTrue(incoming.failed)
        assertEquals(FileTransfer.Incoming.ERR_SEQ, incoming.failure)
        assertEquals(0L, incoming.bytesReceived)
        assertFalse(incoming.chunk(0L, byteArrayOf(1, 2, 3, 4)))

        val gap = FileTransfer.Incoming(id, "a.bin", 4)
        assertTrue(gap.chunk(0L, byteArrayOf(1, 2)))
        assertFalse(gap.chunk(2L, byteArrayOf(3, 4)))
        assertTrue(gap.failed)
        assertEquals(2L, gap.bytesReceived)
    }

    @Test
    fun tooManyBytes() {
        val one = FileTransfer.Incoming(id, "a.bin", 2)
        assertFalse(one.chunk(0L, byteArrayOf(1, 2, 3)))
        assertTrue(one.failed)
        assertEquals(FileTransfer.Incoming.ERR_TOO_MANY, one.failure)
        assertEquals(0L, one.bytesReceived)

        val two = FileTransfer.Incoming(id, "a.bin", 2)
        assertTrue(two.chunk(0L, byteArrayOf(1)))
        assertFalse(two.chunk(1L, byteArrayOf(2, 3)))
        assertTrue(two.failed)
        assertEquals(1L, two.bytesReceived)
    }

    @Test
    fun wrongHash() {
        val data = byteArrayOf(1, 2, 3, 4)
        val incoming = FileTransfer.Incoming(id, "a.bin", data.size.toLong())
        assertTrue(incoming.chunk(0L, data))
        assertFalse(incoming.done("00".repeat(32)))
        assertTrue(incoming.failed)
        assertFalse(incoming.complete)
        assertEquals(FileTransfer.Incoming.ERR_HASH, incoming.failure)
    }

    @Test
    fun matchingHashCompletes() {
        val first = "hello".toByteArray()
        val second = " world".toByteArray()
        val all = first + second
        val incoming = FileTransfer.Incoming(id, "note.txt", all.size.toLong())
        assertTrue(incoming.chunk(0L, first))
        assertTrue(incoming.chunk(1L, second))
        assertEquals(all.size.toLong(), incoming.bytesReceived)
        assertTrue(incoming.done(sha256(all).uppercase()))
        assertTrue(incoming.complete)
        assertFalse(incoming.failed)
    }

    @Test
    fun seqUsesLong() {
        val incoming = FileTransfer.Incoming(id, "a.bin", 1)
        val pastInt = Int.MAX_VALUE.toLong() + 1L
        assertFalse(incoming.chunk(pastInt, byteArrayOf(1)))
        assertTrue(incoming.failed)
        assertEquals(FileTransfer.Incoming.ERR_SEQ, incoming.failure)
    }

    @Test
    fun chunkLargerThan256KiBFails() {
        val incoming = FileTransfer.Incoming(id, "a.bin", FileTransfer.MAX_CHUNK_BYTES + 1L)
        assertFalse(incoming.chunk(0L, ByteArray(FileTransfer.MAX_CHUNK_BYTES + 1)))
        assertTrue(incoming.failed)
        assertEquals(FileTransfer.Incoming.ERR_CHUNK, incoming.failure)
    }

    @Test
    fun fileLargerThanTwoGibIsRejected() {
        assertEquals(2L * 1024 * 1024 * 1024, FileTransfer.MAX_BYTES)
        assertFalse(FileTransfer.Incoming(id, "a", FileTransfer.MAX_BYTES).failed)
        val huge = FileTransfer.Incoming(id, "a", FileTransfer.MAX_BYTES + 1)
        assertTrue(huge.failed)
        assertFalse(huge.chunk(0L, byteArrayOf(1)))
        assertFalse(huge.done("00".repeat(32)))
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
