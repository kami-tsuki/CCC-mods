package kami.libs.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowSizeTest {
    @Test
    fun standardCapsAt900x560() {
        assertEquals(900 to 560, WindowSize.of(2000, 1200, false))
        assertEquals(900 to 552, WindowSize.of(1066, 600, false))
    }

    @Test
    fun largeCapsAt1180x720() {
        assertEquals(1180 to 720, WindowSize.of(2000, 1200, true))
        assertEquals(1100 * 92 / 100 to 680 * 92 / 100, WindowSize.of(1100, 680, true))
    }

    @Test
    fun largeIgnoredOnSmallLayout() {
        assertEquals(WindowSize.of(1066, 600, false), WindowSize.of(1066, 600, true))
        assertEquals(WindowSize.of(1200, 600, false), WindowSize.of(1200, 600, true))
        assertEquals(WindowSize.of(1000, 900, false), WindowSize.of(1000, 900, true))
    }

    @Test
    fun smallScreensKeepMinimumsAndMargin() {
        assertEquals(392 to 252, WindowSize.of(400, 260, false))
        assertEquals(392 to 276, WindowSize.of(400, 300, false))
        assertEquals(292 to 192, WindowSize.of(300, 200, true))
    }

    @Test
    fun monotonicInScreenSize() {
        for (large in listOf(false, true)) {
            var prev = 0 to 0
            for (s in 300..2400 step 50) {
                val cur = WindowSize.of(s, s * 9 / 16, large)
                assertTrue(cur.first >= prev.first && cur.second >= prev.second)
                prev = cur
            }
        }
    }
}
