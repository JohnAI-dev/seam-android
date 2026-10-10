package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/** Checks the Kotlin protocol against the vectors shared with seam-desktop (protocol/vectors.json). */
class ProtocolTest {
    private val vectors = JSONObject(File("../protocol/vectors.json").readText())

    @Test
    fun proofsMatchSharedVectors() {
        val proofs = vectors.getJSONArray("proofs")
        for (i in 0 until proofs.length()) {
            val v = proofs.getJSONObject(i)
            val key = Base64.getUrlDecoder().decode(v.getString("key"))
            assertEquals(
                v.getString("proof"),
                Protocol.proof(key, v.getString("nonce"), v.getString("device_id")),
            )
        }
    }

    @Test
    fun parsesSharedPairingLink() {
        val case = vectors.getJSONObject("pair_uri")
        val expected = case.getJSONObject("parsed")
        val info = PairingInfo.parse(case.getString("uri"))
        assertEquals(listOf("192.168.1.10", "10.0.0.5"), info.hosts)
        assertEquals(expected.getInt("port"), info.port)
        assertEquals(expected.getString("fingerprint"), info.fingerprint)
        assertEquals(expected.getString("key"), info.keyB64)
        assertEquals(32, info.key.size)
        assertEquals("Johns MacBook Air", info.name)
    }

