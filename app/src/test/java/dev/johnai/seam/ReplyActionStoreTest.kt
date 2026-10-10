package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded reply-action cache used when the computer answers a notification. */
class ReplyActionStoreTest {
    @Test
    fun remembersReplyableAndNonReplyable() {
        val store = ReplyActionStore<String>(maxSize = 3)
        store.remember("a", "reply-a")
        store.remember("b", null)
        assertEquals(ReplyHit(true, "reply-a"), store.hit("a"))
        assertEquals(ReplyHit(true, null), store.hit("b"))
        assertEquals(ReplyHit(false, null), store.hit("missing"))
        assertTrue(store.hit("a").known)
        assertFalse(store.hit("missing").known)
        assertNull(store.hit("b").action)

        store.remember("a", null)
        assertEquals(ReplyHit(true, null), store.hit("a"))
    }

    @Test
    fun forgetDropsOnlyThatKey() {
        val store = ReplyActionStore<String>()
        store.remember("a", "reply-a")
        store.remember("b", "keep")
        store.forget("a")
        store.forget("never")
        assertEquals(ReplyHit(false, null), store.hit("a"))
        assertEquals(ReplyHit(true, "keep"), store.hit("b"))
        assertEquals(1, store.size())
    }

    @Test
    fun keepsOnlyTheLastMaxAndRefreshesOnUpdate() {
        val store = ReplyActionStore<String>(maxSize = 2)
        store.remember("a", "1")
        store.remember("b", "2")
        store.remember("a", "1b")
        assertEquals(2, store.size())
        store.remember("c", "3")
        assertEquals(ReplyHit(false, null), store.hit("b"))
        assertEquals(ReplyHit(true, "1b"), store.hit("a"))
        assertEquals(ReplyHit(true, "3"), store.hit("c"))
        assertEquals(2, store.size())
    }

    @Test
    fun defaultBoundIs200() {
        val store = ReplyActionStore<Int>()
        for (i in 0 until 200) store.remember("k$i", i)
        assertEquals(200, store.size())
        assertEquals(ReplyHit(true, 0), store.hit("k0"))
        store.remember("extra", 1)
        assertEquals(ReplyHit(false, null), store.hit("k0"))
        assertEquals(200, store.size())
        assertEquals(ReplyHit(true, 1), store.hit("extra"))
    }

    @Test
    fun forgetIfSameKeepsAReplacement() {
        val store = ReplyActionStore<Any>()
        val old = Any()
        val newer = Any()
        store.remember("a", old)
        store.forgetIfSame("a", Any())
        assertTrue(store.hit("a").action === old)
        store.remember("a", newer)
        store.forgetIfSame("a", old)
        assertTrue(store.hit("a").action === newer)
        store.forgetIfSame("a", newer)
        assertFalse(store.hit("a").known)
    }
}
