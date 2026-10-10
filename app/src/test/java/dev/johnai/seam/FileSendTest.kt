package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Chunking, frames, and the outgoing file state machine. See protocol/PROTOCOL.md. */
class FileSendTest {
    private val id = "ab".repeat(16)

    @Test
    fun chunkLengthsSplitOnTheProtocolLimit() {
        assertEquals(FileSend.CHUNK_BYTES, Protocol.FILE_CHUNK_MAX_BYTES)
        assertEquals(emptyList<Int>(), FileSend.chunkLengths(0))
        assertEquals(listOf(4), FileSend.chunkLengths(4, 4))
        assertEquals(listOf(4, 4, 2), FileSend.chunkLengths(10, 4))
        assertEquals(listOf(FileSend.CHUNK_BYTES), FileSend.chunkLengths(FileSend.CHUNK_BYTES.toLong()))
        assertEquals(
            listOf(FileSend.CHUNK_BYTES, 1),
            FileSend.chunkLengths(FileSend.CHUNK_BYTES + 1L),
        )
        assertEquals(8192, FileSend.chunkLengths(Protocol.FILE_MAX_BYTES).size)
        try {
            FileSend.chunkLengths(-1)
            throw AssertionError("negative size")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }

    @Test
    fun usableMimeDropsWildcardsAndParameters() {
        assertNull(FileSend.usableMime(null))
        assertNull(FileSend.usableMime(""))
        assertNull(FileSend.usableMime("*/*"))
        assertNull(FileSend.usableMime("not-a-mime"))
        assertEquals("text/plain", FileSend.usableMime("text/plain; charset=utf-8"))
        assertEquals("image/jpeg", FileSend.usableMime(" image/jpeg "))
    }

    @Test
    fun onlyGrantedContentUrisAreAccepted() {
        val own = "dev.johnai.seam"
        assertTrue(FileSend.isAcceptedShareUri("content", "media", true, true, own))
        assertTrue(FileSend.isAcceptedShareUri("CONTENT", "com.android.providers.media.documents", true, true, own))
        assertTrue(FileSend.mayOpenSharedUri("content", "media", own))
        // file:// is refused even when the intent claims a read grant and lists the URI.
        assertFalse(FileSend.isAcceptedShareUri("file", "", true, true, own))
        assertFalse(FileSend.isAcceptedShareUri("file", "localhost", true, true, own))
        assertFalse(FileSend.mayOpenSharedUri("file", "localhost", own))
        assertFalse(FileSend.isAcceptedShareUri("content", "media", false, true, own))
        assertFalse(FileSend.isAcceptedShareUri("content", "media", true, false, own))
        assertFalse(FileSend.isAcceptedShareUri(null, "media", true, true, own))
        assertFalse(FileSend.isAcceptedShareUri("content", null, true, true, own))
        assertFalse(FileSend.isAcceptedShareUri("content", "", true, true, own))
        assertFalse(FileSend.isAcceptedShareUri("content", "   ", true, true, own))
        assertFalse(FileSend.isAcceptedShareUri("android.resource", "dev.johnai.seam", true, true, own))
        assertFalse(FileSend.isAcceptedShareUri("content", own, true, true, own))
        assertFalse(FileSend.isAcceptedShareUri("content", "$own.fileprovider", true, true, own))
        assertFalse(FileSend.mayOpenSharedUri("content", "$own.FileProvider", own))
        assertTrue(FileSend.mayOpenSharedUri("content", "media", ""))
    }

    @Test
    fun callerCheckNeverTrustsUrisThisAppAlreadyHolds() {
        val own = "dev.johnai.seam"
        assertTrue(
            FileSend.callerMayUpload(
                "content", "media", true, true, own,
                callingHasRead = true, callerKnown = true, alreadyHeld = true,
            ),
        )
        assertFalse(
            "an identified caller without read is refused",
            FileSend.callerMayUpload(
                "content", "media", true, true, own,
                callingHasRead = false, callerKnown = true, alreadyHeld = false,
            ),
        )
        assertFalse(
            "unknown caller cannot push a URI this app already holds",
            FileSend.callerMayUpload(
                "content", "media", true, true, own,
                callingHasRead = false, callerKnown = false, alreadyHeld = true,
            ),
        )
        assertTrue(
            FileSend.callerMayUpload(
                "content", "media", true, true, own,
                callingHasRead = false, callerKnown = false, alreadyHeld = false,
            ),
        )
        assertFalse(
            FileSend.callerMayUpload(
                "file", "localhost", true, true, own,
                callingHasRead = true, callerKnown = true, alreadyHeld = false,
            ),
        )
        assertFalse(
            FileSend.callerMayUpload(
                "content", own, true, true, own,
                callingHasRead = true, callerKnown = true, alreadyHeld = false,
            ),
        )
        assertFalse(
            FileSend.callerMayUpload(
                "content", "$own.fileprovider", true, true, own,
                callingHasRead = true, callerKnown = true, alreadyHeld = false,
            ),
        )
        assertFalse(
            FileSend.callerMayUpload(
                "content", "media", false, true, own,
                callingHasRead = true, callerKnown = true, alreadyHeld = false,
            ),
        )
    }

    @Test
    fun callerPidUnknownIsMinusOneNotZero() {
        assertEquals(-1, FileSend.CALLER_PID_UNKNOWN)
        assertTrue(FileSend.CALLER_PID_UNKNOWN != 0)
    }

    @Test
    fun identifiedCallerDenialDoesNotFailOpen() {
        val mine = 10_123
        val other = 10_456
        val denied = FileSend.callerAccess(
            inIpc = false,
            ipcGranted = false,
            launchedFromUid = other,
            launchedUidCanRead = false,
            myUid = mine,
        )
        assertFalse(denied.hasRead)
        assertTrue("a denial is a known caller", denied.known)
        assertFalse(
            "known denial must not upload a URI we do not already hold",
            FileSend.callerMayUpload(
                "content", "media", true, true, "dev.johnai.seam",
                denied.hasRead, denied.known, alreadyHeld = false,
            ),
        )

        val granted = FileSend.callerAccess(
            inIpc = false,
            ipcGranted = false,
            launchedFromUid = other,
            launchedUidCanRead = true,
            myUid = mine,
        )
        assertTrue(granted.hasRead)
        assertTrue(granted.known)
        assertTrue(
            FileSend.callerMayUpload(
                "content", "media", true, true, "dev.johnai.seam",
                granted.hasRead, granted.known, alreadyHeld = true,
            ),
        )

        // A grant observed off the IPC path is this process, not the sharing app.
        val unknown = FileSend.callerAccess(
            inIpc = false,
            ipcGranted = true,
            launchedFromUid = -1,
            launchedUidCanRead = false,
            myUid = mine,
        )
        assertFalse(unknown.hasRead)
        assertFalse(unknown.known)

        assertFalse(FileSend.callerAccess(false, false, mine, true, mine).known)
        val root = FileSend.callerAccess(false, false, 0, true, mine)
        assertFalse(root.known)
        assertFalse(root.hasRead)
        val system = FileSend.callerAccess(false, false, 1000, true, mine)
        assertFalse(system.known)
        assertFalse(system.hasRead)

        val ipcNo = FileSend.callerAccess(true, false, other, true, mine)
        assertTrue(ipcNo.known)
        assertFalse(ipcNo.hasRead)
        val ipcYes = FileSend.callerAccess(true, true, -1, false, mine)
        assertTrue(ipcYes.hasRead)
        assertTrue(ipcYes.known)
    }

    @Test
    fun unknownCallerCannotUploadAProviderThisAppCanAlreadyRead() {
        val access = FileSend.callerAccess(
            inIpc = false,
            ipcGranted = false,
            launchedFromUid = -1,
            launchedUidCanRead = false,
            myUid = 10_001,
        )
        assertFalse(access.known)
        val contacts = FileSend.readableWithoutUriGrant(
            exported = true,
            readPermission = "android.permission.READ_CONTACTS",
            holds = { it == "android.permission.READ_CONTACTS" },
            matchingPathReadPermissions = emptyList(),
        )
        assertTrue(contacts)
        assertFalse(
            FileSend.callerMayUpload(
                "content", "com.android.contacts", true, true, "dev.johnai.seam",
                access.hasRead, access.known, contacts,
            ),
        )
        assertTrue(
            FileSend.authorityReadableByHeldPermission(
                "com.android.contacts", holdsContacts = true, holdsCallLog = false, holdsPhoneState = false,
            ),
        )
        assertTrue(
            FileSend.authorityReadableByHeldPermission(
                "call_log", holdsContacts = false, holdsCallLog = true, holdsPhoneState = false,
            ),
        )
        assertTrue(
            FileSend.authorityReadableByHeldPermission(
                "telephony", holdsContacts = false, holdsCallLog = false, holdsPhoneState = true,
            ),
        )
        assertFalse(
            FileSend.authorityReadableByHeldPermission(
                "com.android.contacts", holdsContacts = false, holdsCallLog = true, holdsPhoneState = true,
            ),
        )
        assertFalse(
            FileSend.authorityReadableByHeldPermission(
                "com.google.android.apps.docs.storage",
                holdsContacts = true, holdsCallLog = true, holdsPhoneState = true,
            ),
        )
        // Drive-style: not exported, so not already readable. An unknown caller may still
        // upload if the share grant is the only access.
        assertFalse(FileSend.readableWithoutUriGrant(false, null, { true }, emptyList()))
        assertTrue(
            FileSend.callerMayUpload(
                "content", "com.google.android.apps.docs.storage", true, true, "dev.johnai.seam",
                callingHasRead = false, callerKnown = false, alreadyHeld = false,
            ),
        )
        assertTrue(FileSend.readableWithoutUriGrant(true, null, { false }, emptyList()))
        assertFalse(
            FileSend.readableWithoutUriGrant(
                true, null, { false }, listOf("android.permission.READ_CONTACTS"),
            ),
        )
        assertTrue(
            FileSend.readableWithoutUriGrant(
                true,
                "android.permission.READ_EXTERNAL_STORAGE",
                { it == "android.permission.READ_CONTACTS" },
                listOf("android.permission.READ_CONTACTS"),
            ),
        )
        assertFalse(
            FileSend.readableWithoutUriGrant(
                true, "android.permission.READ_EXTERNAL_STORAGE", { false }, listOf(null),
            ),
        )
    }

    @Test
    fun cancelRequestCodeIsStablePerTransfer() {
        val code = FileSend.cancelRequestCode(id)
        assertEquals(code, FileSend.cancelRequestCode(id))
        assertTrue(code != FileSend.cancelRequestCode("cd".repeat(16)))
        assertTrue(code > 0)
    }

    @Test
    fun transferIdIs32HexChars() {
        val a = FileSend.randomTransferId()
        val b = FileSend.randomTransferId()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(a != b)
    }

    @Test
    fun happyPathChunksFramesAndHash() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val io = ScriptedIo()
        io.reply(Protocol.fileAccept(id))
        io.reply(Protocol.fileResult(id, true))
        val source = MemSource(data)
        val progress = ArrayList<Long>()
        val result = FileUpload(io).send(
            id,
            "../notes.txt",
            data.size.toLong(),
            "text/plain; charset=utf-8",
            open = { source },
            onProgress = { sent, _ -> progress.add(sent) },
            chunkBytes = 3,
        )
        assertEquals(FileUpload.Status.SENT, result.status)
        assertEquals(listOf("file_offer", "file_chunk", "file_chunk", "file_chunk", "file_done"), io.types())
        assertTrue(io.sent.all { it.first == 1L })
        val offer = Protocol.parseFileOffer(io.sent[0].second)!!
        assertEquals(id, offer.transfer)
        assertEquals("notes.txt", offer.name)
        assertEquals(8L, offer.size)
        assertEquals("text/plain", offer.mime)
        val chunks = io.sent.mapNotNull { Protocol.parseFileChunk(it.second) }
        assertEquals(listOf(0L, 1L, 2L), chunks.map { it.seq })
        assertEquals(listOf(3, 3, 2), chunks.map { it.data.size })
        assertTrue(data.contentEquals(chunks.fold(ByteArray(0)) { acc, part -> acc + part.data }))
        assertTrue(source.maxLen <= 3)
        assertEquals(data.size, source.pos)
        assertEquals(listOf(3L, 6L, 8L), progress)
        val done = Protocol.parseFileDone(io.sent[4].second)!!
        assertEquals(sha256(data), done.sha256)
        assertEquals(id, io.prepared)
        assertEquals(id, io.finished)
    }

