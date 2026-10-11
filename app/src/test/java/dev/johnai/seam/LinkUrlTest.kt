package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.IDN
import java.util.Locale

/** URL detection and open_url validation. See protocol/PROTOCOL.md, "Opening links". */
class LinkUrlTest {
    @Test
    fun limitIs4096() {
        assertEquals(4096, Protocol.OPEN_URL_MAX_CHARS)
    }

    @Test
    fun singleHttpUrl() {
        assertEquals("https://example.com", LinkUrls.singleHttpUrl("https://example.com"))
        assertEquals("http://example.com/a", LinkUrls.singleHttpUrl("http://example.com/a"))
        assertEquals(
            "https://example.com/a?x=1&y=2#f",
            LinkUrls.singleHttpUrl("https://example.com/a?x=1&y=2#f"),
        )
        assertEquals("https://example.com/a", LinkUrls.singleHttpUrl("  https://example.com/a  "))
        assertEquals("https://example.com/a", LinkUrls.singleHttpUrl("https://example.com/a\n"))
        assertEquals("https://example.com/a", LinkUrls.singleHttpUrl("https://example.com/a\r\n"))
        assertEquals("http://example.com", LinkUrls.singleHttpUrl("HTTP://example.com"))
        assertEquals("https://Example.COM/Path", LinkUrls.singleHttpUrl("HTTPS://Example.COM/Path"))
        assertEquals("example.com", LinkUrls.hostOf("HTTPS://Example.COM/Path"))
        assertEquals("http://192.168.1.10/", LinkUrls.singleHttpUrl("http://192.168.1.10/"))
        assertEquals("http://[::1]/", LinkUrls.singleHttpUrl("http://[::1]/"))
        assertEquals("::1", LinkUrls.hostOf("http://[::1]/"))
        assertEquals("example.com", LinkUrls.hostOf("https://user:pass@example.com:8443/a"))
        assertEquals("evil.com", LinkUrls.hostOf("https://example.com@evil.com/phish"))
    }

    @Test
    fun textContainingAUrlIsNotASingleUrl() {
        val url = "https://example.com/a"
        assertEquals(url, LinkUrls.singleHttpUrl(url))
        assertNull(LinkUrls.singleHttpUrl("see $url"))
        assertNull(LinkUrls.singleHttpUrl("$url thanks"))
        assertNull(LinkUrls.singleHttpUrl("please open $url now"))
        assertNull(LinkUrls.singleHttpUrl(url + "\n" + "more"))
        assertNull(LinkUrls.singleHttpUrl("Title\n" + url))
        assertNull(LinkUrls.singleHttpUrl("$url $url"))
        val quote = '"'.toString()
        assertNull(LinkUrls.singleHttpUrl(quote + url + quote))
        assertNull(LinkUrls.singleHttpUrl("<$url>"))
        assertNull(LinkUrls.singleHttpUrl("example.com"))
        assertNull(LinkUrls.singleHttpUrl("www.example.com/path"))
        assertNull(Protocol.openUrl("see $url"))
    }

