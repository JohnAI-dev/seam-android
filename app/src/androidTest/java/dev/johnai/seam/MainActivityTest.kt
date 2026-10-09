package dev.johnai.seam

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real app on an emulator. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    // Otherwise the notification permission dialog pauses the screen under test.
    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Same link as protocol/vectors.json "pair_uri".
    private val pairLink = "seam://pair?v=1&host=192.168.1.10,10.0.0.5&port=47100&fp=" +
        "ab".repeat(32) + "&key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&name=Johns%20MacBook%20Air"

    @Before
    @After
    fun clearPairing() {
        LinkHub.stopService(context)
        PairingStore(context).pairing = null
    }

    @Test
    fun startsAndExplainsHowToPair() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val status = a.window.decorView.findViewWithTag<TextView>("status")
                assertNotNull("status text is shown", status)
                assertTrue(status.text.contains("Pair phone"))
            }
        }
        assertNull(PairingStore(context).pairing)
    }

    @Test
    fun scanningAPairingLinkPairsThePhone() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pairLink))
            .setClass(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                val paired = a.window.decorView.findViewWithTag<TextView>("paired")
                assertEquals("Paired with Johns MacBook Air", paired.text.toString())
            }
        }
        val saved = PairingStore(context).pairing
        assertNotNull(saved)
        assertEquals(listOf("192.168.1.10", "10.0.0.5"), saved!!.hosts)
        assertEquals(47100, saved.port)
        assertEquals("ab".repeat(32), saved.fingerprint)
    }

    @Test
    fun anInvalidLinkDoesNotPair() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("seam://pair?v=1&host=1.2.3.4"))
            .setClass(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                val notice = a.window.decorView.findViewWithTag<TextView>("notice")
                assertTrue(notice.text.contains("isn't a valid Seam pairing link"))
            }
        }
        assertNull(PairingStore(context).pairing)
    }
}
