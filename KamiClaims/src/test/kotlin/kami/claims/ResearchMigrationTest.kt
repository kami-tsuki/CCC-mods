package kami.claims

import kami.claims.research.*
import kami.libs.config.ConfigFolder
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchMigrationTest : WithDb() {
    private val cutoff = "2026-01-01T00:00:00Z"
    private val before = Instant.parse("2025-12-01T00:00:00Z").toEpochMilli()
    private val after = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()

    private fun node(id: String, extra: String = "") = """{"id":"$id","category":"c","time":"10m","cost":100$extra}"""

    private fun mine(block: String, count: Int) = """{"type":"mine","block":"minecraft:$block","count":$count}"""

    private fun install(vararg nodes: String, mode: MigrationMode = MigrationMode.APPLY): Built {
        val tree = Configs.json().decodeFromString(TreeFile.serializer(), """{"scope":"country","categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}""")
        return Validator.build(ResearchSettings(baseline = emptyList(), migrations = mode), LevelsConfig(xpFromLevel = 0), mapOf("g" to Group()), mapOf("t" to tree)).also { Research.defs = it.defs }
    }

    private fun country(name: String): Country {
        val c = Country(name, treasury = 1000)
        Realm.data.countries[c.id] = c
        Realm.join(c, "${name}_p", Rank.PRESIDENT)
        return c
    }

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        Queue.clock = { 5_000_000_000_000L }
        Levels.announce = {}
    }

    private val dirs = ArrayList<java.nio.file.Path>()

    private fun tempDir() = Files.createTempDirectory("research").also { dirs.add(it) }

    @AfterTest
    fun restore() {
        dirs.forEach { it.toFile().deleteRecursively() }
        dirs.clear()
        Queue.clock = ::now
        Research.defs = ResearchDefs.EMPTY
        ResearchMigration.legacy = null
    }

    @Test
    fun aliasesMoveDoneBuffsAndQueueAndAreIdempotent() {
        install(node("old"), node("oldq"), node("other"))
        val c = country("alpha")
        c.research.done["t:old"] = 500
        c.buffs.enabled += "t:old"
        c.research.queue += QueueEntry("t:oldq", NodeState.READY)
        install(node("new", ""","replaces":["old"]"""), node("newq", ""","replaces":["oldq"]"""))
        assertEquals(3, ResearchMigration.run().size)
        assertEquals(500L, c.research.done["t:new"])
        assertNull(c.research.done["t:old"])
        assertEquals(500L, c.research.retired["t:old"])
        assertTrue("t:old" !in c.buffs.enabled)
        assertEquals(listOf("t:newq"), c.research.queue.map { it.node })
        assertTrue(ResearchMigration.run().isEmpty())
    }

    @Test
    fun aliasDuplicateIsRefundedAndRetiredKeysRollBack() {
        install(node("old"))
        val c = country("alpha")
        c.research.done["t:old"] = 500
        c.research.queue += QueueEntry("t:old", NodeState.RESEARCHING, paid = true, paidCost = 70)
        install(node("new", ""","replaces":["old"]"""))
        ResearchMigration.run()
        assertTrue(c.research.queue.isEmpty())
        assertEquals(1070L, c.treasury)
        install(node("old"), node("new"))
        ResearchMigration.run()
        assertEquals(500L, c.research.done["t:old"])
        assertTrue(c.research.retired.isEmpty())
    }

    @Test
    fun queueTaskProgressFollowsTheTaskSignature() {
        install(node("m", ""","tasks":[${mine("stone", 10)},${mine("dirt", 5)}]"""))
        val c = country("alpha")
        Queue.enqueue(c, "t:m", null)
        Queue.credit(c, "t:m", 0, 4)
        Queue.credit(c, "t:m", 1, 2)
        install(node("m", ""","tasks":[${mine("dirt", 5)},${mine("stone", 3)}]"""))
        assertEquals(1, ResearchMigration.run().size)
        assertEquals(mapOf(0 to 2L, 1 to 3L), c.research.queue.single().tasks)
        assertTrue(ResearchMigration.run().isEmpty())
    }

    @Test
    fun grandfatherHonorsCutoffLevelAndRefundsPaidEntries() {
        install(node("a"))
        val early = country("early").also { it.research.done["t:a"] = before }
        val late = country("late").also { it.research.done["t:a"] = after }
        val none = country("none")
        early.research.queue += QueueEntry("t:g", NodeState.RESEARCHING, paid = true, paidCost = 100)
        val rule = """"grandfather":[{"from":["a"],"before":"$cutoff"}]"""
        install(node("a"), node("g", ",$rule"), node("h", ""","level":5,$rule"""))
        ResearchMigration.run()
        assertNotNull(early.research.done["t:g"])
        assertNotNull(early.research.done["t:h"])
        assertTrue(early.research.queue.isEmpty())
        assertEquals(1100L, early.treasury)
        assertEquals(0L, early.xp)
        assertEquals(2, early.research.migrations.size)
        assertTrue(late.research.done.keys == setOf("t:a") && none.research.done.isEmpty())
        early.research.done.remove("t:g")
        ResearchMigration.run()
        assertNull(early.research.done["t:g"])
    }

    @Test
    fun orphansAreParkedAndComeBackWhenTheirNodeReturns() {
        install(node("a"), node("b"))
        val c = country("alpha")
        Queue.enqueue(c, "t:a", null)
        c.research.queue += QueueEntry("t:b", NodeState.RESEARCHING, paid = true, paidCost = 50)
        install(node("z"))
        Queue.dropOrphans()
        assertTrue(c.research.queue.isEmpty())
        assertEquals(listOf("t:a", "t:b"), c.research.parked.map { it.node })
        install(node("a"), node("b"))
        ResearchMigration.run()
        assertTrue(c.research.parked.isEmpty())
        assertEquals(listOf("t:a", "t:b"), c.research.queue.map { it.node })
        install(node("z"))
        Queue.dropOrphans()
        assertEquals(50L, ResearchMigration.refundParked(c).total)
        assertTrue(c.research.parked.isEmpty())
    }

    @Test
    fun validatorReportsMigrationProblems() {
        val problems = install(
            node("a"),
            node("b", ""","replaces":["a","b","gone"]"""),
            node("c", ""","replaces":["gone"],"grandfather":[{"from":["nope"],"before":"yesterday"},{"from":[],"before":"$cutoff"}]""")
        ).problems
        listOf("t:b replaces itself", "t:b replaces t:a which still exists", "t:gone is replaced by several nodes", "is not an ISO instant", "from is empty", "unknown node t:nope").forEach { part ->
            assertTrue(problems.any { it.contains(part) }, part)
        }
    }

    @Test
    fun dryRunReportsWithoutChangingAndOffDoesNothing() {
        install(node("old"))
        val c = country("alpha")
        c.research.done["t:old"] = 500
        install(node("new", ""","replaces":["old"]"""), mode = MigrationMode.DRY_RUN)
        assertEquals(1, ResearchMigration.run().size)
        assertEquals(500L, c.research.done["t:old"])
        assertNull(c.research.done["t:new"])
        install(node("new", ""","replaces":["old"]"""), mode = MigrationMode.OFF)
        assertTrue(ResearchMigration.run().isEmpty())
        assertNull(c.research.done["t:new"])
    }

    @Test
    fun oldSavesLoadWithMigrationDefaults() {
        val state = Configs.json().decodeFromString(ResearchState.serializer(), """{"done":{"t:a":1},"queue":[{"node":"t:a","state":"READY"}]}""")
        assertTrue(state.retired.isEmpty() && state.migrations.isEmpty() && state.parked.isEmpty())
        assertTrue(state.queue.single().sigs.isEmpty())
        state.queue.single().node = "t:b"
        val back = Configs.json().decodeFromString(ResearchState.serializer(), Configs.json().encodeToString(ResearchState.serializer(), state))
        assertEquals("t:b", back.queue.single().node)
    }

    private val rule = """"grandfather":[{"from":["a"],"before":"$cutoff"}]"""

    @Test
    fun refundsIgnoreTheTreasuryCapAndKeepUnknownCosts() {
        install(node("a"))
        val c = country("alpha")
        c.treasury = Levels.capacity(c, Capacity.TREASURY) - 10L
        c.research.parked += QueueEntry("t:a", NodeState.RESEARCHING, paid = true, paidCost = 70)
        c.research.parked += QueueEntry("t:gone", NodeState.RESEARCHING, paid = true)
        assertEquals(70L, ResearchMigration.refundParked(c).total)
        assertEquals(Levels.capacity(c, Capacity.TREASURY) + 60L, c.treasury)
        assertEquals(listOf("t:gone"), c.research.parked.map { it.node })
    }

    @Test
    fun legacySignaturesRemapEntriesWithoutSigs() {
        ResearchMigration.legacy = LegacyData(sigs = mapOf("t:m" to listOf("mine|minecraft:stone", "mine|minecraft:dirt")))
        install(node("m", ""","tasks":[${mine("dirt", 5)},${mine("stone", 3)}]"""))
        val c = country("alpha")
        c.research.queue += QueueEntry("t:m", tasks = mutableMapOf(0 to 4L, 1 to 2L))
        assertEquals(1, ResearchMigration.run().size)
        assertEquals(mapOf(0 to 2L, 1 to 3L), c.research.queue.single().tasks)
        assertEquals(2, c.research.queue.single().sigs.size)
        assertTrue(ResearchMigration.run().isEmpty())
    }

    @Test
    fun grandfatherAlsoMatchesRetiredKeysAndChainsKeepTheSourceTime() {
        install(node("g", ",$rule"))
        val c = country("alpha")
        c.research.retired["t:a"] = before
        ResearchMigration.run()
        assertEquals(before, c.research.done["t:g"])
        val chain = """"grandfather":[{"from":["g"],"before":"$cutoff"}]"""
        install(node("a"), node("h", ",$chain"), node("g", ",$rule"))
        val d = country("beta").also { it.research.done["t:a"] = before }
        ResearchMigration.run()
        assertEquals(before, d.research.done["t:g"])
        assertEquals(before, d.research.done["t:h"])
    }

    @Test
    fun unparkRespectsResearchAndQueueSlots() {
        install(node("x"), node("y"), node("z"))
        val c = country("alpha")
        c.research.parked += QueueEntry("t:x", NodeState.RESEARCHING, paid = true, paidCost = 1)
        c.research.parked += QueueEntry("t:y", NodeState.RESEARCHING, paid = true, paidCost = 1)
        c.research.parked += QueueEntry("t:z")
        ResearchMigration.run()
        assertEquals(listOf("t:x" to NodeState.RESEARCHING, "t:y" to NodeState.PAUSED), c.research.queue.map { it.node to it.state })
        assertEquals(listOf("t:z"), c.research.parked.map { it.node })
    }

    @Test
    fun readyEntriesWithOpenTasksAreDemoted() {
        install(node("m", ""","tasks":[${mine("stone", 5)}]"""))
        val c = country("alpha")
        c.research.queue += QueueEntry("t:m", NodeState.READY, tasks = mutableMapOf(0 to 5L))
        assertEquals(0, ResearchMigration.run().size)
        assertEquals(NodeState.READY, c.research.queue.single().state)
        assertEquals(5L, c.research.queue.single().tasks[0])
        c.research.queue.single().tasks.clear()
        assertEquals(1, ResearchMigration.run().size)
        assertEquals(NodeState.QUEUED, c.research.queue.single().state)
    }

    @Test
    fun aliasChainsAreFollowedAndRollbackMovesQueueAndBuffsBack() {
        install(node("c", ""","replaces":["b"]"""))
        val c = country("alpha")
        c.research.queue += QueueEntry("t:a")
        c.research.migrations["alias:t:a>t:b"] = 1
        ResearchMigration.run()
        assertEquals("t:c", c.research.queue.single().node)
        install(node("old"))
        val d = country("beta")
        d.research.queue += QueueEntry("t:old")
        d.buffs.enabled += "t:old"
        install(node("new", ""","replaces":["old"]"""))
        ResearchMigration.run()
        assertEquals("t:new", d.research.queue.single().node)
        install(node("old"), node("new"))
        ResearchMigration.run()
        assertEquals("t:old", d.research.queue.single().node)
        assertTrue(d.research.migrations.isEmpty())
    }

    @Test
    fun flatFromInsideTheCurveStartIsRejected() {
        val built = Validator.build(ResearchSettings(), LevelsConfig(curve = XpCurve(from = 11, flatFrom = 5)), emptyMap(), emptyMap())
        assertTrue(built.problems.any { it.contains("flatFrom") })
        assertEquals(0, built.defs.levels.curve.flatFrom)
    }

    @Test
    fun upgradedLevelsFileIsWrittenBackOnce() {
        val dir = tempDir()
        Files.writeString(dir.resolve("levels.json"), """{"version":2,"announce":false}""")
        Research.load(ConfigFolder(dir, "test"))
        val path = dir.resolve("levels.json")
        val written = Configs.json().decodeFromString(LevelsConfig.serializer(), Jsonc.read(path))
        assertEquals(LevelDefaults.VERSION, written.version)
        assertEquals(false, written.announce)
        assertTrue(Files.exists(dir.resolve("levels.json.v2.bak")))
    }

    @Test
    fun existingLevelsBackupIsNeverOverwritten() {
        val dir = tempDir()
        Files.writeString(dir.resolve("levels.json"), """{"version":2,"announce":false}""")
        Files.writeString(dir.resolve("levels.json.v2.bak"), "keep")
        Research.load(ConfigFolder(dir, "test"))
        assertEquals("keep", Files.readString(dir.resolve("levels.json.v2.bak")))
        assertEquals(LevelDefaults.VERSION, Configs.json().decodeFromString(LevelsConfig.serializer(), Jsonc.read(dir.resolve("levels.json"))).version)
    }

    private fun deposit(item: String, count: Int) = """{"type":"deposit","item":"minecraft:$item","count":$count}"""

    @Test
    fun mergedDepositTasksSumTheirProgress() {
        install(node("m", ""","tasks":[${deposit("amethyst_shard", 20)}]"""))
        val c = country("alpha")
        val dep = "deposit|minecraft:amethyst_shard"
        c.research.queue += QueueEntry("t:m", tasks = mutableMapOf(0 to 3L, 1 to 4L), sigs = mutableListOf(dep, dep, "deposit|minecraft:iron_ingot"))
        assertEquals(1, ResearchMigration.run().size)
        assertEquals(mapOf(0 to 7L), c.research.queue.single().tasks)
    }

    @Test
    fun unmatchedDepositProgressCarriesAsAFraction() {
        install(node("m", ""","tasks":[${deposit("gold_ingot", 40)},${deposit("copper_ingot", 10)}]"""))
        val c = country("alpha")
        c.research.queue += QueueEntry(
            "t:m", tasks = mutableMapOf(0 to 5L, 1 to 1L),
            sigs = mutableListOf("deposit|minecraft:iron_ingot", "deposit|minecraft:tin_ingot"), targets = mutableListOf(10L, 100L)
        )
        ResearchMigration.run()
        assertEquals(mapOf(0 to 20L, 1 to 1L), c.research.queue.single().tasks)
        assertEquals(listOf(40L, 10L), c.research.queue.single().targets)
    }

    @Test
    fun legacyCostRefundIsExactlyTheLegacyCost() {
        install(node("a"))
        val c = country("alpha")
        ResearchMigration.legacy = LegacyData(costs = mapOf("t:a" to 60L))
        c.research.parked += QueueEntry("t:a", NodeState.RESEARCHING, paid = true)
        assertEquals(60L, ResearchMigration.refundParked(c).total)
        ResearchMigration.legacy = LegacyData(costs = mapOf("t:a" to 150L))
        c.research.parked += QueueEntry("t:a", NodeState.RESEARCHING, paid = true)
        assertEquals(150L, ResearchMigration.refundParked(c).total)
    }

    @Test
    fun unknownLegacyCostKeepsThePaidEntryParked() {
        install(node("a"))
        val c = country("alpha")
        ResearchMigration.legacy = LegacyData()
        c.research.parked += QueueEntry("t:a", NodeState.RESEARCHING, paid = true)
        val result = ResearchMigration.refundParked(c)
        assertEquals(0L, result.total)
        assertEquals(1, result.kept)
        assertEquals(listOf("t:a"), c.research.parked.map { it.node })
    }

    @Test
    fun holdSignatureIsStructuralAndQualified() {
        install(node("a"), node("h", ""","tasks":[{"type":"hold","condition":{"type":"all","of":[{"type":"node","id":"a"},{"type":"treasury","min":50}]}}]"""))
        assertEquals(listOf("hold|all(node:t:a,treasury:50)"), ResearchMigration.sigs(Research.defs.node("t:h")!!))
    }

    @Test
    fun zeroTaskNodesWithLegacySigsStabilizeAfterOneRun() {
        ResearchMigration.legacy = LegacyData(sigs = mapOf("t:z" to listOf("mine|minecraft:stone")))
        install(node("z"))
        val c = country("alpha")
        c.research.queue += QueueEntry("t:z")
        assertEquals(1, ResearchMigration.run().size)
        assertTrue(ResearchMigration.run().isEmpty())
        assertTrue(ResearchMigration.run().isEmpty())
    }

    @Test
    fun researchingEntriesKeepTheirTasksButGetStamped() {
        install(node("m", ""","tasks":[${mine("dirt", 5)},${mine("stone", 3)}]"""))
        val c = country("alpha")
        c.research.queue += QueueEntry("t:m", NodeState.RESEARCHING, paid = true, tasks = mutableMapOf(0 to 4L, 1 to 2L), sigs = mutableListOf("mine|minecraft:stone", "mine|minecraft:dirt"))
        assertEquals(1, ResearchMigration.run().size)
        assertEquals(mapOf(0 to 4L, 1 to 2L), c.research.queue.single().tasks)
        assertEquals(2, c.research.queue.single().sigs.size)
        assertTrue(ResearchMigration.run().isEmpty())
    }

    @Test
    fun unmatchedNonDepositProgressCarriesWithinTheSameKind() {
        install(node("m", ""","tasks":[${mine("diamond_ore", 20)},{"type":"kill","entity":"minecraft:zombie","count":10}]"""))
        val c = country("alpha")
        c.research.queue += QueueEntry(
            "t:m", tasks = mutableMapOf(0 to 5L, 1 to 2L),
            sigs = mutableListOf("mine|minecraft:iron_ore", "craft|minecraft:stick"), targets = mutableListOf(10L, 4L)
        )
        assertEquals(1, ResearchMigration.run().size)
        assertEquals(mapOf(0 to 10L), c.research.queue.single().tasks)
        assertTrue(ResearchMigration.run().isEmpty())
    }

    @Test
    fun dryRunNeverBanksRefundsOrTouchesState() {
        install(node("old"))
        val c = country("alpha")
        c.research.done["t:old"] = 500
        c.research.queue += QueueEntry("t:old", NodeState.RESEARCHING, paid = true, paidCost = 70)
        install(node("new", ""","replaces":["old"]"""), mode = MigrationMode.DRY_RUN)
        assertTrue(ResearchMigration.run().isNotEmpty())
        assertEquals(1000L, c.treasury)
        assertEquals(1, c.research.queue.size)
        assertEquals(70L, c.research.queue.single().paidCost)
        assertEquals(500L, c.research.done["t:old"])
        assertEquals(ResearchMigration.run().size, ResearchMigration.run().size)
    }
}
