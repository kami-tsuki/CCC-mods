package kami.economy.compat

import kami.economy.compat.create.DisplayFormat
import kami.economy.compat.create.Figures
import kami.economy.compat.create.Metric
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayFormatTest {
    private val f = Figures(64, 134, 120, 0, -4, 1234, -1, 0)

    private fun show(m: Metric, trend: Int? = null, lot: Boolean = false, compact: Boolean = true) = DisplayFormat.render(m, f, trend, lot, compact)

    @Test
    fun money() {
        assertEquals("2.1 ◎", show(Metric.BUY))
        assertEquals("134 ◎/lot", show(Metric.BUY, lot = true))
        assertEquals("—", DisplayFormat.render(Metric.SELL, Figures(1, 0, 0, 0, 0, 0, 0, 0), null, false, true))
    }

    @Test
    fun changes() {
        assertEquals("▼0.4%", show(Metric.DAY))
        assertEquals("▲3.1%", show(Metric.WEEK, 31))
        assertEquals("0%", show(Metric.MONTH, 0))
        assertEquals("—", show(Metric.ALL))
    }

    @Test
    fun counts() {
        assertEquals("1.2k", show(Metric.SUPPLY))
        assertEquals("1,234", show(Metric.SUPPLY, compact = false))
        assertEquals("∞", show(Metric.DEMAND))
        assertEquals("10.4%", show(Metric.SPREAD))
    }
}
