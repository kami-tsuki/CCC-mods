package kami.economy.quest

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DishesTest {
    private fun one(vararg o: String) = In(o.toList())

    private val recipes = listOf(
        Rec("t:bread", 1, listOf(one("t:wheat"), one("t:wheat"), one("t:wheat"))),
        Rec("t:stew", 1, listOf(one("t:carrot"), one("t:potato"), one("t:mushroom")), listOf("t:pot")),
        Rec("t:sandwich", 1, listOf(one("t:bread"), one("t:cheese"))),
        Rec("t:cookie", 8, listOf(one("t:wheat"), one("t:cocoa"))),
        Rec("t:a", 1, listOf(one("t:b"))),
        Rec("t:b", 1, listOf(one("t:a", "t:wheat"))),
        Rec("t:soup", 1, listOf(one("t:wheat"), In(listOf("t:water_bucket"), 0.5, true)))
    )
    private val prices = mapOf("t:wheat" to 0.15, "t:carrot" to 0.12, "t:potato" to 0.12, "t:mushroom" to 0.2, "t:cheese" to 1.0, "t:cocoa" to 0.4, "t:water_bucket" to 1.3, DishGraph.BUCKET to 1.0)

    private fun solve(vararg t: String) = DishGraph.solve(recipes, t.toList(), { prices[it] }, { 0.3 }, { it.startsWith("t:") })

    @Test
    fun scores() {
        val d = solve("t:bread", "t:stew", "t:sandwich", "t:soup")
        assertEquals(2, d.getValue("t:bread").score)
        assertEquals(1, d.getValue("t:bread").tier)
        assertEquals(5, d.getValue("t:stew").score)
        assertEquals(2, d.getValue("t:stew").tier)
        assertEquals(5, d.getValue("t:sandwich").score)
        assertEquals(listOf("t:wheat"), d.getValue("t:soup").inputs)
        assertEquals(3, d.getValue("t:soup").score)
        assertTrue(d.getValue("t:bread").quest)
        assertEquals(0, d.getValue("t:wheat").score)
        assertTrue(!d.getValue("t:wheat").quest)
    }

    @Test
    fun prices() {
        val d = solve("t:bread", "t:cookie", "t:soup", "t:mushroom", "t:unknown")
        assertTrue(abs(d.getValue("t:bread").price - 0.45 * 1.14) < 1e-9)
        assertTrue(abs(d.getValue("t:cookie").price - 0.55 / 8 * 1.17) < 1e-9)
        assertTrue(abs(d.getValue("t:soup").price - (0.15 + 0.15) * 1.17) < 1e-9)
        assertTrue(!d.getValue("t:mushroom").guessed)
        assertTrue(d.getValue("t:unknown").guessed)
        assertEquals(0.3, d.getValue("t:unknown").price)
    }

    @Test
    fun cyclesTerminate() {
        val d = solve("t:a", "t:b")
        assertNotNull(d["t:a"])
        assertEquals(listOf("t:wheat"), d.getValue("t:b").inputs)
    }

    @Test
    fun cyclePricesDoNotDependOnOrder() {
        val pair = listOf(Rec("t:hay", 1, List(9) { one("t:grain") }), Rec("t:grain", 9, listOf(one("t:hay"))), Rec("t:loaf", 1, listOf(one("t:grain"), one("t:grain"))))
        fun run(vararg t: String) = DishGraph.solve(pair, t.toList(), { if (it == "t:grain") 0.12 else null }, { 0.3 }, { true })
        val a = run("t:grain", "t:hay", "t:loaf")
        val b = run("t:hay", "t:grain", "t:loaf")
        assertEquals(0.12, a.getValue("t:grain").price)
        assertEquals(a.getValue("t:grain").price, b.getValue("t:grain").price)
        assertEquals(a.getValue("t:hay").price, b.getValue("t:hay").price)
        assertEquals(a.getValue("t:loaf").price, b.getValue("t:loaf").price)
    }

    @Test
    fun lots() {
        assertEquals(1, DishGraph.lot(25.0))
        assertEquals(4, DishGraph.lot(5.0))
        assertEquals(16, DishGraph.lot(1.3))
        assertEquals(64, DishGraph.lot(0.1))
        assertEquals(0, DishGraph.tier(1))
        assertEquals(3, DishGraph.tier(8))
        assertEquals(5, DishGraph.tier(13))
    }
}
