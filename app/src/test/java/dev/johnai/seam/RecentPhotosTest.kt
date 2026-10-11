package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Picking recent photos, photo_new frames, and photo_request parsing. */
class RecentPhotosTest {
    @Test
    fun photoLimitsMatchTheProtocol() {
        assertEquals(12, Protocol.PHOTO_RECENT_LIMIT)
        assertEquals(32 * 1024, Protocol.PHOTO_THUMB_MAX_BYTES)
        assertEquals(256, Protocol.PHOTO_THUMB_SIDE)
    }

    @Test
    fun picksTwelveMostRecentOldestFirst() {
        val photos = (1..20).map {
            PhotoCandidate(id = it.toString(), name = "p$it.jpg", taken = it * 1000L, recent = it * 1000L)
        }
        val picked = pickRecentPhotos(photos.asReversed())
        assertEquals(12, picked.size)
        assertEquals((9..20).map { it.toString() }, picked.map { it.id })
        assertEquals("9", picked.first().id)
        assertEquals("20", picked.last().id)
        assertEquals("p9.jpg", picked.first().name)
        assertTrue(picked.zipWithNext().all { it.first.recent <= it.second.recent })
    }

    @Test
    fun fewerThanTwelveReturnsAllOldestFirst() {
        val photos = listOf(
            PhotoCandidate("3", "c", 30, 30),
            PhotoCandidate("1", "a", 10, 10),
            PhotoCandidate("2", "b", 20, 20),
        )
        assertEquals(listOf("1", "2", "3"), pickRecentPhotos(photos).map { it.id })
        assertEquals(listOf("a", "b", "c"), pickRecentPhotos(photos).map { it.name })
    }

    @Test
    fun exactlyTwelveIsOldestFirst() {
        val photos = (1..12).map { PhotoCandidate(it.toString(), "n$it", it.toLong(), it.toLong()) }.asReversed()
        assertEquals((1..12).map { it.toString() }, pickRecentPhotos(photos).map { it.id })
    }

    @Test
    fun ordersByRecentNotTaken() {
        val olderSaved = PhotoCandidate("1", "old-file", taken = 9_000, recent = 100)
        val newerSaved = PhotoCandidate("2", "new-file", taken = 50, recent = 500)
        assertEquals(listOf("1", "2"), pickRecentPhotos(listOf(newerSaved, olderSaved)).map { it.id })
        assertEquals(9_000L, pickRecentPhotos(listOf(newerSaved, olderSaved)).first().taken)
    }

    @Test
    fun sameRecentPrefersHigherIdAsNewer() {
        val photos = listOf(
            PhotoCandidate("10", "a", 5, 100),
            PhotoCandidate("2", "b", 5, 100),
            PhotoCandidate("7", "c", 1, 50),
        )
        assertEquals(listOf("2", "10"), pickRecentPhotos(photos, limit = 2).map { it.id })
    }

    @Test
    fun tieBreakKeepsHighestIdsWhenLimited() {
        val photos = (1..5).map { PhotoCandidate(it.toString(), "n", 10, 10) }
        assertEquals(listOf("4", "5"), pickRecentPhotos(photos, limit = 2).map { it.id })
    }

    @Test
    fun nonNumericIdsSortLexicographicallyWhenRecentTies() {
        val photos = listOf(
            PhotoCandidate("b", "b", 1, 5),
            PhotoCandidate("a", "a", 1, 5),
        )
        assertEquals(listOf("a", "b"), pickRecentPhotos(photos).map { it.id })
    }

    @Test
    fun duplicateIdKeepsNewerAndBlankIdDropped() {
        val photos = listOf(
            PhotoCandidate("1", "old", 1, 1),
            PhotoCandidate("1", "new", 2, 5),
            PhotoCandidate("", "nope", 9, 9),
        )
        val picked = pickRecentPhotos(photos)
        assertEquals(1, picked.size)
        assertEquals("new", picked[0].name)
        assertEquals(5L, picked[0].recent)
        assertEquals(2L, picked[0].taken)
    }

    @Test
    fun emptyAndNonPositiveLimit() {
        assertTrue(pickRecentPhotos(emptyList()).isEmpty())
        val one = listOf(PhotoCandidate("1", "a", 1, 1))
        assertTrue(pickRecentPhotos(one, 0).isEmpty())
        assertTrue(pickRecentPhotos(one, -1).isEmpty())
        assertEquals(listOf("1"), pickRecentPhotos(one, 5).map { it.id })
    }

    @Test
    fun photoNewFrameRoundTripsThumbAndStripsPath() {
        val raw = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte(), 1)
        val frame = checkNotNull(Protocol.photoNew("9", "a/../../Screenshot.png", 1_700_000_000_000L, raw))
        val o = JSONObject(frame)
        assertEquals("photo_new", o.getString("type"))
        assertEquals("9", o.getString("id"))
        assertEquals("Screenshot.png", o.getString("name"))
        assertEquals(1_700_000_000_000L, o.getLong("taken"))
        assertEquals(5, o.length())
        val thumb = o.getString("thumb")
        assertFalse(thumb.contains("\n"))
        assertEquals(raw.toList(), Base64.getDecoder().decode(thumb).toList())
        assertNull(Protocol.parsePhotoRequest(frame))

