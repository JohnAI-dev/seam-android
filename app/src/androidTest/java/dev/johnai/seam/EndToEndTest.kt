package dev.johnai.seam

import android.Manifest
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Talks to a real desktop link (seam-testpeer on the host).
 * Skipped unless the instrumentation argument `seamPairLink` is set.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    @After
    fun clearPairing() {
        LinkHub.stopService(context)
        PairingStore(context).pairing = null
    }

    @Test
    fun linkStaysConnectedAfterNotificationAndClipboard() {
        val link = InstrumentationRegistry.getArguments().getString("seamPairLink")
        assumeTrue("seamPairLink instrumentation argument is not set", !link.isNullOrBlank())
        val info = PairingInfo.parse(link!!)

        val connected = CountDownLatch(1)
        val rejected = AtomicReference<String?>(null)
        val listener: (LinkHub.Status) -> Unit = { status ->
            when (status.state) {
                LinkClient.State.CONNECTED -> connected.countDown()
                LinkClient.State.REJECTED -> {
                    rejected.set(status.detail)
                    connected.countDown()
                }
                else -> Unit
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    // Same path as the dialog's Pair button; no confirmation dialog.
                    activity.savePairing(info)
                    LinkHub.addListener(listener)
                }
                assertEquals(info.fingerprint, PairingStore(context).pairing?.fingerprint)
                assertTrue(
                    "not CONNECTED within 30s (status=${LinkHub.status}, rejected=${rejected.get()})",
                    connected.await(30, TimeUnit.SECONDS),
                )
                assertEquals(
                    "computer rejected the link: ${rejected.get()}",
                    LinkClient.State.CONNECTED,
                    LinkHub.status.state,
                )
                val session = LinkHub.currentSessionId()
                assertNotNull("connected without a session", session)

                LinkHub.send(
                    Protocol.notification(
                        id = "e2e",
                        app = "dev.johnai.seam",
                        appName = "Seam",
                        title = "E2E",
                        text = "end-to-end test",
                        time = System.currentTimeMillis(),
                    ),
                )
                LinkHub.send(Protocol.clipboard("e2e-clipboard"))

                // Frames are written on the link thread; give a bad frame time to drop the session.
                Thread.sleep(2_000)
                assertEquals(
                    "link did not stay connected (status=${LinkHub.status})",
                    LinkClient.State.CONNECTED,
                    LinkHub.status.state,
                )
                assertEquals("session changed after send", session, LinkHub.currentSessionId())
                Log.i(TAG, "E2E-OK")
            }
        } finally {
            LinkHub.removeListener(listener)
        }
    }

    private companion object {
        const val TAG = "EndToEndTest"
    }
}