    @Test
    fun emptyFileSendsOfferAndDoneOnly() {
        val io = ScriptedIo()
        io.reply(Protocol.fileAccept(id))
        io.reply(Protocol.fileResult(id, true))
        var opened = false
        val result = FileUpload(io).send(
            id,
            "empty.txt",
            0L,
            null,
            open = {
                opened = true
                MemSource(ByteArray(0))
            },
        )
        assertEquals(FileUpload.Status.SENT, result.status)
        assertTrue(opened)
        assertEquals(listOf("file_offer", "file_done"), io.types())
        assertNull(Protocol.parseFileOffer(io.sent[0].second)!!.mime)
        val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(emptyHash, sha256(ByteArray(0)))
        assertEquals(emptyHash, Protocol.parseFileDone(io.sent[1].second)!!.sha256)
    }

    @Test
    fun chunkSizeIsCappedAt256KiB() {
        val extra = 10
        val data = ByteArray(FileSend.CHUNK_BYTES + extra) { (it and 0xff).toByte() }
        val io = ScriptedIo()
        io.reply(Protocol.fileAccept(id))
        io.reply(Protocol.fileResult(id, true))
        val source = MemSource(data)
        val result = FileUpload(io).send(
            id,
            "big.bin",
            data.size.toLong(),
            null,
            open = { source },
            chunkBytes = FileSend.CHUNK_BYTES * 2,
        )
        assertEquals(FileUpload.Status.SENT, result.status)
        assertTrue(source.maxLen <= FileSend.CHUNK_BYTES)
        val chunks = io.sent.mapNotNull { Protocol.parseFileChunk(it.second) }
        assertEquals(2, chunks.size)
        assertEquals(FileSend.CHUNK_BYTES, chunks[0].data.size)
        assertEquals(extra, chunks[1].data.size)
        assertEquals(0L, chunks[0].seq)
        assertEquals(1L, chunks[1].seq)
        assertEquals(sha256(data), Protocol.parseFileDone(io.sent.last().second)!!.sha256)
    }

