package kami.libs.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaveGuardTest {
    private val next = Route("next")
    private val gone = ArrayList<Route>()
    private var asked: Pair<(() -> Unit)?, () -> Unit>? = null
    private val guard = LeaveGuard({ save, _, discard -> asked = save to discard }, { gone += it })

    @Test
    fun cleanPageLeavesWithoutAsking() {
        assertTrue(guard.allow(next, false) { })
        assertNull(asked)
    }

    @Test
    fun dirtyPageAsksThenNavigatesOnDiscard() {
        var dropped = false
        assertFalse(guard.allow(next, true) { dropped = true })
        asked!!.second()
        assertTrue(dropped)
        assertEquals(listOf(next), gone)
        assertFalse(guard.allow(next, true) { })
    }

    @Test
    fun reentrantNavigationPassesOnce() {
        lateinit var reentrant: LeaveGuard
        var passed = false
        reentrant = LeaveGuard({ _, _, discard -> asked = null to discard }, { passed = reentrant.allow(it, true) { } })
        assertFalse(reentrant.allow(next, true) { })
        asked!!.second()
        assertTrue(passed)
        assertFalse(reentrant.allow(next, true) { })
    }

    @Test
    fun skippedNavigationDoesNotLeaveStaleClearance() {
        assertFalse(guard.allow(next, true) { })
        asked!!.second()
        assertFalse(guard.allow(next, true) { })
    }

    @Test
    fun dirtyCloseAsksThenClosesOnDiscardOnly() {
        assertFalse(guard.allow(Route.CLOSE, true) { })
        assertTrue(gone.isEmpty())
        asked!!.second()
        assertEquals(listOf(Route.CLOSE), gone)
    }

    @Test
    fun saveIsOfferedOnlyWhenGiven() {
        guard.allow(next, true, null) { }
        assertNull(asked!!.first)
        var saved = false
        guard.allow(next, true, { saved = true }) { }
        asked!!.first!!()
        assertTrue(saved)
    }
}
