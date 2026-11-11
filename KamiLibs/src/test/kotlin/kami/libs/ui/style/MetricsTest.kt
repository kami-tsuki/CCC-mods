package kami.libs.ui.style

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {
    @Test
    fun comfortableKeepsBaseValues() {
        Metrics.use(Density.COMFORTABLE)
        assertEquals(listOf(18, 16, 16, 16, 13, 24, 16, 14, 14, 18), listOf(Metrics.controlH, Metrics.smallH, Metrics.rowH, Metrics.cardHeaderH, Metrics.sectionRowH, Metrics.topbarH, Metrics.crumbsH, Metrics.groupH, Metrics.railToggleH, Metrics.modulesH))
    }

    @Test
    fun layoutMetricsScaleWithDensity() {
        Metrics.use(Density.COMFORTABLE)
        assertEquals(listOf(6, 34, 44, 124, 132, 96, 18), listOf(Metrics.gap, Metrics.kpiH, Metrics.tileH, Metrics.cardH, Metrics.chartH, Metrics.chartCompactH, Metrics.tableRowH))
        Metrics.use(Density.COMPACT)
        assertTrue(Metrics.cardH < 124 && Metrics.gap >= 4)
        Metrics.use(Density.COMFORTABLE)
    }

    @Test
    fun controlHeightsStayNearBase() {
        Density.entries.forEach {
            assertTrue(abs(Metrics.px(18, it) - 18) <= 2)
            assertTrue(abs(Metrics.px(16, it) - 16) <= 2)
        }
        assertEquals(16, Metrics.px(18, Density.COMPACT))
        assertEquals(14, Metrics.px(16, Density.COMPACT))
    }

    @Test
    fun parseIsTolerant() {
        assertEquals(Density.COMPACT, Density.parse(" Compact "))
        assertEquals(Density.COMFORTABLE, Density.parse("nonsense"))
    }
}
