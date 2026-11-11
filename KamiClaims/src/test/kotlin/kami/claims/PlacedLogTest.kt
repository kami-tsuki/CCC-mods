package kami.claims

import kami.claims.world.PlacedLog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlacedLogTest {
    @BeforeTest
    @AfterTest
    fun clean() = PlacedLog.reset()

    @Test
    fun readsAndMissesKeepTheRevision() {
        PlacedLog.mark("overworld", 1L, "a")
        val rev = PlacedLog.rev
        PlacedLog.contains("overworld", 1L)
        PlacedLog.takeBy("overworld", 1L, "b")
        PlacedLog.remove("overworld", 2L)
        PlacedLog.rows()
        assertEquals(rev, PlacedLog.rev)
    }

    @Test
    fun everyChangeBumpsTheRevision() {
        var rev = PlacedLog.rev
        fun bumped(change: () -> Unit) {
            change()
            assertNotEquals(rev, PlacedLog.rev)
            rev = PlacedLog.rev
        }
        bumped { PlacedLog.mark("overworld", 1L) }
        bumped { PlacedLog.mark("overworld", 1L, "a") }
        bumped { PlacedLog.takeBy("overworld", 1L, "a") }
        bumped { PlacedLog.restore("overworld", 2L, "a", 5L) }
        bumped { PlacedLog.remove("overworld", 2L) }
        bumped { PlacedLog.reset() }
    }
}