    @Test
    fun rejectsSharedInvalidLinks() {
        val bad = vectors.getJSONArray("invalid_pair_uris")
        for (i in 0 until bad.length()) {
            val uri = bad.getString(i)
            try {
                PairingInfo.parse(uri)
                fail("should reject $uri")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun messagesHaveTheFieldsTheDesktopExpects() {
        val n = JSONObject(Protocol.notification("k", "com.whatsapp", "WhatsApp", "Anna", "Hei", 42L))
        assertEquals("notification", n.getString("type"))
        assertEquals("k", n.getString("id"))
        assertEquals("com.whatsapp", n.getString("app"))
        assertEquals("WhatsApp", n.getString("app_name"))
        assertEquals("Anna", n.getString("title"))
        assertEquals("Hei", n.getString("text"))
        assertEquals(42L, n.getLong("time"))
        assertEquals(false, n.getBoolean("replyable"))

        val replyable = JSONObject(
            Protocol.notification("k", "com.whatsapp", "WhatsApp", "Anna", "Hei", 42L, replyable = true),
        )
        assertEquals("notification", replyable.getString("type"))
        assertEquals("k", replyable.getString("id"))
        assertEquals("Hei", replyable.getString("text"))
        assertEquals(42L, replyable.getLong("time"))
        assertEquals(true, replyable.getBoolean("replyable"))

        val b = JSONObject(Protocol.battery(150, true))
        assertEquals("battery", b.getString("type"))
        assertEquals(100, b.getInt("level"))
        assertEquals(true, b.getBoolean("charging"))

        val h = JSONObject(Protocol.hello("id", "Galaxy", "abc"))
        assertEquals("hello", h.getString("type"))
        assertEquals("id", h.getString("device_id"))
        assertEquals("abc", h.getString("proof"))

        assertEquals("notification_removed", JSONObject(Protocol.notificationRemoved("k")).getString("type"))
        assertEquals("pong", JSONObject(Protocol.pong()).getString("type"))
    }

    @Test
    fun clipboardHasTypeTextAndTruncates() {
        val c = JSONObject(Protocol.clipboard("copied"))
        assertEquals("clipboard", c.getString("type"))
        assertEquals("copied", c.getString("text"))

        val limit = 100_000
        assertEquals(limit, Protocol.CLIPBOARD_MAX_CHARS)
        assertEquals(
            "y".repeat(limit),
            JSONObject(Protocol.clipboard("y".repeat(limit))).getString("text")
        )

        val over = "x".repeat(limit + 1)
        val sent = JSONObject(Protocol.clipboard(over))
        assertEquals("clipboard", sent.getString("type"))
        assertEquals(limit, sent.getString("text").length)
        assertEquals("x".repeat(limit), sent.getString("text"))

        val incoming = JSONObject().put("type", "clipboard").put("text", over).toString()
        assertEquals("x".repeat(limit), Protocol.clipboardText(incoming))
        assertEquals("", Protocol.clipboardText(JSONObject().put("type", "clipboard").put("text", "").toString()))
        assertNull(Protocol.clipboardText(JSONObject().put("type", "clipboard").put("text", 1).toString()))
        assertNull(Protocol.clipboardText(JSONObject().put("type", "clipboard").toString()))
        assertNull(Protocol.clipboardText(JSONObject().put("type", "clipboard").put("text", JSONObject.NULL).toString()))
        assertNull(Protocol.clipboardText(JSONObject().put("type", "ping").put("text", "no").toString()))
        assertNull(Protocol.clipboardText("not json"))
    }

    @Test
    fun parseToleratesGarbageAndUnknownTypes() {
        assertNull(Protocol.parse("not json"))
        assertNull(Protocol.parse("""{"no_type":1}"""))
        assertEquals("from_the_future", Protocol.parse("""{"type":"from_the_future"}""")!!.getString("type"))
    }

    @Test
    fun dismissIdFromDismissFrameOnly() {
        assertEquals("0|com.example|1|tag|100", Protocol.dismissId("""{"type":"dismiss","id":"0|com.example|1|tag|100"}"""))
        assertNull(Protocol.dismissId("""{"type":"ping"}"""))
        assertNull(Protocol.dismissId("""{"type":"notification","id":"kept"}"""))
        assertNull(Protocol.dismissId("""{"type":"dismiss"}"""))
        assertNull(Protocol.dismissId("""{"type":"dismiss","id":""}"""))
        assertNull(Protocol.dismissId("""{"type":"dismiss","id":null}"""))
        assertNull(Protocol.dismissId("""{"id":"x"}"""))
        assertNull(Protocol.dismissId("not json"))
    }

    @Test
    fun replyRequestAndReplyResult() {
        assertEquals("notification is gone", Protocol.REPLY_ERROR_GONE)
        assertEquals("cannot reply to this notification", Protocol.REPLY_ERROR_CANNOT)
        assertEquals(5_000, Protocol.REPLY_MAX_CHARS)

        val ok = JSONObject(Protocol.replyResult("0|com.example|1|tag|100", true))
        assertEquals("reply_result", ok.getString("type"))
        assertEquals("0|com.example|1|tag|100", ok.getString("id"))
        assertEquals(true, ok.getBoolean("ok"))
        assertFalse(ok.has("error"))

        val okIgnoresError = JSONObject(Protocol.replyResult("k", true, Protocol.REPLY_ERROR_GONE))
        assertEquals(true, okIgnoresError.getBoolean("ok"))
        assertFalse(okIgnoresError.has("error"))

        val gone = JSONObject(Protocol.replyResult("k", false, Protocol.REPLY_ERROR_GONE))
        assertEquals("reply_result", gone.getString("type"))
        assertEquals("k", gone.getString("id"))
        assertEquals(false, gone.getBoolean("ok"))
        assertEquals(Protocol.REPLY_ERROR_GONE, gone.getString("error"))

        val cannot = JSONObject(Protocol.replyResult("k", false, Protocol.REPLY_ERROR_CANNOT))
        assertEquals(false, cannot.getBoolean("ok"))
        assertEquals(Protocol.REPLY_ERROR_CANNOT, cannot.getString("error"))

        val noReason = JSONObject(Protocol.replyResult("k", false))
        assertEquals(false, noReason.getBoolean("ok"))
        assertFalse(noReason.has("error"))

        assertEquals(
            Protocol.ReplyRequest("0|com.example|1|tag|100", "On my way"),
            Protocol.replyRequest(
                JSONObject().put("type", "reply").put("id", "0|com.example|1|tag|100")
                    .put("text", "On my way").toString(),
            ),
        )

        val exact = "y".repeat(Protocol.REPLY_MAX_CHARS)
        val kept = Protocol.replyRequest(
            JSONObject().put("type", "reply").put("id", "k").put("text", exact).toString(),
        )
        assertEquals(exact, kept!!.text)

        val over = "z".repeat(Protocol.REPLY_MAX_CHARS + 40)
        val truncated = Protocol.replyRequest(
            JSONObject().put("type", "reply").put("id", "k").put("text", over).toString(),
        )
        assertEquals(Protocol.REPLY_MAX_CHARS, truncated!!.text.length)
        assertEquals("z".repeat(Protocol.REPLY_MAX_CHARS), truncated.text)

        assertEquals(
            Protocol.ReplyRequest("k", ""),
            Protocol.replyRequest(JSONObject().put("type", "reply").put("id", "k").put("text", "").toString()),
        )
        val extra = Protocol.replyRequest(
            JSONObject().put("type", "reply").put("id", "k").put("text", "hi").put("extra", 1).toString(),
        )
        assertEquals("hi", extra!!.text)

        assertNull(Protocol.replyRequest(JSONObject().put("type", "ping").toString()))
        assertNull(Protocol.replyRequest(JSONObject().put("type", "dismiss").put("id", "k").toString()))
        assertNull(
            Protocol.replyRequest(
                JSONObject().put("type", "notification").put("id", "k").put("text", "hi").toString(),
            ),
        )
        assertNull(Protocol.replyRequest(JSONObject().put("type", "reply").toString()))
        assertNull(Protocol.replyRequest(JSONObject().put("type", "reply").put("id", "k").toString()))
        assertNull(Protocol.replyRequest(JSONObject().put("type", "reply").put("text", "hi").toString()))
        assertNull(
            Protocol.replyRequest(
                JSONObject().put("type", "reply").put("id", "").put("text", "hi").toString(),
            ),
        )
        assertNull(
            Protocol.replyRequest(
                JSONObject().put("type", "reply").put("id", JSONObject.NULL).put("text", "hi").toString(),
            ),
        )
        assertNull(
            Protocol.replyRequest(
                JSONObject().put("type", "reply").put("id", "k").put("text", JSONObject.NULL).toString(),
            ),
        )
        assertNull(
            Protocol.replyRequest(
                JSONObject().put("type", "reply").put("id", 1).put("text", "hi").toString(),
            ),
        )
        assertNull(
            Protocol.replyRequest(
                JSONObject().put("type", "reply").put("id", "k").put("text", 2).toString(),
            ),
        )
        assertNull(
            Protocol.replyRequest(
                JSONObject().put("type", "reply").put("id", "k").put("text", true).toString(),
            ),
        )
        assertNull(Protocol.replyRequest(JSONObject().put("id", "k").put("text", "hi").toString()))
        assertNull(Protocol.replyRequest("not json"))
        assertNull(Protocol.replyRequest(""))
    }

    @Test
    fun ringAndRingStop() {
        assertEquals(60_000L, Protocol.RING_DURATION_MS)

        assertTrue(Protocol.isRing("""{"type":"ring"}"""))
        assertTrue(Protocol.isRing("""{"type":"ring","v":1}"""))
        assertFalse(Protocol.isRing("""{"type":"ring_stop"}"""))
        assertFalse(Protocol.isRing("""{"type":"ring_stop_now"}"""))
        assertFalse(Protocol.isRing("""{"type":"Ring"}"""))
        assertFalse(Protocol.isRing("""{"type":"ping"}"""))
        assertFalse(Protocol.isRing("""{"type":"dismiss","id":"x"}"""))
        assertFalse(Protocol.isRing("""{"type":"notification","id":"k"}"""))
        assertFalse(Protocol.isRing("""{"type":""}"""))
        assertFalse(Protocol.isRing("""{"type":1}"""))
        assertFalse(Protocol.isRing("""{"type":null}"""))
        assertFalse(Protocol.isRing("""{"no_type":1}"""))
        assertFalse(Protocol.isRing("not json"))
        assertFalse(Protocol.isRing(""))
        assertNull(Protocol.dismissId("""{"type":"ring"}"""))
        assertNull(Protocol.clipboardText("""{"type":"ring"}"""))
        assertNull(Protocol.replyRequest("""{"type":"ring"}"""))

        assertTrue(Protocol.isRingStop("""{"type":"ring_stop"}"""))
        assertTrue(Protocol.isRingStop("""{"type":"ring_stop","extra":true}"""))
        assertFalse(Protocol.isRingStop("""{"type":"ring"}"""))
        assertFalse(Protocol.isRingStop("""{"type":"ring-stop"}"""))
        assertFalse(Protocol.isRingStop("""{"type":"RING_STOP"}"""))
        assertFalse(Protocol.isRingStop("""{"type":"ping"}"""))
        assertFalse(Protocol.isRingStop("""{"type":"dismiss","id":"x"}"""))
        assertFalse(Protocol.isRingStop("""{"type":1}"""))
        assertFalse(Protocol.isRingStop("""{"type":null}"""))
        assertFalse(Protocol.isRingStop("""{"no_type":1}"""))
        assertFalse(Protocol.isRingStop("not json"))
        assertFalse(Protocol.isRingStop(""))
        assertNull(Protocol.dismissId("""{"type":"ring_stop"}"""))
        assertNull(Protocol.replyRequest("""{"type":"ring_stop","id":"k","text":"hi"}"""))
    }

    @Test
    fun pinnedTrustManagerRejectsMissingCertificate() {
        try {
            PinnedTrustManager("00".repeat(32)).checkServerTrusted(emptyArray(), "ECDHE")
            fail("must reject")
        } catch (e: java.security.cert.CertificateException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }
}
