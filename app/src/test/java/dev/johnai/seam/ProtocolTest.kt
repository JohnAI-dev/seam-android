package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun pinnedTrustManagerRejectsMissingCertificate() {
        try {
            PinnedTrustManager("00".repeat(32)).checkServerTrusted(emptyArray(), "ECDHE")
            fail("must reject")
        } catch (e: java.security.cert.CertificateException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }
}