    @Test
    fun reconnectDoesNotReviveAnInFlightUpload() {
        val io = ScriptedIo(session = 7L)
        io.replyWith {
            io.session = 8L
            Protocol.fileAccept(id)
        }
        var opened = false
        val result = FileUpload(io).send(id, "a.bin", 4L, null, open = {
            opened = true
            MemSource(byteArrayOf(1, 2, 3, 4))
        })
        assertEquals(FileUpload.Status.FAILED, result.status)
        assertEquals(FileUpload.ERR_NOT_CONNECTED, result.detail)
        assertEquals("no frames after the session changes", listOf("file_offer"), io.types())
        assertFalse(io.types().contains("file_cancel"))
        assertFalse(opened)
        assertTrue(io.sent.all { it.first == 7L })
        val before = io.sent.size
        assertTrue(io.send(8L, Protocol.fileChunk(id, 0L, byteArrayOf(1))))
        assertEquals(before + 1, io.sent.size)
        assertEquals(8L, io.sent.last().first)
        assertTrue(io.sent.dropLast(1).all { it.first == 7L })
    }

    @Test
    fun sessionChangeAfterAChunkSendsNoFurtherFrames() {
        val io = ScriptedIo()
        io.reply(Protocol.fileAccept(id))
        io.afterSend = { frame ->
            if (Protocol.parse(frame)?.opt("type") == "file_chunk") io.session = 99L
        }
        val result = FileUpload(io).send(
            id,
            "a.bin",
            4L,
            null,
            open = { MemSource(byteArrayOf(1, 2, 3, 4)) },
            chunkBytes = 4,
        )
        assertEquals(FileUpload.Status.FAILED, result.status)
        assertEquals(listOf("file_offer", "file_chunk"), io.types())
        assertFalse(io.types().contains("file_done"))
        assertFalse(io.types().contains("file_cancel"))
        assertTrue(io.sent.all { it.first == 1L })
    }

