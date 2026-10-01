package kami.claims

import kami.claims.net.ResearchSync
import kami.claims.research.*
import kami.libs.config.Configs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerTreeTest {
    private val json = Configs.json()
    private val open = ResearchSettings(baseline = emptyList(), locked = emptyList())

    private fun file(vararg nodes: String) =
        json.decodeFromString(TreeFile.serializer(), """{"categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}""")

    private fun node(id: String, vararg requires: String, extra: String = "") =
        """{"id":"$id","category":"c","requires":[${requires.joinToString(",") { """{"type":"node","id":"$it"}""" }}]$extra}"""

    private fun build(files: Map<String, TreeFile>, levels: LevelsConfig = LevelsConfig()) = Validator.build(open, levels, emptyMap(), files)

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        Levels.announce = {}
    }

    @AfterTest
    fun restore() {
        Research.defs = ResearchDefs.EMPTY
        Gate.install(Resolution.EMPTY)
        Levels.announce = { }
    }

    private fun country(): Country {
        val c = Country("alpha", treasury = 1000)
        Realm.data.countries[c.id] = c
        Realm.join(c, "alpha_p", Rank.PRESIDENT)
        return c
    }

    @Test
    fun depositAcceptsAnyListedItemAndKeepsTheSingleForm() {
        val task = json.decodeFromString(Task.serializer(), """{"type":"deposit","items":["minecraft:raw_gold","minecraft:gold_ore"],"count":16}""") as DepositTask
        assertTrue(task.accepts("minecraft:raw_gold") && task.accepts("minecraft:gold_ore"))
        assertFalse(task.accepts("minecraft:stone"))
        assertEquals(16L, task.target)
        val single = json.decodeFromString(Task.serializer(), """{"type":"deposit","item":"minecraft:cobblestone","count":64}""") as DepositTask
        assertTrue(single.accepts("minecraft:cobblestone"))
        assertFalse(single.accepts("minecraft:raw_gold"))
    }

    @Test
    fun recipesUnlockNeedsEveryGivenFieldToMatch() {
        val facts = listOf(
            RecipeFact("a", "minecraft:smelting", listOf("minecraft:gold_ingot"), listOf("minecraft:raw_gold")),
            RecipeFact("b", "minecraft:blasting", listOf("minecraft:gold_ingot"), listOf("minecraft:raw_gold")),
            RecipeFact("c", "minecraft:smelting", listOf("minecraft:iron_ingot"), listOf("minecraft:raw_iron")),
            RecipeFact("d", "minecraft:smelting", listOf("minecraft:gold_ingot"), listOf("minecraft:gold_ore", "minecraft:deepslate_gold_ore")),
            RecipeFact("e", "create:crushing", listOf("create:crushed_raw_gold"), listOf("minecraft:raw_gold"))
        )
        val world = WorldFacts(facts, emptyList(), itemTagged = { tag, id -> tag == "c:ores/gold" && id.endsWith("gold_ore") })
        fun matched(unlock: String): Set<String> {
            val defs = build(mapOf("t" to file(node("n", extra = ""","unlocks":[$unlock]""")))).defs
            return Resolver(world, defs).run().nodes.getValue("t:n").recipes
        }
        assertEquals(setOf("a"), matched("""{"type":"recipes","recipeType":"minecraft:smelting","input":"minecraft:raw_gold","output":"minecraft:gold_ingot"}"""))
        assertEquals(setOf("a", "b", "d"), matched("""{"type":"recipes","recipeType":"minecraft:*ing","output":"minecraft:gold_ingot"}"""))
        assertEquals(setOf("d"), matched("""{"type":"recipes","input":"#c:ores/gold"}"""))
        assertEquals(setOf("e"), matched("""{"type":"recipes","recipeType":"create:*","output":"*:crushed_raw_gold"}"""))
    }

    @Test
    fun emptyRecipesUnlockIsRejected() {
        val built = build(mapOf("t" to file(node("n", extra = ""","unlocks":[{"type":"recipes"}]"""), node("ok"))))
        assertEquals(setOf("t:ok"), built.defs.trees.getValue("t").nodes.map { it.key }.toSet())
    }

    @Test
    fun crossTreeRequirementsValidateAndEvaluate() {
        val built = build(mapOf("a" to file(node("x")), "b" to file(node("y", "a:x"), node("lost", "a:ghost"))))
        assertEquals(setOf("b:y"), built.defs.trees.getValue("b").nodes.map { it.key }.toSet())
        assertTrue(built.problems.any { "unknown node a:ghost" in it })
        Research.defs = built.defs
        val c = country()
        val y = built.defs.node("b:y")!!
        assertFalse(y.requires.all { it.met(c) })
        c.research.done["a:x"] = 1
        assertTrue(y.requires.all { it.met(c) })
        val view = ResearchSync.defs().trees.first { it.id == "b" }.nodes.single()
        assertEquals(emptyList(), view.requires + view.anyRequires)
        assertEquals(1, view.conditions.size)
    }

    @Test
    fun crossTreeCyclesAreCaught() {
        val built = build(mapOf("a" to file(node("x", "b:y")), "b" to file(node("y", "a:x"))))
        assertTrue(built.problems.any { "cycle" in it })
        assertTrue(built.defs.trees.isEmpty())
    }

    @Test
    fun theStoredLevelNeverDrops() {
        Research.defs = build(emptyMap()).defs
        val c = country()
        c.xp = 50
        repeat(12) { Realm.add(Claim(c.id, "minecraft:overworld", it, 0, "civic")) }
        Levels.advance(c)
        val level = Research.level(c)
        assertTrue(level > 1)
        Realm.claims(c.id).forEach { Realm.unclaim(it, false) }
        Levels.advance(c)
        assertEquals(level, Research.level(c))
        assertEquals(level, c.level)
    }
}
