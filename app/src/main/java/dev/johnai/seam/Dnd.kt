package dev.johnai.seam

/**
 * Do Not Disturb interruption-filter mapping (protocol/PROTOCOL.md, "Do Not Disturb").
 * No Android types, so this runs in JVM unit tests.
 *
 * Values match android.app.NotificationManager: UNKNOWN 0, ALL 1, PRIORITY 2, NONE 3, ALARMS 4.
 * Priority, alarms, and none are on. All, unknown, and anything else are off.
 * Turning it on sets priority; turning it off sets all. [filterFor] is passed to
 * setInterruptionFilter.
 */
object Dnd {
    const val FILTER_UNKNOWN = 0
    const val FILTER_ALL = 1
    const val FILTER_PRIORITY = 2
    const val FILTER_NONE = 3
    const val FILTER_ALARMS = 4

    /** True when [filter] means Do Not Disturb is on. */
    fun isOn(filter: Int): Boolean = when (filter) {
        FILTER_PRIORITY, FILTER_NONE, FILTER_ALARMS -> true
        else -> false
    }

    /** Filter to apply for a dnd_set: priority when [on], all when off. */
    fun filterFor(on: Boolean): Int = if (on) FILTER_PRIORITY else FILTER_ALL
}
