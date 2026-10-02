package kami.libs.mc

import kotlin.test.Test
import kotlin.test.assertEquals

class ItemSpecsTest {
    @Test
    fun parts() {
        assertEquals("tacz:ammo", ItemSpecs.base("tacz:ammo#tacz:9mm"))
        assertEquals("tacz:9mm", ItemSpecs.pack("tacz:ammo#tacz:9mm"))
        assertEquals("minecraft:stone", ItemSpecs.base("minecraft:stone"))
        assertEquals("", ItemSpecs.pack("minecraft:stone"))
        assertEquals("#minecraft:logs", ItemSpecs.base("#minecraft:logs"))
        assertEquals("", ItemSpecs.pack("#minecraft:logs"))
    }

    @Test
    fun roundTrip() {
        for (s in listOf("tacz:modern_kinetic_gun#tacz:ak47", "minecraft:stone", "#minecraft:logs"))
            assertEquals(s, ItemSpecs.join(ItemSpecs.base(s), ItemSpecs.pack(s)))
    }
}
