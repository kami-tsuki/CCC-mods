package kami.economy.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoCacheTest {
    @Test
    fun oneRequestPerKeyInFlightAndTtl() {
        val cache = InfoCache<Int>(30_000, 5_000)
        assertTrue(cache.request("a", 0))
        assertFalse(cache.request("a", 1_000))
        assertTrue(cache.request("a", 6_000))
        assertTrue(cache.request("b", 6_000))
        cache.put("b", 1, 6_500)
        assertFalse(cache.request("b", 20_000))
        assertTrue(cache.request("b", 37_000))
        assertEquals(1, cache.get("b"))
    }
}