    @Test
    fun rejectAndPeerCancelSendNoChunks() {
        val rejected = ScriptedIo()
        rejected.reply(Protocol.fileReject(id, "no thanks"))
        var opened = false
        val reject = FileUpload(rejected).send(id, "a.bin", 1L, null, open = {
            opened = true
            MemSource(byteArrayOf(1))
        })
        assertEquals(FileUpload.Status.REJECTED, reject.status)
        assertEquals("no thanks", reject.detail)
        assertEquals(listOf("file_offer"), rejected.types())
        assertFalse(opened)

        val plain = ScriptedIo()
        plain.reply(Protocol.fileReject(id))
        assertEquals(
            FileUpload.ERR_REJECTED,
            FileUpload(plain).send(id, "a.bin", 1L, null, open = { MemSource(byteArrayOf(1)) }).detail,
        )

        val cancelled = ScriptedIo()
        cancelled.reply(Protocol.fileCancel(id))
        val cancel = FileUpload(cancelled).send(id, "a.bin", 1L, null, open = { MemSource(byteArrayOf(1)) })
        assertEquals(FileUpload.Status.CANCELLED, cancel.status)
        assertEquals(listOf("file_offer"), cancelled.types())
    }

    @Test
    fun timeoutAndLocalCancelSendFileCancelOnTheSameSession() {
        val timed = ScriptedIo()
        val timeout = FileUpload(timed).send(id, "a.bin", 1L, null, open = { MemSource(byteArrayOf(1)) })
        assertEquals(FileUpload.Status.FAILED, timeout.status)
        assertEquals(FileUpload.ERR_TIMEOUT, timeout.detail)
        assertEquals(listOf("file_offer", "file_cancel"), timed.types())
        assertEquals(id, Protocol.parseFileCancel(timed.sent[1].second))

        val local = ScriptedIo()
        local.reply(Protocol.fileAccept(id))
        local.afterSend = { frame ->
            if (Protocol.parse(frame)?.opt("type") == "file_chunk") local.cancelled = true
        }
        val cancel = FileUpload(local).send(
            id,
            "a.bin",
            4L,
            null,
            open = { MemSource(byteArrayOf(1, 2, 3, 4)) },
            chunkBytes = 4,
        )
        assertEquals(FileUpload.Status.CANCELLED, cancel.status)
        assertEquals(listOf("file_offer", "file_chunk", "file_cancel"), local.types())
        assertFalse(local.types().contains("file_done"))
    }

