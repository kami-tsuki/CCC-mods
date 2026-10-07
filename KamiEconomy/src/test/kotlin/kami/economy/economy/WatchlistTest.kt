package kami.economy.economy

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchlistTest {
    @BeforeTest
    fun setUp() {
        TestDb.open()
        Watchlist.load()
    }

    @AfterTest
    fun tearDown() = TestDb.close()

    @Test
    fun addRemoveCapAndPersist() {
        assertTrue(Watchlist.add("a", "minecraft:stone"))
        assertFalse(Watchlist.add("a", "minecraft:stone"))
        assertTrue(Watchlist.add("b", "minecraft:stone"))
        (1 until Watchlist.CAP).forEach { assertTrue(Watchlist.add("a", "test:item$it")) }
        assertFalse(Watchlist.add("a", "test:over"))
        assertTrue(Watchlist.remove("a", "test:item1"))
        assertFalse(Watchlist.remove("a", "test:item1"))
        assertEquals(Watchlist.CAP - 1, Watchlist.of("a").size)
        TestDb.restart()
        Watchlist.load()
        assertEquals(Watchlist.CAP - 1, Watchlist.of("a").size)
        assertEquals(listOf("minecraft:stone"), Watchlist.of("b").toList())
        val summary = Watchlist.summary(1)
        assertEquals(Watchlist.CAP, summary.total)
        assertEquals(2, summary.players)
        assertEquals(listOf("minecraft:stone" to 2), summary.top)
    }

    @Test
    fun importOnlyIntoEmptyList() {
        assertEquals(2, Watchlist.import("a", listOf("x:1", "x:2")))
        assertEquals(0, Watchlist.import("a", listOf("x:3")))
        assertEquals(listOf("x:1", "x:2"), Watchlist.of("a").toList())
    }
}
