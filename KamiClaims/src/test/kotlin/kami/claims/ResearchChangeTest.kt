package kami.claims

import kami.claims.client.store.ResearchChange
import kami.claims.net.QueueView
import kami.claims.net.StateView
import kami.claims.research.NodeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchChangeTest {
    private fun state(
        country: String = "a", level: Int = 1, xp: Long = 0, done: List<String> = emptyList(), queue: List<QueueView> = emptyList()
    ) = StateView(country, level, xp, 0, 100, 0, true, done, queue, emptyList(), emptyList(), emptyList())

    private fun running(node: String) = QueueView(node, NodeState.RESEARCHING, 1000, true, emptyList())

    @Test
    fun loginAndCountrySwitchNeverReportChanges() {
        assertNull(ResearchChange.between(StateView.NONE, state(done = listOf("t:a"), level = 5)))
        assertNull(ResearchChange.between(state(country = "a"), state(country = "b", done = listOf("t:a"))))
        assertNull(ResearchChange.between(state(), StateView.NONE))
    }

    @Test
    fun identicalStatesReportNothing() {
        assertNull(ResearchChange.between(state(done = listOf("t:a")), state(done = listOf("t:a"))))
    }

    @Test
    fun completedNodesAreTheNewlyDoneOnes() {
        val change = assertNotNull(ResearchChange.between(state(done = listOf("t:a"), queue = listOf(running("t:b"))), state(done = listOf("t:a", "t:b"), xp = 40)))
        assertEquals(listOf("t:b"), change.completed)
        assertEquals(emptyList(), change.started)
        assertEquals(40, change.xpGained)
    }

    @Test
    fun startedNodesAreNewlyResearching() {
        val change = assertNotNull(ResearchChange.between(state(queue = listOf(running("t:a"))), state(queue = listOf(running("t:a"), running("t:b")))))
        assertEquals(listOf("t:b"), change.started)
    }

    @Test
    fun levelUpReportsBothLevels() {
        val change = assertNotNull(ResearchChange.between(state(level = 2, xp = 90), state(level = 3, xp = 120)))
        assertTrue(change.leveledUp)
        assertEquals(2, change.levelFrom)
        assertEquals(3, change.levelTo)
        assertEquals(30, change.xpGained)
    }
}
