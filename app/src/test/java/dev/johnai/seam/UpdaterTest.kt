package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private val latest = """
        {"tag_name":"v0.1.42","draft":false,"prerelease":false,
         "assets":[
           {"name":"notes.txt","browser_download_url":"https://example.com/notes.txt","size":10},
           {"name":"Seam-0.1.42.apk","browser_download_url":"https://github.com/JohnAI-dev/seam-android/releases/download/v0.1.42/Seam-0.1.42.apk","size":1234567}
         ]}
    """.trimIndent()

    @Test
    fun parsesTagAndApkAsset() {
        val r = Updater.parseLatest(latest)!!
        assertEquals(42L, r.versionCode)
        assertEquals("0.1.42", r.versionName)
        assertTrue(r.apkUrl.endsWith("/Seam-0.1.42.apk"))
        assertEquals(1234567L, r.size)
    }

    @Test
    fun onlyNewerVersionsInstall() {
        val r = Updater.parseLatest(latest)!!
        assertTrue(Updater.isNewer(r, 41))
        assertFalse(Updater.isNewer(r, 42))
        assertFalse(Updater.isNewer(r, 43))
    }

    @Test
    fun rejectsReleasesWithoutApkOrWithOddTagsOrDrafts() {
        assertNull(Updater.parseLatest("""{"tag_name":"v0.1.5","assets":[]}"""))
        assertNull(Updater.parseLatest("""{"tag_name":"build-20261009","assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
        assertNull(Updater.parseLatest("""{"tag_name":"v0.1.5","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
        assertNull(Updater.parseLatest("""{"tag_name":"v0.1.5","draft":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
        assertNull(Updater.parseLatest("not json"))
    }
}
