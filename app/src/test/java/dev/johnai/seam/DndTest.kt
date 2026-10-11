package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Do Not Disturb filter mapping and dnd_set parsing. See protocol/PROTOCOL.md, "Do Not Disturb". */
class DndTest {
    @Test
    fun filtersMapToOnAndOff() {
        assertFalse(Dnd.isOn(Dnd.FILTER_UNKNOWN))
        assertFalse(Dnd.isOn(Dnd.FILTER_ALL))
        assertTrue(Dnd.isOn(Dnd.FILTER_PRIORITY))
        assertTrue(Dnd.isOn(Dnd.FILTER_NONE))
        assertTrue(Dnd.isOn(Dnd.FILTER_ALARMS))
        assertFalse(Dnd.isOn(-1))
        assertFalse(Dnd.isOn(5))
        assertFalse(Dnd.isOn(99))
    }

    @Test
    fun filterConstantsMatchNotificationManager() {
        assertEquals(0, Dnd.FILTER_UNKNOWN)
        assertEquals(1, Dnd.FILTER_ALL)
        assertEquals(2, Dnd.FILTER_PRIORITY)
        assertEquals(3, Dnd.FILTER_NONE)
        assertEquals(4, Dnd.FILTER_ALARMS)
    }

    @Test
    fun settingUsesPriorityOrAll() {
        assertEquals(Dnd.FILTER_PRIORITY, Dnd.filterFor(true))
        assertEquals(Dnd.FILTER_ALL, Dnd.filterFor(false))
        assertEquals(2, Dnd.filterFor(true))
        assertEquals(1, Dnd.filterFor(false))
    }

    @Test
    fun dndFrameReportsOnOrOff() {
        val frame = Protocol.dnd(true)
        assertFalse(frame.contains("\n"))
        val onJson = JSONObject(frame)
        assertEquals("dnd", onJson.getString("type"))
        assertTrue(onJson.getBoolean("on"))
        assertEquals(2, onJson.length())
        val off = JSONObject(Protocol.dnd(false))
        assertEquals("dnd", off.getString("type"))
        assertFalse(off.getBoolean("on"))
        assertNull(Protocol.parseDndSet(frame))
        assertFalse(Protocol.isRing(frame))
        assertNull(Protocol.dismissId(frame))
    }

    @Test
    fun parseDndSetAcceptsOnlyABoolean() {
        assertEquals(true, Protocol.parseDndSet("""{"type":"dnd_set","on":true}"""))
        assertEquals(false, Protocol.parseDndSet("""{"type":"dnd_set","on":false}"""))
        assertEquals(true, Protocol.parseDndSet("""{"type":"dnd_set","on":true,"extra":1}"""))
        assertEquals(false, Protocol.parseDndSet(""" { "type" : "dnd_set" , "on" : false } """))
        assertNull(Protocol.parseDndSet("""{"type":"dnd_set"}"""))
        assertNull(Protocol.parseDndSet("""{"type":"dnd_set","on":null}"""))
        assertNull(Protocol.parseDndSet("""{"type":"dnd_set","on":"true"}"""))
        assertNull(Protocol.parseDndSet("""{"type":"dnd_set","on":1}"""))
        assertNull(Protocol.parseDndSet("""{"type":"dnd_set","on":0}"""))
        assertNull(Protocol.parseDndSet("""{"type":"dnd","on":true}"""))
        assertNull(Protocol.parseDndSet("""{"type":"ping"}"""))
        assertNull(Protocol.parseDndSet("""{"type":"call_action","action":"silence"}"""))
        assertNull(Protocol.parseDndSet("not json"))
        assertNull(Protocol.parseDndSet(""))
        assertNull(Protocol.parseDndSet("{"))
    }

    @Test
    fun parsedSetMapsOntoTheFilterToApply() {
        val turnOn = Protocol.parseDndSet("""{"type":"dnd_set","on":true}""")
        val turnOff = Protocol.parseDndSet("""{"type":"dnd_set","on":false}""")
        assertEquals(true, turnOn)
        assertEquals(false, turnOff)
        assertEquals(Dnd.FILTER_PRIORITY, Dnd.filterFor(turnOn!!))
        assertEquals(Dnd.FILTER_ALL, Dnd.filterFor(turnOff!!))
    }

    @Test
    fun reportedOnMatchesTheFilterMapping() {
        assertTrue(JSONObject(Protocol.dnd(Dnd.isOn(Dnd.FILTER_PRIORITY))).getBoolean("on"))
        assertTrue(JSONObject(Protocol.dnd(Dnd.isOn(Dnd.FILTER_NONE))).getBoolean("on"))
        assertTrue(JSONObject(Protocol.dnd(Dnd.isOn(Dnd.FILTER_ALARMS))).getBoolean("on"))
        assertFalse(JSONObject(Protocol.dnd(Dnd.isOn(Dnd.FILTER_ALL))).getBoolean("on"))
        assertFalse(JSONObject(Protocol.dnd(Dnd.isOn(Dnd.FILTER_UNKNOWN))).getBoolean("on"))
    }
}
