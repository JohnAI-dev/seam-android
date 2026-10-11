package dev.johnai.seam

/**
 * Which recent photos to announce (protocol/PROTOCOL.md, "Recent photos").
 * No Android types, so the selection runs in JVM unit tests.
 *
 * [PhotoCandidate.recent] is when the image was saved, in ms. [PhotoCandidate.taken] is the
 * timestamp a photo_new frame reports. [pickRecentPhotos] returns the [limit] most recent
 * photos, oldest first.
 */
data class PhotoCandidate(
    val id: String,
    val name: String,
    val taken: Long,
    val recent: Long = taken,
)

fun pickRecentPhotos(
    photos: List<PhotoCandidate>,
    limit: Int = Protocol.PHOTO_RECENT_LIMIT,
): List<PhotoCandidate> {
    if (limit <= 0 || photos.isEmpty()) return emptyList()
    val best = HashMap<String, PhotoCandidate>(photos.size)
    for (photo in photos) {
        if (photo.id.isEmpty()) continue
        val prev = best[photo.id]
        if (prev == null || photo.recent > prev.recent ||
            (photo.recent == prev.recent && photo.taken > prev.taken)
        ) {
            best[photo.id] = photo
        }
    }
    if (best.isEmpty()) return emptyList()
    val sorted = best.values.sortedWith(
        compareBy<PhotoCandidate> { it.recent }
            .thenBy { it.id.toLongOrNull() ?: Long.MIN_VALUE }
            .thenBy { it.id },
    )
    return if (sorted.size <= limit) sorted else sorted.takeLast(limit)
}

/**
 * Which photo_new frames have actually been sent (protocol/PROTOCOL.md, "Recent photos").
 *
 * An id is announced only after the frame is written. A thumbnail that is not ready stays
 * pending and holds [watermarkSec] strictly before that photo, so a later date scan still
 * sees it. [pendingIds] is the backstop when a scan's date window would otherwise miss it.
 */
class PhotoAnnounceLog {
    private val announced = HashSet<String>()
    private val pendingSec = HashMap<String, Long>()
    private var highWater = 0L

    var watermarkSec: Long = 0L
        private set

    fun isAnnounced(id: String): Boolean = id in announced

    fun pendingIds(): Set<String> = pendingSec.keys.toSet()

    fun reset() {
        announced.clear()
        pendingSec.clear()
        highWater = 0L
        watermarkSec = 0L
    }

    /** An older photo this batch will not announce. Moves the watermark, but not past a pending one. */
    fun skip(addedSec: Long) {
        if (addedSec > highWater) highWater = addedSec
        publish()
    }

    /** Call only after photo_new was written on the current session. */
    fun markSent(id: String, addedSec: Long) {
        if (id.isEmpty()) return
        announced.add(id)
        pendingSec.remove(id)
        if (addedSec > highWater) highWater = addedSec
        publish()
    }

    /** Thumbnail failed or the frame was rejected. Does not announce [id]. */
    fun markRetry(id: String, addedSec: Long) {
        if (id.isEmpty() || id in announced) return
        pendingSec[id] = addedSec
        publish()
    }

    /** Stop retrying [id] without announcing it, so the watermark can move on. */
    fun drop(id: String) {
        if (pendingSec.remove(id) != null) publish()
    }

    private fun publish() {
        val cap = pendingSec.values.minOrNull()
        watermarkSec = if (cap != null && highWater >= cap) (cap - 1).coerceAtLeast(0L) else highWater
    }
}
