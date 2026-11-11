package kami.claims.client

import kami.claims.client.app.Finance
import kami.claims.client.map.ChunkCells
import kami.claims.client.app.runway
import kami.claims.client.app.pages.LoanBlock
import kami.claims.client.app.pages.LoansModel
import kami.claims.client.app.pages.MapModel
import kami.claims.client.app.pages.Plan
import kami.claims.client.compat.xaero.chunkKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewModelTest {
    @Test
    fun financeNetIncludesTribute() {
        val f = Finance(tax = 100, upkeep = 40, wages = 20, tributeIn = 15, tributeOut = 5)
        assertEquals(115, f.income)
        assertEquals(65, f.spending)
        assertEquals(50, f.net)
        assertNull(runway(1000, f.net))
        assertEquals(10, runway(500, Finance(10, 40, 20, 0, 0).net))
    }

    @Test
    fun jumpParsesChunksAndBlocks() {
        assertEquals(3 to -4, MapModel.jump("3, -4"))
        assertEquals(62 to -7, MapModel.jump("1000 -100 b"))
        assertEquals(2000 to 1000, MapModel.jump("2000;1000"))
        assertEquals(250 to 0, MapModel.jump("4000 0"))
        assertNull(MapModel.jump("12"))
    }

    @Test
    fun areaCoversRectangleInAnyDirection() {
        val cells = MapModel.area(2, 5, 0, 4, ::chunkKey)
        assertEquals(6, cells.size)
        assertEquals(6, MapModel.size(2, 5, 0, 4))
        assertContentEquals(intArrayOf(0, 4, 2, 5), MapModel.bounds(cells))
        assertEquals(-3 to 7, ChunkCells.cell(chunkKey(-3, 7)))
    }

    @Test
    fun planFollowsOwnership() {
        assertEquals(Plan.CLAIM, MapModel.choose(Plan.RETYPE, listOf(true, null)))
        assertEquals(Plan.RETYPE, MapModel.choose(Plan.CLAIM, listOf(true, false)))
        assertEquals(Plan.UNCLAIM, MapModel.choose(Plan.UNCLAIM, listOf(true)))
        assertEquals(Plan.CLAIM, MapModel.choose(Plan.CLAIM, listOf(false)))
    }

    @Test
    fun planArgsMatchPlan() {
        val cells = listOf(chunkKey(1, -2), chunkKey(3, 4))
        assertContentEquals(arrayOf("cells", "1:-2,3:4"), MapModel.args(Plan.UNCLAIM, "farming", cells))
        assertContentEquals(arrayOf("farming", "cells", "1:-2,3:4"), MapModel.args(Plan.CLAIM, "farming", cells))
    }

    @Test
    fun runwayAfterCost() {
        assertEquals(Long.MAX_VALUE, MapModel.runwayAfter(100, 10, 5))
        assertEquals(9, MapModel.runwayAfter(100, 10, -10))
    }

    @Test
    fun loanQuote() {
        val plain = LoansModel.quote(1000, 7, 10, false)
        assertEquals(1100, plain.total)
        assertEquals(158, plain.perDay)
        assertNull(plain.worstEffective)
        val worst = LoansModel.quote(1000, 7, 10, true)
        assertEquals(15, worst.worstEffective)
        assertEquals(1150, worst.worstTotal)
    }

    @Test
    fun loanBlockOrder() {
        assertEquals(LoanBlock.LEND_LOCKED, LoansModel.block(true, 99, 64, 0, 5, false, false, 0, 0))
        assertEquals(LoanBlock.OFFER_LIMIT, LoansModel.block(false, 64, 64, 1000, 5, false, false, 0, 0))
        assertEquals(LoanBlock.FUNDS, LoansModel.block(false, 1, 64, 4, 5, false, false, 0, 0))
        assertEquals(LoanBlock.RANGE, LoansModel.block(false, 1, 64, 10, 5, true, true, 9, 3))
        assertNull(LoansModel.block(false, 1, 64, 10, 5, true, false, 9, 3))
    }
}