    @Test
    fun cancelBeforeOfferWritesNothing() {
        val io = ScriptedIo()
        io.cancelled = true
        val result = FileUpload(io).send(id, "a.bin", 1L, null, open = { MemSource(byteArrayOf(1)) })
        assertEquals(FileUpload.Status.CANCELLED, result.status)
        assertTrue(io.sent.isEmpty())
        assertNull(io.prepared)
    }

    @Test
    fun disconnectedOrRefusedOfferWritesNothing() {
        val down = ScriptedIo(session = null)
        val gone = FileUpload(down).send(id, "a.bin", 1L, null, open = { MemSource(byteArrayOf(1)) })
        assertEquals(FileUpload.Status.FAILED, gone.status)
        assertTrue(down.sent.isEmpty())

        val refused = ScriptedIo()
        refused.failNextSend = true
        val fail = FileUpload(refused).send(id, "a.bin", 1L, null, open = { MemSource(byteArrayOf(1)) })
        assertEquals(FileUpload.Status.FAILED, fail.status)
        assertTrue(refused.sent.isEmpty())
        assertFalse(refused.types().contains("file_cancel"))
    }

    @Test
    fun shortReadAndBadResultDoNotClaimSuccess() {
        val short = ScriptedIo()
        short.reply(Protocol.fileAccept(id))
        val shortResult = FileUpload(short).send(
            id,
            "a.bin",
            10L,
            null,
            open = { MemSource(byteArrayOf(1, 2, 3)) },
            chunkBytes = 4,
        )
        assertEquals(FileUpload.Status.FAILED, shortResult.status)
        assertEquals(FileUpload.ERR_READ, shortResult.detail)
        assertEquals(listOf("file_offer", "file_cancel"), short.types())
        assertFalse(short.types().contains("file_done"))

        val bad = ScriptedIo()
        bad.reply(Protocol.fileAccept(id))
        bad.reply(Protocol.fileResult(id, false, "wrong hash"))
        val badResult = FileUpload(bad).send(
            id,
            "a.bin",
            1L,
            null,
            open = { MemSource(byteArrayOf(9)) },
        )
        assertEquals(FileUpload.Status.FAILED, badResult.status)
        assertEquals("wrong hash", badResult.detail)
        assertTrue(bad.types().contains("file_done"))
        assertFalse(bad.types().contains("file_cancel"))
    }

