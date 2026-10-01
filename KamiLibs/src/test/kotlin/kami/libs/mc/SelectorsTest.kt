package kami.libs.mc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectorsTest {
    @Test
    fun globMatchesWildcards() {
        assertTrue(Selectors.glob("create:*", "create:crushing/iron"))
        assertTrue(Selectors.glob("create:crushing/*_ore", "create:crushing/iron_ore"))
        assertTrue(Selectors.glob("*:iron_*", "minecraft:iron_axe"))
        assertTrue(Selectors.glob("a?c", "abc"))
        assertTrue(Selectors.glob("*", ""))
        assertTrue(Selectors.glob("minecraft:stick", "minecraft:stick"))
    }

    @Test
    fun globRejectsMismatches() {
        assertFalse(Selectors.glob("create:*", "tfmg:ingot"))
        assertFalse(Selectors.glob("a?c", "ac"))
        assertFalse(Selectors.glob("minecraft:stick", "minecraft:sticks"))
        assertFalse(Selectors.glob("*_ore", "iron_ore_block"))
    }

    @Test
    fun namespaceOfSelectors() {
        assertEquals("create", Selectors.namespace("#create:planks"))
        assertEquals("minecraft", Selectors.namespace("stick"))
    }
}
