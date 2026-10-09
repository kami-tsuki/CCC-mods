package kami.libs.xaero

import kami.libs.xaero.impl.MapPrefs
import kami.libs.xaero.impl.Registry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapTogglesTest {
    private fun fresh() {
        MapPrefs.path = Files.createTempDirectory("kamimaps").resolve("maps.json")
        MapPrefs.reset()
    }

    @Test
    fun defaultsApplyUntilSet() {
        fresh()
        Registry.putToggle(MapToggle("t.on", "g", "l", "t", true))
        Registry.putToggle(MapToggle("t.off", "g", "l", "t", false))
        assertTrue(XaeroMaps.on("t.on"))
        assertFalse(XaeroMaps.on("t.off"))
        Registry.setToggle("t.on", false)
        assertFalse(XaeroMaps.on("t.on"))
    }

    @Test
    fun valuesPersistAcrossReload() {
        fresh()
        Registry.putToggle(MapToggle("p.t", "g", "l", "t", true))
        Registry.putChoice(MapChoice("p.c", "g", "l", listOf("a" to "A", "b" to "B"), "a"))
        Registry.setToggle("p.t", false)
        Registry.setChoice("p.c", "b")
        MapPrefs.reset()
        assertFalse(XaeroMaps.on("p.t"))
        assertEquals("b", XaeroMaps.choice("p.c"))
    }

    @Test
    fun invalidChoiceFallsBackToDefault() {
        fresh()
        Registry.putChoice(MapChoice("v.c", "g", "l", listOf("a" to "A"), "a"))
        MapPrefs.set("v.c", "zzz")
        assertEquals("a", XaeroMaps.choice("v.c"))
    }

    @Test
    fun unknownIdsAreOffAndEmpty() {
        fresh()
        assertFalse(XaeroMaps.on("nope"))
        assertEquals("", XaeroMaps.choice("nope"))
        assertTrue(XaeroMaps.on(""))
    }
}