    @Test
    fun tooLargeOrUnknownSizeSendsNoOffer() {
        val huge = ScriptedIo()
        val tooBig = FileUpload(huge).send(
            id,
            "a.bin",
            Protocol.FILE_MAX_BYTES + 1,
            null,
            open = { MemSource(byteArrayOf(1)) },
        )
        assertEquals(FileUpload.Status.FAILED, tooBig.status)
        assertEquals(FileUpload.ERR_TOO_LARGE, tooBig.detail)
        assertTrue(huge.sent.isEmpty())

        val missing = ScriptedIo()
        val unread = FileUpload(missing).send(id, "a.bin", -1L, null, open = { MemSource(byteArrayOf(1)) })
        assertEquals(FileUpload.ERR_READ, unread.detail)
        assertTrue(missing.sent.isEmpty())
    }

    @Test
    fun progressFailureDoesNotFailTheTransfer() {
        val io = ScriptedIo()
        io.reply(Protocol.fileAccept(id))
        io.reply(Protocol.fileResult(id, true))
        val result = FileUpload(io).send(
            id,
            "a.bin",
            1L,
            null,
            open = { MemSource(byteArrayOf(1)) },
            onProgress = { _, _ -> throw IllegalStateException("ui") },
        )
        assertEquals(FileUpload.Status.SENT, result.status)
    }

    @Test
    fun responseQueueKeepsAcceptThenResultAndIgnoresOffers() {
        val q = FileResponseQueue()
        q.arm(id)
        assertFalse(q.deliver(Protocol.fileOffer(id, "a.txt", 1L)))
        assertFalse(q.deliver(Protocol.fileAccept("11".repeat(16))))
        assertTrue(q.deliver(Protocol.fileAccept(id)))
        assertTrue(q.deliver(Protocol.fileResult(id, true)))
        assertEquals(id, Protocol.parseFileAccept(q.await(id, 1L, { 1L }, 1_000)!!))
        assertTrue(Protocol.parseFileResult(q.await(id, 1L, { 1L }, 1_000)!!)!!.ok)
        q.finish(id)
        assertFalse(q.deliver(Protocol.fileAccept(id)))
    }

