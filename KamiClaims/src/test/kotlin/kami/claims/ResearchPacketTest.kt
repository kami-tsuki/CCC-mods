package kami.claims

import kami.claims.net.*
import kami.claims.research.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResearchPacketTest {
    @AfterTest
    fun reset() {
        Research.defs = ResearchDefs.EMPTY
    }

    private fun defsRoundTrip(defs: DefsView, deflateAbove: Int = 524_288) = ResearchWire.decodeDefs(ResearchWire.encodeDefs(defs, deflateAbove))

    private fun assertSame(a: DefsView, b: DefsView) {
        assertEquals(a.trees.map { it.id }, b.trees.map { it.id })
        assertEquals(a.levels.map { Triple(it.level, it.xp, it.rewards.map { r -> listOf(r.kind, r.id, r.count, r.amplifier, r.cooldownSeconds, r.interestPct, r.amount) }) }, b.levels.map { Triple(it.level, it.xp, it.rewards.map { r -> listOf(r.kind, r.id, r.count, r.amplifier, r.cooldownSeconds, r.interestPct, r.amount) }) })
        a.trees.zip(b.trees).forEach { (x, y) ->
            assertEquals(x.scope, y.scope)
            assertEquals(x.categories.map { it.id to it.order }, y.categories.map { it.id to it.order })
            assertEquals(x.nodes.size, y.nodes.size)
            x.nodes.zip(y.nodes).forEach { (m, n) ->
                assertEquals(m.key, n.key)
                assertEquals(listOf(m.level, m.requires, m.anyRequires, m.hiddenRequires, m.conditions, m.x, m.y), listOf(n.level, n.requires, n.anyRequires, n.hiddenRequires, n.conditions, n.x, n.y))
                assertEquals(listOf(m.cost, m.timeMs, m.xp), listOf(n.cost, n.timeMs, n.xp))
                assertEquals(m.tasks.map { Triple(it.kind, it.subject, it.target) }, n.tasks.map { Triple(it.kind, it.subject, it.target) })
                assertEquals(m.unlocks.map { Triple(it.kind, it.id, it.count) }, n.unlocks.map { Triple(it.kind, it.id, it.count) })
            }
        }
    }

    @Test
    fun defsRoundTripPlainAndDeflated() {
        Research.defs = Validator.build(ResearchSettings(), LevelsConfig(), Defaults.groups, Defaults.trees).defs
        val defs = ResearchSync.defs().let { DefsView(it.trees, mapOf("recipes" to byteArrayOf(1, 2, 3)), it.levels) }
        assertTrue(defs.trees.sumOf { it.nodes.size } > 40)
        assertTrue(defs.trees.flatMap { it.nodes }.any { it.requires.isNotEmpty() && it.conditions.isNotEmpty() })
        val foundations = defs.trees.first { it.id == "metallurgy" }.nodes
        assertTrue(foundations.first { it.id == "iron_crushing" }.anyRequires.isEmpty())
        assertEquals(1, foundations.first { it.id == "iron_deco" }.hiddenRequires.size)
        val washing = foundations.first { it.id == "iron_washing" }
        assertEquals(1, washing.requires.size)
        assertEquals(2, washing.conditions.size)
        assertEquals(LevelsConfig().top, defs.levels.size)
        assertEquals(listOf(0, 1, 3), defs.levels.take(3).map { it.requires.size })
        val plain = defsRoundTrip(defs)
        val packed = defsRoundTrip(defs, deflateAbove = 0)
        assertSame(defs, plain)
        assertSame(defs, packed)
        assertEquals(listOf(1, 2, 3), plain.extras.getValue("recipes").map { it.toInt() })
        assertEquals(listOf(1, 2, 3), packed.extras.getValue("recipes").map { it.toInt() })
    }

    @Test
    fun defsRejectBrokenInput() {
        val node = NodeView("t", "a", "c", "", "", "", 0, 0, 0, -1, emptyList(), listOf(5), emptyList(), emptyList(), null, null)
        val lone = NodeView("t", "a", "c", "", "", "", 0, 0, 0, -1, emptyList(), emptyList(), emptyList(), emptyList(), null, null, listOf(3))
        val bad = DefsView(listOf(TreeView("t", "", Scope.ANY, emptyList(), listOf(node))))
        assertFailsWith<IllegalArgumentException> { defsRoundTrip(bad) }
        assertFailsWith<IllegalArgumentException> { defsRoundTrip(DefsView(listOf(TreeView("t", "", Scope.ANY, emptyList(), listOf(lone))))) }
        assertFailsWith<IllegalArgumentException> { ResearchWire.decodeDefs(byteArrayOf(0, 5, 1)) }
        assertFailsWith<IllegalArgumentException> { ResearchWire.decodeState(byteArrayOf(-1, -1, -1, -1, -1, -1)) }
        val packed = ResearchWire.encodeDefs(DefsView(emptyList(), mapOf("x" to ByteArray(64))), 0)
        packed[packed.size - 3] = (packed[packed.size - 3] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { ResearchWire.decodeDefs(packed) }
    }

    @Test
    fun stateRoundTrip() {
        val state = StateView(
            "alpha", 3, 450, 400, 900, 1234, true, listOf("country:a", "country:b"),
            listOf(QueueView("country:c", NodeState.RESEARCHING, 90_000, true, listOf(3, 0)), QueueView("country:d", NodeState.QUEUED, 0, false, emptyList())),
            listOf("country:e"), listOf(5, 1, 2, 1, 0), listOf(20, 0, 10, 1, 1),
            listOf("gear", "country"), mapOf("country:e" to listOf(true, false, true), "gear:x" to listOf(false)),
            listOf(emptyList(), listOf(true), listOf(true, false, false)),
            buffs = BuffsView(true, 3, 2, 3, 12, listOf(BuffView("buffs:speed_2", "minecraft:speed", 1, "aura", 0, 2, 2, true), BuffView("buffs:instant_health_1", "minecraft:instant_health", 0, "pulse", 300, 1, 1, false))),
            loans = LoansView(
                true, 2, listOf(ActiveLoanView("small", 1000, 1309, 286, 186, 6, 95)),
                listOf(LoanOfferView("small", 1000, 30, 7, 1300, 186, true, 10, 0), LoanOfferView("huge", 1_000_000, 50, 30, 1_500_000, 50_000, false, 125, 4)), true
            )
        )
        val back = ResearchWire.decodeState(ResearchWire.encodeState(state))
        assertEquals(listOf("alpha", 3, 450L, 400L, 900L, 1234L, true), listOf(back.country, back.level, back.xp, back.xpFloor, back.xpCeiling, back.treasury, back.canManage))
        assertEquals(state.done, back.done)
        assertEquals(state.available, back.available)
        assertEquals(state.queue.map { listOf(it.node, it.state, it.remainingMs, it.paid, it.tasks) }, back.queue.map { listOf(it.node, it.state, it.remainingMs, it.paid, it.tasks) })
        assertEquals(5, back.used(Capacity.CHUNKS))
        assertEquals(10, back.max(Capacity.CITIZENS))
        assertEquals(listOf("gear", "country"), back.trees)
        assertEquals(state.met, back.met)
        assertEquals(state.levelMet, back.levelMet)
        assertEquals(listOf(true, 3, 2, 3, 12), listOf(back.buffs.unlocked, back.buffs.points, back.buffs.used, back.buffs.surchargePerBorderChunk, back.buffs.borderChunks))
        assertEquals(state.buffs.list.map { listOf(it.key, it.effect, it.amplifier, it.mode, it.cooldownSeconds, it.cost, it.tax, it.enabled) }, back.buffs.list.map { listOf(it.key, it.effect, it.amplifier, it.mode, it.cooldownSeconds, it.cost, it.tax, it.enabled) })
        assertEquals(listOf(true, 2, true), listOf(back.loans.unlocked, back.loans.slots, back.loans.inDefault))
        assertEquals(state.loans.active.map { listOf(it.id, it.principal, it.total, it.paid, it.perDay, it.daysLeft, it.overdue) }, back.loans.active.map { listOf(it.id, it.principal, it.total, it.paid, it.perDay, it.daysLeft, it.overdue) })
        assertEquals(state.loans.offers.map { listOf(it.id, it.amount, it.interestPct, it.termDays, it.total, it.perDay, it.unlocked, it.level, it.cooldownDays) }, back.loans.offers.map { listOf(it.id, it.amount, it.interestPct, it.termDays, it.total, it.perDay, it.unlocked, it.level, it.cooldownDays) })
    }
}
