package dev.johnai.seam

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Receiving a find-my-phone ring posts the Find my phone notification and restores volume. */
@RunWith(AndroidJUnit4::class)
class FindMyPhoneTest {
    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    @After
    fun stopRinging() {
        PhoneFinder.stop(context)
    }

    @Test
    fun receivingRingPostsFindMyPhoneNotification() {
        val audio = context.getSystemService(AudioManager::class.java)
        assertNotNull(audio)
        val before = audio!!.getStreamVolume(AudioManager.STREAM_ALARM)
        PhoneFinder.handleFrame(context, """{"type":"ping"}""")
        assertFalse(PhoneFinder.isRinging())
        PhoneFinder.handleFrame(context, """{"type":"ring"}""")
        try {
            assertTrue(PhoneFinder.isRinging())
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = nm.getNotificationChannel(PhoneFinder.CHANNEL_ID)
            assertNotNull(channel)
            assertEquals("Find my phone", channel!!.name.toString())
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            val posted = nm.activeNotifications.firstOrNull { it.id == PhoneFinder.NOTIFICATION_ID }
            assertNotNull("ring posts a notification", posted)
            val n = posted!!.notification
            assertEquals(PhoneFinder.CHANNEL_ID, n.channelId)
            assertEquals(Notification.CATEGORY_ALARM, n.category)
            assertEquals("Find my phone", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertNotNull(n.contentIntent)
            val found = n.actions?.firstOrNull { it.title?.toString() == "Found it" }
            assertNotNull(found)
            val fullScreenAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nm.canUseFullScreenIntent()
            } else {
                true
            }
            if (fullScreenAllowed) assertNotNull(n.fullScreenIntent) else assertNull(n.fullScreenIntent)
        } finally {
            PhoneFinder.handleFrame(context, """{"type":"ring_stop"}""")
        }
        assertFalse(PhoneFinder.isRinging())
        assertEquals(before, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(nm.activeNotifications.none { it.id == PhoneFinder.NOTIFICATION_ID })
    }
}