    @Test
    fun queueDropsAFrameWhenTheSessionChanges() {
        val q = FileResponseQueue()
        q.arm(id)
        assertTrue(q.deliver(Protocol.fileAccept(id)))
        val start = System.nanoTime()
        assertNull(q.await(id, 1L, { null }, 10_000))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
    }

    @Test(timeout = 10000)
    fun cancelAndDeliverUnblockAwait() {
        val q = FileResponseQueue()
        val other = "cd".repeat(16)
        q.arm(id)
        q.arm(other)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cancelled = pool.submit<String?> { q.await(id, 1L, { 1L }, 30_000) }
            val accepted = pool.submit<String?> { q.await(other, 1L, { 1L }, 5_000) }
            q.cancel(id)
            assertTrue(q.deliver(Protocol.fileAccept(other)))
            assertNull(cancelled.get(2, TimeUnit.SECONDS))
            assertEquals(other, Protocol.parseFileAccept(accepted.get(2, TimeUnit.SECONDS)!!))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10000)
    fun shutdownNowReportsQueuedWorkThatNeverStarted() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(
            UploadJobs.submit(
                executor,
                NamedUpload("running") {
                    started.countDown()
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
            ),
        )
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(UploadJobs.submit(executor, NamedUpload("queued-a") { error("a") }))
        assertTrue(UploadJobs.submit(executor, NamedUpload("queued-b") { error("b") }))
        assertEquals(listOf("queued-a", "queued-b"), UploadJobs.shutdownNow(executor))
        release.countDown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun submitAfterShutdownIsRejected() {
        val executor = Executors.newSingleThreadExecutor()
        assertTrue(UploadJobs.shutdownNow(executor).isEmpty())
        assertFalse(UploadJobs.submit(executor, NamedUpload("late") { error("ran") }))
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/** A transport that writes only on the captured session, like LinkClient.sendOn. */
private class ScriptedIo(var session: Long? = 1L) : FileUpload.Io {
    val sent = mutableListOf<Pair<Long, String>>()
    private val replies = ArrayDeque<(String) -> String?>()
    var cancelled = false
    var prepared: String? = null
    var finished: String? = null
    var failNextSend = false
    var afterSend: ((String) -> Unit)? = null

    fun reply(frame: String) {
        replies.add { frame }
    }

    fun replyWith(block: (String) -> String?) {
        replies.add(block)
    }

    override fun prepare(transfer: String) {
        prepared = transfer
    }

    override fun finish(transfer: String) {
        finished = transfer
    }

    override fun sessionId(): Long? = session

    override fun send(sessionId: Long, frame: String): Boolean {
        if (failNextSend) {
            failNextSend = false
            return false
        }
        if (session == null || sessionId != session) return false
        sent.add(sessionId to frame)
        afterSend?.invoke(frame)
        return true
    }

    override fun await(transfer: String, sessionId: Long, timeoutMs: Long): String? {
        val next = replies.poll() ?: return null
        return next(transfer)
    }

    override fun isCancelled(): Boolean = cancelled

    fun types(): List<String> = sent.map { Protocol.parse(it.second)!!.getString("type") }
}

private class MemSource(private val data: ByteArray) : FileUpload.ByteSource {
    var pos = 0
    var maxLen = 0
    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (len > maxLen) maxLen = len
        if (pos >= data.size) return -1
        val n = minOf(len, data.size - pos)
        for (i in 0 until n) buf[off + i] = data[pos + i]
        pos += n
        return n
    }

    override fun close() {}
}