    @Test
    fun rejectsJavascriptIntentAndFile() {
        val bad = listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "javascript:https://example.com",
            "javascript://example.com/%0Aalert(1)",
            "intent://example.com#Intent;scheme=https;end",
            "intent:https://example.com",
            "INTENT://scan/#Intent;scheme=http;end",
            "file:///etc/passwd",
            "file://localhost/etc/passwd",
            "FILE:///sdcard/a.jpg",
            "content://com.android.contacts/1",
            "ftp://example.com/a",
            "data:text/html,hi",
            "about:blank",
        )
        for (url in bad) {
            assertNull(url, LinkUrls.singleHttpUrl(url))
            assertNull(url, LinkUrls.openableHttpUrl(url))
            assertNull(url, LinkUrls.hostOf(url))
            assertNull(url, Protocol.openUrl(url))
            val frame = JSONObject().put("type", "open_url").put("url", url).toString()
            assertNull(url, Protocol.parseOpenUrl(frame))
        }
    }

    @Test
    fun lengthLimit() {
        val max = Protocol.OPEN_URL_MAX_CHARS
        val prefix = "https://example.com/"
        val ok = prefix + "a".repeat(max - prefix.length)
        assertEquals(max, ok.length)
        assertEquals(ok, LinkUrls.singleHttpUrl(ok))
        assertEquals(ok, LinkUrls.openableHttpUrl(ok))
        val frame = checkNotNull(Protocol.openUrl(ok))
        assertEquals(ok, Protocol.parseOpenUrl(frame))

        val tooLong = ok + "b"
        assertEquals(max + 1, tooLong.length)
        assertNull(LinkUrls.singleHttpUrl(tooLong))
        assertNull(LinkUrls.openableHttpUrl(tooLong))
        assertNull(Protocol.openUrl(tooLong))
        val rejected = JSONObject().put("type", "open_url").put("url", tooLong).toString()
        assertNull(Protocol.parseOpenUrl(rejected))
    }

    @Test
    fun openUrlFrameRoundTrip() {
        val url = "https://example.com/a?x=1&y=2#frag"
        val frame = checkNotNull(Protocol.openUrl(url))
        val o = JSONObject(frame)
        assertEquals("open_url", o.getString("type"))
        assertEquals(url, o.getString("url"))
        assertEquals(2, o.length())
        assertEquals(url, Protocol.parseOpenUrl(frame))
        assertNull(Protocol.clipboardText(frame))
        assertNull(Protocol.dismissId(frame))
        assertFalse(Protocol.isRing(frame))
        assertFalse(Protocol.isRingStop(frame))

        val extra = JSONObject().put("type", "open_url").put("url", url).put("extra", 1).toString()
        assertEquals(url, Protocol.parseOpenUrl(extra))
        assertNull(Protocol.parseOpenUrl(JSONObject().put("type", "open_url").toString()))
        assertNull(Protocol.parseOpenUrl(JSONObject().put("type", "open_url").put("url", "").toString()))
        assertNull(Protocol.parseOpenUrl(JSONObject().put("type", "open_url").put("url", 1).toString()))
        assertNull(
            Protocol.parseOpenUrl(
                JSONObject().put("type", "clipboard").put("text", "https://example.com").toString(),
            ),
        )
        assertNull(Protocol.parseOpenUrl("not json"))
        assertNull(Protocol.parseOpenUrl(""))
    }

    @Test
    fun hostIsNotTakenFromUserinfoAndEncodedHostIsRejected() {
        assertEquals("evil.com", LinkUrls.hostOf("https://example.com@evil.com/phish"))
        assertEquals("example.com", LinkUrls.hostOf("https://user%40name@example.com/a"))
        assertNull(LinkUrls.openableHttpUrl("https://ex%61mple.com/"))
        assertNull(LinkUrls.openableHttpUrl("https://example.com%2eevil.com/"))
        assertEquals("https://example.com/a%20b", LinkUrls.singleHttpUrl("https://example.com/a%20b"))
        assertNull(LinkUrls.singleHttpUrl("https://example.com/a b"))
        val slash = 0x5C.toChar()
        assertNull(LinkUrls.singleHttpUrl("https://example.com" + slash + "@evil.com"))
    }

    @Test
    fun blankAndHostlessAreRejected() {
        assertNull(LinkUrls.singleHttpUrl(""))
        assertNull(LinkUrls.singleHttpUrl("   "))
        assertNull(LinkUrls.singleHttpUrl("https://"))
        assertNull(LinkUrls.singleHttpUrl("http://"))
        assertNull(LinkUrls.singleHttpUrl("https:///no-host"))
        assertNull(LinkUrls.singleHttpUrl("http:/example.com"))
        assertNull(LinkUrls.openableHttpUrl("https://example.com/a b"))
    }

    @Test
    fun rejectsNonCanonicalIpv4ThatABrowserWouldRetarget() {
        val bad = listOf(
            "http://2130706433/",
            "http://2130706433",
            "http://2130706433:8080/",
            "HTTP://2130706433/",
            "http://127.1/",
            "http://127.0.1/",
            "http://127.1:80/",
            "http://0x7f.0.0.1/",
            "http://0x7f000001/",
            "http://0X7F000001/",
            "http://0x7f.0x0.0.0x1/",
            "http://0177.0.0.1/",
            "http://127.0.0.01/",
            "http://127.0.0.010/",
            "http://192.168.001.1/",
            "http://127.0.0.1./",
            "https://example.123/",
        )
        for (url in bad) {
            assertNull(url, LinkUrls.singleHttpUrl(url))
            assertNull(url, LinkUrls.openableHttpUrl(url))
            assertNull(url, LinkUrls.hostOf(url))
            assertNull(url, LinkUrls.displayHost(url))
            assertNull(url, Protocol.openUrl(url))
            val frame = JSONObject().put("type", "open_url").put("url", url).toString()
            assertNull(url, Protocol.parseOpenUrl(frame))
        }
        assertEquals("http://127.0.0.1/", LinkUrls.openableHttpUrl("http://127.0.0.1/"))
        assertEquals("127.0.0.1", LinkUrls.hostOf("http://127.0.0.1/"))
        assertEquals("127.0.0.1:9", LinkUrls.displayHost("http://127.0.0.1:9/"))
        assertEquals(9, LinkUrls.portOf("http://127.0.0.1:9/"))
        assertEquals(-1, LinkUrls.portOf("http://127.0.0.1/"))
        assertEquals("192.168.1.10", LinkUrls.hostOf("http://192.168.1.10/"))
        assertEquals("1.2.3.4.example.com", LinkUrls.hostOf("https://1.2.3.4.example.com/a"))
        assertEquals("123.example.com", LinkUrls.hostOf("https://123.example.com/"))
        assertEquals("localhost:8080", LinkUrls.displayHost("http://localhost:8080/"))
    }

    @Test
    fun nonAsciiHostIsShownAndOpenedAsPunycode() {
        val unicode = "b\u00FCcher.example"
        val ascii = IDN.toASCII(unicode).lowercase(Locale.ROOT)
        assertTrue(ascii.startsWith("xn--"))
        assertFalse(ascii.any { it.code > 127 })
        val raw = "https://$unicode/a"
        assertEquals("https://$ascii/a", LinkUrls.singleHttpUrl(raw))
        assertEquals(ascii, LinkUrls.hostOf(raw))
        assertEquals(ascii, LinkUrls.displayHost(raw))
        assertFalse(LinkUrls.displayHost(raw)!!.any { it.code > 127 })

        val withPort = "https://user:pass@$unicode:8443/a?x=1#f"
        assertEquals("https://user:pass@$ascii:8443/a?x=1#f", LinkUrls.openableHttpUrl(withPort))
        assertEquals(ascii, LinkUrls.hostOf(withPort))
        assertEquals("$ascii:8443", LinkUrls.displayHost(withPort))
        assertEquals(8443, LinkUrls.portOf(withPort))

        val spoof = "\u0435xample.com"
        val spoofAscii = IDN.toASCII(spoof).lowercase(Locale.ROOT)
        assertNotEquals("example.com", spoofAscii)
        assertTrue(spoofAscii.startsWith("xn--"))
        assertEquals(spoofAscii, LinkUrls.hostOf("https://$spoof/"))
        assertEquals("https://$spoofAscii/phish", LinkUrls.openableHttpUrl("https://$spoof/phish"))
        assertFalse(LinkUrls.displayHost("https://$spoof/")!!.contains("\u0435"))
    }

    @Test
    fun displayHostKeepsAnExplicitPortAndBracketsIpv6() {
        assertEquals("example.com:8443", LinkUrls.displayHost("https://example.com:8443/a"))
        assertEquals("example.com", LinkUrls.hostOf("https://example.com:8443/a"))
        assertEquals("example.com", LinkUrls.displayHost("https://user:pass@example.com/a"))
        assertEquals("[::1]", LinkUrls.displayHost("http://[::1]/"))
        assertEquals("[::1]:8080", LinkUrls.displayHost("http://[::1]:8080/a"))
        assertEquals("::1", LinkUrls.hostOf("http://[::1]:8080/a"))
        assertEquals(8080, LinkUrls.portOf("http://[::1]:8080/a"))
    }

    @Test
    fun hostEqualityUsesRootLocale() {
        val previous = Locale.getDefault()
        // Some unit-test JVMs have no Turkish case data, so I.lowercase() may still be "i".
        // Host checks must use Locale.ROOT either way, or a Turkish device drops the open.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals("linkedin.com", LinkUrls.hostOf("https://LINKEDIN.COM/jobs"))
            assertTrue(LinkUrls.hostsEqual("LINKEDIN.COM", "linkedin.com"))
            assertTrue(LinkUrls.hostsEqual("LINKEDIN.COM", LinkUrls.hostOf("https://LINKEDIN.COM/jobs")!!))
            assertTrue(LinkUrls.hostsEqual("[::1]", "::1"))
            assertFalse(LinkUrls.hostsEqual("evil.com", "example.com"))
            assertEquals("i", "I".lowercase(Locale.ROOT))
            assertEquals("linkedin.com", "LINKEDIN.COM".lowercase(Locale.ROOT))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
