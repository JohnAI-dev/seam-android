package dev.johnai.seam

/**
 * Reply actions kept by notification key: the last [maxSize] forwarded notifications.
 * A null action means that notification cannot be replied to. Call [forget] when it is removed.
 */
class ReplyActionStore<T>(private val maxSize: Int = 200) {
    private val held = LinkedHashMap<String, Holder<T>>()

    private class Holder<T>(val action: T?)

    fun remember(key: String, action: T?) {
        synchronized(held) {
            held.remove(key)
            held[key] = Holder(action)
            while (held.size > maxSize) {
                val eldest = held.keys.firstOrNull() ?: break
                held.remove(eldest)
            }
        }
    }

    fun forget(key: String) {
        synchronized(held) { held.remove(key) }
    }

    /** Drop [key] only while it still holds this exact [action]. A replacement is kept. */
    fun forgetIfSame(key: String, action: T) {
        synchronized(held) {
            if (held[key]?.action === action) held.remove(key)
        }
    }

    fun hit(key: String): ReplyHit<T> = synchronized(held) {
        val holder = held[key] ?: return ReplyHit(false, null)
        ReplyHit(true, holder.action)
    }

    fun size(): Int = synchronized(held) { held.size }
}

/** Whether a notification key is still known, and its reply action if it has one. */
data class ReplyHit<T>(val known: Boolean, val action: T?)
