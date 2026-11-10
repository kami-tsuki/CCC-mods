package kami.economy.villager

import kami.economy.economy.EnchantBooks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class OffersTest {
    private fun map(a: Leg<String>, b: Leg<String>?, r: Leg<String>) = Offers.map(a, b, r) { Specs.emeralds(it.stack, it.count) }

    @Test
    fun emeraldSidesBecomeOrders() {
        val sell = assertIs<Mapped.Order<String>>(map(Leg(Specs.EMERALD, 3), null, Leg("minecraft:bread", 6)))
        assertEquals(Side.SELL, sell.side)
        assertEquals("minecraft:bread", sell.goods.stack)
        assertEquals(3, sell.emeralds)
        assertNull(sell.extra)

        val buy = assertIs<Mapped.Order<String>>(map(Leg("minecraft:wheat", 20), null, Leg(Specs.EMERALD, 1)))
        assertEquals(Side.BUY, buy.side)
        assertEquals(20, buy.goods.count)
    }

    @Test
    fun mixedTradesKeepTheItemCost() {
        val book = assertIs<Mapped.Order<String>>(map(Leg(Specs.EMERALD, 12), Leg("minecraft:book", 1), Leg("minecraft:enchanted_book", 1)))
        assertEquals("minecraft:book", book.extra?.stack)
        val fish = assertIs<Mapped.Order<String>>(map(Leg("minecraft:cod", 6), Leg(Specs.EMERALD, 1), Leg("minecraft:cooked_cod", 6)))
        assertEquals("minecraft:cod", fish.extra?.stack)
        assertEquals(1, fish.emeralds)
        assertEquals(18, assertIs<Mapped.Order<String>>(map(Leg(Specs.EMERALD_BLOCK, 2), null, Leg("minecraft:diamond", 1))).emeralds)
    }

    @Test
    fun everythingElseStaysABarter() {
        assertIs<Mapped.Barter>(map(Leg("minecraft:gold_ingot", 3), null, Leg("minecraft:apple", 1)))
        assertIs<Mapped.Barter>(map(Leg(Specs.EMERALD, 1), null, Leg(Specs.EMERALD_BLOCK, 1)))
        assertIs<Mapped.Barter>(map(Leg("minecraft:wheat", 20), Leg("minecraft:sugar", 1), Leg(Specs.EMERALD, 1)))
    }

    @Test
    fun specKeysAreSorted() {
        assertEquals("minecraft:diamond_sword#sharpness3+unbreaking2", Specs.key("minecraft:diamond_sword", listOf("unbreaking2", "sharpness3")))
        assertEquals("minecraft:enchanted_book#minecraft:mending@1", EnchantBooks.spec("minecraft:mending", 1))
        assertEquals(4.0, EnchantBooks.factor(3))
        assertEquals("apotheosis_life_mending", Specs.short("apotheosis:life_mending"))
    }
}
