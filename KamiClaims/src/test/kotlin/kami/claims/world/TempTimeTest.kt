package kami.claims.world

import kami.claims.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class TempTimeTest {
    private val fixed = Settings().tempBlockFixedSeconds

    private fun secs(id: String, tags: List<String> = emptyList(), hardness: Double = 1.0, map: Map<String, Double> = fixed) =
        TempTime.seconds(id, tags, map, hardness, 3.0, 2.0)

    @Test
    fun fixedDefaults() {
        assertEquals(300.0, secs("minecraft:torch"))
        assertEquals(300.0, secs("minecraft:wall_torch"))
        assertEquals(600.0, secs("minecraft:soul_torch"))
        assertEquals(600.0, secs("minecraft:soul_wall_torch"))
        assertEquals(60.0, secs("minecraft:stone_button"))
        assertEquals(60.0, secs("minecraft:lever"))
        assertEquals(30.0, secs("minecraft:oak_button", listOf("minecraft:wooden_buttons")))
    }

    @Test
    fun hardnessFormula() {
        assertEquals(4.5, secs("minecraft:stone", hardness = 1.5))
        assertEquals(2.0, secs("minecraft:dirt", hardness = 0.1))
        assertEquals(4.5, secs("mod:unknown", listOf("mod:tag"), 1.5))
    }

    @Test
    fun exactIdBeatsTag() {
        val map = mapOf("minecraft:oak_button" to 5.0, "#minecraft:wooden_buttons" to 30.0)
        assertEquals(5.0, secs("minecraft:oak_button", listOf("minecraft:wooden_buttons"), map = map))
    }

    @Test
    fun smallestTagWins() {
        val map = mapOf("#a:x" to 40.0, "#a:y" to 10.0, "#a:z" to 25.0)
        assertEquals(10.0, secs("mod:b", listOf("a:x", "a:y", "a:z"), map = map))
        assertEquals(10.0, secs("mod:b", listOf("a:z", "a:y", "a:x"), map = map))
    }
}
