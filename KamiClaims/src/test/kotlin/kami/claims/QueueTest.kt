package kami.claims

import kami.claims.research.*
import kami.claims.service.Fail
import kami.libs.config.Configs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueTest {
    private var time = 1_000_000L
    private var online = 1
    private val iron = """{"type":"deposit","item":"minecraft:iron_ingot","count":10}"""

    private fun tree(scope: String, vararg nodes: String) = Configs.json().decodeFromString(
        TreeFile.serializer(), """{"scope":"$scope","categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}"""
    )

    private fun node(id: String, extra: String = "") = """{"id":"$id","category":"c","time":"10m","cost":100$extra}"""

    private fun install(levels: LevelsConfig = LevelsConfig(xpFromLevel = 0, capacities = mapOf(Capacity.CHUNKS to 3, Capacity.PROVINCES to 1, Capacity.CITIZENS to 2, Capacity.RESEARCH_SLOTS to 1, Capacity.QUEUE_SLOTS to 2))) {
        val country = tree(
            "country",
            node("a"),
            node("b", ""","requires":[{"type":"node","id":"a"}],"tasks":[$iron]"""),
            node("late", ""","level":2"""),
            node("hold", ""","tasks":[{"type":"hold","condition":{"type":"treasury","min":50}}]"""),
            node("mine", ""","tasks":[{"type":"mine","block":"minecraft:stone","count":3}]"""),
            node("wide", ""","unlocks":[{"type":"capacity","key":"queueSlots","add":1}]""")
        )
        val province = tree("province", node("x"))
        val shared = tree("any", node("shared"))
        Research.defs = Validator.build(
            ResearchSettings(baseline = emptyList()), levels, mapOf("g" to Group()), mapOf("t" to country, "p" to province, "u" to shared)
        ).defs
    }

    private fun country(name: String, treasury: Long = 1000, parent: Country? = null): Country {
        val c = Country(name, treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "${name}_p", Rank.PRESIDENT)
        if (parent != null) { c.parent = parent.id; parent.provinces += c.id }
        return c
    }

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        install()
        Queue.clock = { time }
        Queue.onlineCount = { online }
        Queue.refreshCrafting = {}
        Levels.day = { 1 }
        Levels.announce = {}
    }

    @AfterTest
    fun restore() {
        Queue.clock = ::now
        Levels.day = ::today
        Research.defs = ResearchDefs.EMPTY
    }

    @Test
    fun enqueueChecksDependenciesLevelAndSlots() {
        val c = country("alpha")
        assertFailsWith<Fail> { Queue.enqueue(c, "t:b", null) }
        assertFailsWith<Fail> { Queue.enqueue(c, "t:late", null) }
        assertFailsWith<Fail> { Queue.enqueue(c, "t:nothing", null) }
        Queue.enqueue(c, "t:a", "alpha_p")
        assertFailsWith<Fail> { Queue.enqueue(c, "t:a", null) }
        Queue.enqueue(c, "t:mine", null)
        assertFailsWith<Fail> { Queue.enqueue(c, "t:hold", null) }
        assertEquals(2, c.research.queue.size)
    }

    @Test
    fun conditionsAreNotCheckedAgainAfterEnqueue() {
        val c = country("alpha")
        Queue.grant(c, "t:a")
        Queue.enqueue(c, "t:b", null)
        Queue.revoke(c, "t:a")
        assertEquals(10, Queue.credit(c, "t:b", 0, 10))
        assertEquals(NodeState.READY, c.research.queue.single().state)
    }

    @Test
    fun depositsArePartialCappedAndIrreversible() {
        val c = country("alpha")
        Queue.grant(c, "t:a")
        Queue.enqueue(c, "t:b", null)
        assertEquals(4, Queue.credit(c, "t:b", 0, 4))
        assertEquals(NodeState.QUEUED, c.research.queue.single().state)
        assertEquals(6, Queue.credit(c, "t:b", 0, 20))
        assertEquals(10L, c.research.queue.single().tasks[0])
        assertEquals(0, Queue.credit(c, "t:b", 0, 5))
        assertEquals(NodeState.READY, c.research.queue.single().state)
    }

    @Test
    fun startNeedsTasksSlotAndPaysOnce() {
        val c = country("alpha", treasury = 250)
        Queue.grant(c, "t:a")
        Queue.enqueue(c, "t:b", null)
        assertFailsWith<Fail> { Queue.start(c, "t:b", null) }
        Queue.credit(c, "t:b", 0, 10)
        Queue.enqueue(c, "t:mine", null)
        Queue.start(c, "t:b", "alpha_p")
        assertEquals(150L, c.treasury)
        assertEquals(LedgerKind.RESEARCH, c.ledger.last().kind)
        Queue.credit(c, "t:mine", 0, 3)
        assertFailsWith<Fail> { Queue.start(c, "t:mine", null) }
        Queue.pause(c, "t:b")
        Queue.start(c, "t:mine", null)
        Queue.pause(c, "t:mine")
        Queue.start(c, "t:b", null)
        assertEquals(50L, c.treasury)
    }

    @Test
    fun startFailsWithoutMoneyAndKeepsTheEntry() {
        val c = country("alpha", treasury = 10)
        Queue.enqueue(c, "t:a", null)
        assertFailsWith<Fail> { Queue.start(c, "t:a", null) }
        assertEquals(10L, c.treasury)
        assertEquals(NodeState.READY, c.research.queue.single().state)
        assertTrue(c.research.queue.single().paid.not())
    }

    @Test
    fun pauseTakesAQueueSlotAndReorderKeepsEveryEntry() {
        val c = country("alpha")
        Queue.enqueue(c, "t:a", null)
        Queue.start(c, "t:a", null)
        Queue.enqueue(c, "t:mine", null)
        Queue.enqueue(c, "t:wide", null)
        assertFailsWith<Fail> { Queue.pause(c, "t:a") }
        Queue.move(c, "t:wide", 0)
        assertEquals(listOf("t:wide", "t:a", "t:mine"), c.research.queue.map { it.node })
        Queue.move(c, "t:wide", 99)
        assertEquals("t:wide", c.research.queue.last().node)
        assertEquals(3, c.research.queue.size)
    }

    @Test
    fun timerRunsOnlyWhileAHomeCitizenIsOnline() {
        val c = country("alpha")
        Queue.enqueue(c, "t:a", null)
        Queue.start(c, "t:a", null)
        online = 0
        Queue.tick(c, 300_000)
        assertEquals(600_000L, c.research.queue.single().remainingMs)
        online = 1
        Queue.tick(c, 300_000)
        assertEquals(300_000L, c.research.queue.single().remainingMs)
        Queue.tick(c, 300_000)
        assertTrue(c.research.queue.isEmpty())
        assertNotNull(c.research.done["t:a"])
        assertEquals(100L, c.xp)
    }

    @Test
    fun citizenXpIsGrantedOncePerPlayer() {
        val c = country("alpha")
        Progress.citizenJoined(c, "u1")
        Progress.citizenJoined(c, "u1")
        assertEquals(25, c.xp)
    }

    @Test
    fun orphanedEntriesAreDroppedWhenTheirNodeVanishes() {
        val c = country("alpha")
        Queue.enqueue(c, "t:a", null)
        install()
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), LevelsConfig(), mapOf("g" to Group()), mapOf("p" to tree("province", node("x")))).defs
        Queue.dropOrphans()
        assertTrue(c.research.queue.isEmpty())
    }

    @Test
    fun aWallClockJumpAdvancesTheTimerByAtMostOneStep() {
        val c = country("alpha")
        Queue.enqueue(c, "t:a", null)
        Queue.start(c, "t:a", null)
        time = 1_000_000_000_000L
        Queue.tick()
        val before = c.research.queue.single().remainingMs
        time += 3_600_000
        Queue.tick()
        assertEquals(before - Queue.MAX_STEP_MS, c.research.queue.single().remainingMs)
    }

    @Test
    fun holdTasksAreEvaluatedOnTick() {
        val c = country("alpha", treasury = 10)
        Queue.enqueue(c, "t:hold", null)
        assertEquals(NodeState.QUEUED, c.research.queue.single().state)
        c.treasury = 80
        Queue.tick(c, 5_000)
        assertEquals(NodeState.READY, c.research.queue.single().state)
    }

    @Test
    fun provincesHaveTheirOwnTree() {
        val parent = country("parent")
        val child = country("child", parent = parent)
        assertFailsWith<Fail> { Queue.enqueue(child, "t:a", null) }
        assertFailsWith<Fail> { Queue.enqueue(parent, "p:x", null) }
        Queue.enqueue(child, "p:x", null)
        Queue.grant(parent, "t:a")
        assertTrue(child.research.done.isEmpty())
        Queue.finish(child, "p:x")
        assertTrue("p:x" !in parent.research.done)
        child.parent = null
        parent.provinces.clear()
        assertTrue("p:x" in child.research.done)
    }

    @Test
    fun playerLeavingKeepsTheCountryUnlocks() {
        val c = country("alpha")
        Realm.join(c, "second", Rank.CITIZEN)
        Queue.grant(c, "t:a")
        Realm.leave(c, "second", Leave.LEAVE)
        assertTrue("t:a" in c.research.done)
    }

    @Test
    fun adminGrantRevokeFinishReset() {
        val c = country("alpha")
        Queue.enqueue(c, "t:a", null)
        Queue.grant(c, "t:a")
        assertTrue(c.research.queue.isEmpty())
        Queue.revoke(c, "t:a")
        assertFailsWith<Fail> { Queue.revoke(c, "t:a") }
        Queue.finish(c, "t:wide")
        assertTrue("t:wide" in c.research.done)
        Queue.reset(c, "t:wide")
        assertTrue(c.research.done.isEmpty())
        Queue.grant(c, "t:a")
        Queue.reset(c, null)
        assertTrue(c.research.done.isEmpty())
    }

    @Test
    fun taskProgressOnlyCountsForQueuedNodesOfTheHomeCountry() {
        val c = country("alpha")
        Progress.report(c, "mine", "minecraft:stone", 2)
        Queue.enqueue(c, "t:mine", null)
        Progress.report(c, "mine", "minecraft:dirt", 5)
        Progress.report(c, "mine", "minecraft:stone", 2)
        assertEquals(2L, c.research.queue.single().tasks[0])
        Progress.report(c, "mine", "minecraft:stone", 9)
        assertEquals(NodeState.READY, c.research.queue.single().state)
        assertEquals(3L, c.research.queue.single().tasks[0])
        assertNull(Research.defs.taskIndex["kill"])
    }

    @Test
    fun countriesUseEveryTreeMatchingTheirScope() {
        val c = country("alpha")
        val province = country("beta", parent = c)
        assertEquals(listOf("t", "u"), Research.defs.treesFor(c).map { it.id })
        assertEquals(listOf("p", "u"), Research.defs.treesFor(province).map { it.id })
        assertEquals("u:shared", Queue.key(c, "shared"))
        assertEquals("t:a", Queue.key(c, "a"))
        assertEquals("p:x", Queue.key(province, "x"))
        assertEquals("nothing", Queue.key(c, "nothing"))
        Queue.enqueue(c, Queue.key(c, "shared"), null)
        Queue.enqueue(province, "u:shared", null)
        assertFailsWith<Fail> { Queue.enqueue(c, "p:x", null) }
        assertFailsWith<Fail> { Queue.enqueue(province, "t:a", null) }
        c.research.done["t:a"] = 1
        assertEquals("t:a", Queue.node(c, "t:a").key)
    }
}
