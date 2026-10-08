package kami.economy.economy

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VariantsTest {
    @AfterTest
    fun reset() {
        Variants.map = emptyMap()
    }

    @Test
    fun mathIsExact() {
        Variants.map = mapOf("block" to Variant("ingot", 9, 1), "nugget" to Variant("ingot", 1, 9))
        assertEquals(16, Variants.lot("block", 16))
        assertEquals(144, Variants.lot("nugget", 16))
        assertEquals(16, Variants.lot("ingot", 16))
        assertEquals(18, Variants.coreQty("block", 2))
        assertEquals(2, Variants.coreQty("nugget", 18))
        assertNull(Variants.coreQty("nugget", 10))
        assertEquals(7, Variants.coreQty("ingot", 7))
        assertEquals(100, Variants.scale("nugget", 100, 16))
        assertEquals(900, Variants.scale("block", 100, 16) * 1)
        assertEquals(listOf("nugget", "block"), Variants.family("ingot").map { it.first })
        assertEquals("ingot", Variants.core("block"))
    }

    @Test
    fun coreChoice() {
        val none = { _: String -> false }
        assertEquals(mapOf("b" to Variant("a", 9, 1)), Variants.derive(listOf("a" to "b"), none))
        assertEquals(mapOf("nugget" to Variant("ingot", 1, 9)), Variants.derive(listOf("nugget" to "ingot"), none))
        val chain = listOf("n" to "i", "i" to "b")
        assertEquals(mapOf("n" to Variant("i", 1, 9), "b" to Variant("i", 9, 1)), Variants.derive(chain, none))
        assertEquals(mapOf("n" to Variant("i", 1, 9), "b" to Variant("i", 9, 1)), Variants.derive(chain) { true })
        assertEquals(mapOf("b" to Variant("a", 9, 1)), Variants.derive(listOf("a" to "b")) { it == "a" })
        assertEquals(mapOf("a" to Variant("b", 1, 9)), Variants.derive(listOf("a" to "b")) { it == "b" })
        val long = listOf("a" to "b", "b" to "c", "c" to "d")
        assertEquals(mapOf("a" to Variant("b", 1, 9), "c" to Variant("b", 9, 1), "d" to Variant("b", 81, 1)), Variants.derive(long, none))
        assertEquals(emptyMap(), Variants.derive(listOf("a" to "b", "a" to "c"), none))
    }
}
