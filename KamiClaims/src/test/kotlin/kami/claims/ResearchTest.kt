package kami.claims

import kami.claims.research.*
import kami.libs.config.ConfigFolder
import kami.libs.config.Configs
import kami.libs.text.LangAudit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchTest {
    private val json = Configs.json()

    private fun file(vararg nodes: String, categories: String = """[{"id":"a"}]""") =
        json.decodeFromString(TreeFile.serializer(), """{"categories":$categories,"nodes":[${nodes.joinToString(",")}]}""")

    private fun node(id: String, requires: String = "", extra: String = "") =
        """{"id":"$id","category":"a","time":"10m","requires":[$requires]$extra}"""

    private fun dep(id: String) = """{"type":"node","id":"$id"}"""

    private fun build(files: Map<String, TreeFile>, previous: Map<String, Tree> = emptyMap()) =
        Validator.build(ResearchSettings(baseline = emptyList()), LevelsConfig(), mapOf("g" to Group()), files, previous)

    @Test
    fun bundledDefaultsValidateWithoutProblems() {
        val built = Validator.build(ResearchSettings(), LevelsConfig(), Defaults.groups, Defaults.trees)
        assertEquals(emptyList(), built.problems)
        assertEquals(setOf("metallurgy", "technology", "buffs"), built.defs.trees.keys)
        assertEquals(Scope.ANY, built.defs.trees.getValue("metallurgy").scope)
        assertEquals(0, LevelsConfig().capacity(Capacity.PROVINCES))
    }

    @Test
    fun levelRewardsAreValidatedAgainstRangeAndGroups() {
        val levels = LevelsConfig(maxLevel = 5, rules = emptyMap(), rewards = mapOf(9 to listOf(MoneyReward(1)), 2 to listOf(GroupRef("nope"))))
        val problems = Validator.build(ResearchSettings(baseline = emptyList()), levels, mapOf("g" to Group()), emptyMap()).problems
        assertEquals(2, problems.count { it.startsWith("levels.json") })
    }

    @Test
    fun moneyIsOnlyAllowedAsLevelReward() {
        val built = build(mapOf("t" to file(node("a", extra = ""","unlocks":[{"type":"money","amount":5}]"""), node("b"))))
        assertEquals(setOf("t:b"), built.defs.trees.getValue("t").nodes.map { it.key }.toSet())
    }

    @Test
    fun anyConditionsAreTheOnlySoftDependencies() {
        val tree = build(mapOf("t" to file(node("a"), node("b"), node("c", dep("a") + "," + """{"type":"any","of":[${dep("b")}]}""")))).defs.trees.getValue("t")
        val c = tree.nodes.first { it.key == "t:c" }
        assertEquals(listOf("t:a"), c.requires.flatMap { it.hardRefs() })
        assertEquals(listOf("t:a", "t:b"), c.requires.flatMap { it.refs() })
    }

    @Test
    fun bundledDefaultsHaveTranslations() {
        val dir = Path.of("src/main/resources/assets/kami_claims/lang")
        val en = LangAudit.load(dir.resolve("en_us.json"))
        val de = LangAudit.load(dir.resolve("de_de.json"))
        val defs = Validator.build(ResearchSettings(), LevelsConfig(), Defaults.groups, Defaults.trees).defs
        val keys = defs.trees.values.flatMap { t -> listOf("tree.${t.id}") + t.categories.map { "category.${it.id}" } + t.nodes.map { "node.${it.tree}.${it.id}" } }
        keys.map { "kami_claims.research.$it" }.forEach { assertTrue(it in en && it in de, "missing $it") }
    }

    @Test
    fun configFolderWritesDefaultsAndLoadsThem() {
        val dir = Files.createTempDirectory("research")
        val folder = ConfigFolder(dir, "test")
        assertEquals(emptyList(), Research.load(folder))
        listOf("research.json", "levels.json", "groups/basics.json", "trees/metallurgy.json", "trees/technology.json").forEach { assertTrue(Files.exists(dir.resolve(it)), it) }
        assertTrue(Research.defs.node("metallurgy:stone_tools") != null)
        assertEquals(setOf("metallurgy", "technology", "buffs"), Research.defs.trees.keys)
    }

    @Test
    fun brokenTreeFileKeepsLastGoodVersion() {
        val dir = Files.createTempDirectory("research")
        val folder = ConfigFolder(dir, "test")
        Research.load(folder)
        val path = dir.resolve("trees/metallurgy.json")
        Files.writeString(path, "{ broken")
        assertTrue(Research.load(folder).isNotEmpty())
        assertTrue(Research.defs.node("metallurgy:stone_tools") != null)
    }

    @Test
    fun parsesConditionsUnlocksAndTasks() {
        val text = node(
            "x", """{"type":"all","of":[{"type":"level","min":2},{"type":"not","of":{"type":"province"}},{"type":"chunks","chunkType":"farming","min":3}]}""",
            ""","tasks":[{"type":"deposit","item":"#c:ingots/iron","count":5},{"type":"hold","condition":{"type":"treasury","min":10}}],"unlocks":[{"type":"capacity","key":"researchSlots","add":1},{"type":"recipe_type","id":"create:pressing"}]"""
        )
        val n = json.decodeFromString(Node.serializer(), text)
        assertEquals(listOf(5L, 1L), n.tasks.map { it.target })
        assertEquals(2, n.unlocks.size)
        assertTrue(n.requires.single() is AllOf)
        assertEquals(10, n.time.inWholeMinutes.toInt())
    }

    @Test
    fun cyclesRejectTheTree() {
        val built = build(mapOf("t" to file(node("a", dep("b")), node("b", dep("a")), node("c"))))
        assertFalse("t" in built.defs.trees)
        assertTrue(built.problems.any { "cycle" in it })
    }

    @Test
    fun cycleKeepsPreviousVersion() {
        val good = build(mapOf("t" to file(node("a"), node("b", dep("a"))))).defs.trees.getValue("t")
        val next = build(mapOf("t" to file(node("a", dep("b")), node("b", dep("a")))), mapOf("t" to good))
        assertTrue(next.defs.trees.getValue("t") === good)
    }

    @Test
    fun unknownTypeDisablesOnlyThatNodeAndItsDependents() {
        val built = build(mapOf("t" to file(
            node("ok"),
            node("bad", extra = ""","unlocks":[{"type":"teleport","id":"x"}]"""),
            node("child", dep("bad")),
            node("other", dep("ok"))
        )))
        assertEquals(setOf("t:ok", "t:other"), built.defs.trees.getValue("t").nodes.map { it.key }.toSet())
        assertEquals(2, built.problems.size)
    }

    @Test
    fun referencesMustResolve() {
        val built = build(mapOf("t" to file(
            node("missingCategory").replace("\"a\"", "\"zzz\""),
            node("missingGroup", extra = ""","unlocks":[{"type":"group","id":"nope"}]"""),
            node("missingNode", dep("ghost")),
            node("fine", extra = ""","unlocks":[{"type":"group","id":"g"}]""")
        )))
        assertEquals(setOf("t:fine"), built.defs.trees.getValue("t").nodes.map { it.key }.toSet())
    }

    @Test
    fun duplicateIdsRejectTheTree() {
        assertFalse("t" in build(mapOf("t" to file(node("a"), node("a")))).defs.trees)
        assertFalse("t" in build(mapOf("t" to file(node("a"), categories = """[{"id":"a"},{"id":"a"}]"""))).defs.trees)
    }

    @Test
    fun badDurationDisablesNode() {
        val built = build(mapOf("t" to file(node("a").replace("10m", "soon"), node("b"))))
        assertEquals(setOf("t:b"), built.defs.trees.getValue("t").nodes.map { it.key }.toSet())
    }

    @Test
    fun wireLimitsAndTickSecondsAreEnforced() {
        val long = "x".repeat(Limits.MAX_TEXT + 1)
        val settings = ResearchSettings(baseline = emptyList(), tickSeconds = 0)
        val built = Validator.build(settings, LevelsConfig(), mapOf("g" to Group()), mapOf("t" to file(node("a", extra = ""","title":"$long""""), node("b"))))
        assertEquals(setOf("t:b"), built.defs.trees.getValue("t").nodes.map { it.key }.toSet())
        assertEquals(1, built.defs.settings.tickSeconds)
        assertTrue(built.problems.any { "tickSeconds" in it } && built.problems.any { "longer than" in it })
    }

    @Test
    fun holdTasksQualifyAndValidateNodeReferences() {
        val hold = { id: String -> node("h$id", extra = ""","tasks":[{"type":"hold","condition":{"type":"not","of":{"type":"node","id":"$id"}}}]""") }
        val built = build(mapOf("t" to file(node("a"), hold("a"), hold("ghost"))))
        val kept = built.defs.trees.getValue("t").nodes
        assertEquals(setOf("t:a", "t:ha"), kept.map { it.key }.toSet())
        assertEquals(listOf("t:a"), kept.first { it.id == "ha" }.dependencies())
        assertTrue(built.problems.any { "unknown node t:ghost" in it })
    }

    @Test
    fun conditionsEvaluateAgainstTheCountry() {
        Realm.reset(Data())
        val c = Country("Condland", treasury = 500)
        Realm.data.countries[c.id] = c
        assertTrue(MinTreasury(500).met(c))
        assertFalse(MinTreasury(501).met(c))
        assertTrue(NotProvince.met(c))
        assertFalse(NodeDone("t:a").met(c))
        c.research.done["t:a"] = 1
        assertTrue(AllOf(listOf(NodeDone("t:a"), Not(IsProvince))).met(c))
        assertTrue(MinResearched(1).met(c))
    }

    @Test
    fun levelCurve() {
        val levels = LevelsConfig(curve = XpCurve(from = 2, base = 100.0, rise = 0.0), maxLevel = 5, rules = emptyMap())
        assertEquals(listOf(0L, 0L, 100L, 200L, 300L, 400L), (0..5).map(levels::xpFor))
    }

    @Test
    fun levelTableReplacesCurveAndCapsLevel() {
        val levels = LevelsConfig(table = listOf(10, 50), maxLevel = 9, rules = emptyMap())
        assertEquals(3, levels.top)
    }

    @Test
    fun oldSaveWithoutResearchFieldsLoads() {
        val old = json.decodeFromString(Country.serializer(), """{"name":"Oldland","treasury":42,"members":{"u":{"rank":"PRESIDENT"}}}""")
        assertEquals(42, old.treasury)
        assertEquals(0, old.xp)
        assertTrue(old.research.done.isEmpty() && old.research.queue.isEmpty() && old.xpToday.isEmpty())
    }

    @Test
    fun researchStateRoundTrips() {
        val c = Country("Roundland")
        c.research.done["country:land_1"] = 5
        c.research.queue += QueueEntry("country:land_2", NodeState.PAUSED, 1000, true, mutableMapOf(0 to 7L), "u")
        c.xp = 250
        c.xpToday["tasks"] = 10
        val back = json.decodeFromString(Country.serializer(), json.encodeToString(Country.serializer(), c))
        assertEquals(5L, back.research.done["country:land_1"])
        assertEquals(NodeState.PAUSED, back.research.queue.single().state)
        assertEquals(7L, back.research.queue.single().tasks[0])
        assertEquals(250L, back.xp)
        assertEquals(10L, back.xpToday["tasks"])
    }
}
