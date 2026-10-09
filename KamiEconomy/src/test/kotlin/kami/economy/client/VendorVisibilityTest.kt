package kami.economy.client

import kami.economy.Vendor
import kami.economy.net.vendorVisible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VendorVisibilityTest {
    private fun vendor(country: String) = Vendor("minecraft:overworld", 0, 64, 0, country = country, item = "minecraft:iron_ingot", price = 5)

    private val hostile = { a: String, b: String -> a == "red" && b == "blue" }

    @Test
    fun hostileCountryVendorsAreHidden() {
        val all = listOf(vendor("blue"), vendor("red"), vendor("green"))
        assertEquals(listOf("red", "green"), all.filter { vendorVisible(it, "red", hostile) }.map { it.country })
    }

    @Test
    fun countrylessViewerAndServerVendorsSeeEverything() {
        assertTrue(vendorVisible(vendor("blue"), null, hostile))
        assertTrue(vendorVisible(vendor(""), "red", hostile))
    }
}
