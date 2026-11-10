package kami.economy.villager

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Settings
import kami.economy.StarterGood
import kami.economy.economy.EnchantBooks
import kami.economy.economy.Stocks
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VillagerCoursesTest {
    private val wheat = "minecraft:wheat"
    private val rotten = "test:rotten"
    private val cake = "test:cake"

    @BeforeTest
    fun setup() {
        Config.s = Settings(starterGoods = listOf(StarterGood(Specs.EMERALD, 384, 16), StarterGood(wheat, 64, 64)))
        Market.data = Data()
        Stocks.index { emptyList() }
        VillagerCourses.all().keys.toList().forEach(VillagerCourses::reset)
    }

    private fun plain(id: String) = Spec(id, id, emptyList(), true)

    @Test
    fun marketItemsUseTheCourse() {
        assertEquals(1.0, VillagerCourses.unit(plain(wheat), Side.BUY, 0.05))
        assertEquals(24.0, VillagerCourses.emeraldUnit())
    }

    @Test
    fun unknownItemsSeedHighWhenSoldAndLowWhenBought() {
        assertEquals(1.0 / 4 * 24 * 3, VillagerCourses.unit(plain(cake), Side.SELL, 0.25))
        assertEquals(1.0 / 32 * 24 * 0.5, VillagerCourses.unit(plain(rotten), Side.BUY, 1.0 / 32))
        assertNull(VillagerCourses.unit(plain("test:nothing"), Side.SELL, null))
    }

    @Test
    fun unknownGoodsAreListedWithAFittingLot() {
        VillagerCourses.unit(plain(cake), Side.SELL, 0.25)
        assertEquals(4, Config.s.lotOf(cake))
        assertEquals(72.0, Market.peek(cake)!!.course)
        VillagerCourses.fill(plain(cake), 15.0, 1, Side.SELL)
        assertEquals(CourseState.LIVE, VillagerCourses.all()[cake]?.state)
        assertTrue(VillagerCourses.unit(plain(cake), Side.SELL, 0.25)!! in 17.0..18.0)
        VillagerCourses.unit(plain(rotten), Side.BUY, 1.0 / 32)
        assertEquals(64, Config.s.lotOf(rotten))
        VillagerCourses.fill(plain(rotten), 0.3, 32, Side.BUY)
        assertTrue(VillagerCourses.unit(plain(rotten), Side.BUY, 1.0 / 32)!! < 1.0)
    }

    @Test
    fun emeraldFallsBackToTheConfiguredPrice() {
        Config.s = Settings()
        Stocks.index { emptyList() }
        assertEquals(24.0, VillagerCourses.emeraldUnit())
    }

    @Test
    fun marketGoodsMoveTheMarketCourse() {
        VillagerCourses.fill(plain(wheat), 2.0, 64, Side.SELL)
        assertTrue(Market.peek(wheat)!!.course > 64.0)
        assertEquals(64L, Market.data.traded[wheat]?.bought)
    }

    @Test
    fun enchantsArePricedAsDoubledLevelOneBooks() {
        val sword = Spec("test:sword#sharpness3", "test:sword", listOf(Enchant("minecraft:sharpness", 3, false)), false)
        assertEquals(12.0 * 24 * 3 * 4, VillagerCourses.unit(sword, Side.SELL, null))
        VillagerCourses.set(EnchantBooks.spec("minecraft:sharpness", 1), 100.0, true)
        VillagerCourses.fill(sword, 10_000.0, 1, Side.SELL)
        assertEquals(400.0, VillagerCourses.unit(sword, Side.SELL, null))
    }
}
