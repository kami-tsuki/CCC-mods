package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Settings
import kami.economy.StarterGood
import kami.economy.Vendor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VendorsTest {
    private val item = "test:oak_log"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun setup() {
        Config.s = Settings(starterGoods = listOf(StarterGood("#test:logs", 20, 64, 64, 32)))
        Market.data = Data()
        Stocks.index { if (it == "test:logs") listOf(item) else emptyList() }
    }

    private fun vendor(x: Int, price: Int, count: Int) = Vendor("minecraft:overworld", x, 64, 0, "aurora", "", item, price, count)

    @Test
    fun registrySurvivesRestart() {
        setup()
        Market.data.vendors += vendor(1, 30, 64)
        val loaded = json.decodeFromString<Data>(json.encodeToString(Market.data))
        val v = loaded.vendors.single()
        assertTrue(v.at("minecraft:overworld", 1, 64, 0))
        assertEquals(30, v.price)
        assertEquals("aurora", v.country)
    }

    @Test
    fun oldSaveLoadsWithoutVendors() {
        assertTrue(json.decodeFromString<Data>("""{"books":{},"nextOrderId":3}""").vendors.isEmpty())
    }

    @Test
    fun medianIsPerLot() {
        setup()
        assertNull(Stocks.vendorPrice(item))
        Market.data.vendors += listOf(vendor(1, 10, 32), vendor(2, 30, 64), vendor(3, 100, 64))
        assertEquals(30, Stocks.vendorPrice(item))
        Market.data.vendors += vendor(4, 40, 64)
        assertEquals(35, Stocks.vendorPrice(item))
    }

    @Test
    fun vendorPriceFeedsRecovery() {
        setup()
        Stocks.stock(item)
        Market.data.vendors += vendor(1, 40, 64)
        Market.data.day = 1
        Stocks.rollover(System.currentTimeMillis())
        assertEquals(22.0, Market.data.stocks.getValue(item).base, 1e-9)
    }
}