        val hidden = JSONObject(Protocol.photoNew("1", "../x", 0L, byteArrayOf(1))!!)
        assertEquals("x", hidden.getString("name"))
        assertEquals(0L, hidden.getLong("taken"))

        val emptyName = JSONObject(Protocol.photoNew("1", "", 3L, byteArrayOf(2))!!)
        assertEquals("file", emptyName.getString("name"))
    }

    @Test
    fun photoNewRejectsEmptyIdAndThumbOutsideTheLimit() {
        assertNull(Protocol.photoNew("", "a.jpg", 1L, byteArrayOf(1)))
        assertNull(Protocol.photoNew("1", "a.jpg", 1L, ByteArray(0)))
        assertNull(Protocol.photoNew("1", "a.jpg", 1L, ByteArray(Protocol.PHOTO_THUMB_MAX_BYTES + 1)))
        assertNotNull(Protocol.photoNew("1", "a.jpg", 1L, ByteArray(Protocol.PHOTO_THUMB_MAX_BYTES)))
    }

    @Test
    fun parsePhotoRequest() {
        val ok = JSONObject().put("type", "photo_request").put("id", "42").put("extra", 1).toString()
        assertEquals("42", Protocol.parsePhotoRequest(ok))

        val padded = JSONObject().put("type", "photo_request").put("id", "  42  ").toString()
        assertEquals("42", Protocol.parsePhotoRequest(padded))

        assertNull(Protocol.parsePhotoRequest("not json"))
        assertNull(Protocol.parsePhotoRequest(""))
        assertNull(Protocol.parsePhotoRequest(JSONObject().put("type", "ping").toString()))
        assertNull(Protocol.parsePhotoRequest(JSONObject().put("type", "photo_new").put("id", "1").toString()))
        assertNull(Protocol.parsePhotoRequest(JSONObject().put("type", "photo_request").toString()))
        assertNull(Protocol.parsePhotoRequest(JSONObject().put("type", "photo_request").put("id", "").toString()))
        assertNull(Protocol.parsePhotoRequest(JSONObject().put("type", "photo_request").put("id", "   ").toString()))
        assertNull(Protocol.parsePhotoRequest(JSONObject().put("type", "photo_request").put("id", 42).toString()))
        assertNull(Protocol.parsePhotoRequest(JSONObject().put("type", "photo_request").put("id", true).toString()))
        assertNull(
            Protocol.parsePhotoRequest(
                JSONObject().put("type", "photo_request").put("id", JSONObject.NULL).toString(),
            ),
        )

        val request = JSONObject().put("type", "photo_request").put("id", "42").toString()
        assertNull(Protocol.dismissId(request))
        assertFalse(Protocol.isRing(request))
        assertFalse(Protocol.isRingStop(request))
        assertNull(Protocol.callAction(request))
        assertNull(Protocol.clipboardText(request))
        assertNull(Protocol.replyRequest(request))
    }

    @Test
    fun thumbnailFailureHoldsTheWatermarkSoALaterScanStillSeesIt() {
        val log = PhotoAnnounceLog()
        log.skip(50)
        log.markRetry("9", 100)
        log.markSent("12", 1_200)
        assertFalse("a failed thumbnail is not announced", log.isAnnounced("9"))
        assertEquals(setOf("9"), log.pendingIds())
        assertTrue(log.isAnnounced("12"))
        assertTrue("watermark stays before the photo that still needs a thumbnail", log.watermarkSec < 100L)
        val since = (log.watermarkSec - 1).coerceAtLeast(0L)
        assertTrue(since <= 100L)

        log.markSent("9", 100)
        assertTrue(log.isAnnounced("9"))
        assertTrue(log.pendingIds().isEmpty())
        assertEquals(1_200L, log.watermarkSec)
    }

    @Test
    fun markRetryDoesNotUnsendAndBlankIdsAreIgnored() {
        val log = PhotoAnnounceLog()
        log.markSent("1", 10)
        log.markRetry("1", 10)
        assertTrue(log.isAnnounced("1"))
        assertTrue(log.pendingIds().isEmpty())
        log.markRetry("", 1)
        log.markSent("", 1)
        assertFalse(log.isAnnounced(""))
        log.reset()
        assertFalse(log.isAnnounced("1"))
        assertEquals(0L, log.watermarkSec)
        assertTrue(log.pendingIds().isEmpty())
    }

    @Test
    fun droppingAPendingPhotoReleasesTheWatermarkWithoutAnnouncing() {
        val log = PhotoAnnounceLog()
        log.markRetry("4", 80)
        log.markSent("5", 90)
        assertTrue(log.watermarkSec < 80L)
        log.drop("4")
        assertFalse(log.isAnnounced("4"))
        assertTrue(log.pendingIds().isEmpty())
        assertEquals(90L, log.watermarkSec)
    }
}
