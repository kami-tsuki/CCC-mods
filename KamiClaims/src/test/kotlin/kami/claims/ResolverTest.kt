package kami.claims

import kami.claims.net.DefsView
import kami.claims.net.ExtraKeys
import kami.claims.net.IdExtras
import kami.claims.net.ResearchWire
import kami.claims.research.*
import kami.libs.config.Configs

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolverTest {
    private val recipes = listOf(
        RecipeFact("minecraft:iron_pickaxe", "minecraft:crafting_shaped", listOf("minecraft:iron_pickaxe")),
        RecipeFact("minecraft:iron_axe", "minecraft:crafting_shaped", listOf("minecraft:iron_axe")),
        RecipeFact("minecraft:oak_planks", "minecraft:crafting_shapeless", listOf("minecraft:oak_planks")),
        RecipeFact("create:crushing/iron_ore", "create:crushing", listOf("create:crushed_raw_iron")),
        RecipeFact("create:milling/wheat", "create:milling", listOf("minecraft:flour")),
        RecipeFact("tfmg:casting/steel", "tfmg:casting", listOf("tfmg:steel_ingot"))
    )
    private val world = WorldFacts(
        recipes,
        listOf("minecraft:loom", "minecraft:oak_planks", "minecraft:furnace", "create:mechanical_press"),
        itemTagged = { tag, id -> tag == "c:tools" && id.endsWith("_axe") },
        blockTagged = { tag, id -> tag == "minecraft:planks" && id == "minecraft:oak_planks" },
        modLoaded = { it != "nuclear" }
    )
    private val open = ResearchSettings(baseline = emptyList(), locked = emptyList())

    private fun defs(
        nodes: List<String>,
        groups: Map<String, Group> = emptyMap(),
        levels: LevelsConfig = LevelsConfig(rewards = emptyMap()),
        settings: ResearchSettings = open
    ): ResearchDefs {
        val tree = Configs.json().decodeFromString(TreeFile.serializer(), """{"categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}""")
        return Validator.build(settings, levels, groups, mapOf("t" to tree)).defs
    }

    private fun node(id: String, vararg unlocks: String) = """{"id":"$id","category":"c","unlocks":[${unlocks.joinToString(",")}]}"""

    private fun recipe(id: String) = """{"type":"recipe","id":"$id"}"""
    private fun block(id: String) = """{"type":"block","id":"$id"}"""

    private fun resolve(defs: ResearchDefs) = Resolver(world, defs).run()

    private fun recipeOpen(country: Country?, id: String) = Gate.recipe(country, id.substringBefore(':'), id.substringAfter(':'))

    private fun blockOpen(country: Country?, id: String) = Gate.block(country, id.substringBefore(':'), id.substringAfter(':'))

    private fun extras(keys: ExtraKeys) = IdExtras.decode(ResearchWire.decodeDefs(ResearchWire.encodeDefs(DefsView(emptyList(), IdExtras.encode(Gate.resolution)))).extras, keys)

    @AfterTest
    fun restore() {
        Research.defs = ResearchDefs.EMPTY
        Gate.install(Resolution.EMPTY)
    }

    @Test
    fun selectorsResolveAgainstFacts() {
        val r = resolve(defs(listOf(
            node("a", recipe("minecraft:iron_*")),
            node("b", """{"type":"recipe_type","id":"create:*"}"""),
            node("c", """{"type":"output","id":"#c:tools"}"""),
            node("d", """{"type":"mod","id":"tfmg"}"""),
            node("e", block("#minecraft:planks"), block("minecraft:loom"))
        )))
        assertEquals(setOf("minecraft:iron_pickaxe", "minecraft:iron_axe"), r.nodes.getValue("t:a").recipes)
        assertEquals(setOf("create:crushing/iron_ore", "create:milling/wheat"), r.nodes.getValue("t:b").recipes)
        assertEquals(setOf("minecraft:iron_axe"), r.nodes.getValue("t:c").recipes)
        assertEquals(setOf("tfmg:casting/steel"), r.nodes.getValue("t:d").recipes)
        assertEquals(setOf("minecraft:oak_planks", "minecraft:loom"), r.nodes.getValue("t:e").blocks)
        assertFalse("minecraft:oak_planks" in r.gated.recipes)
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun emptyMatchesWarnUnlessTheModIsMissing() {
        val r = resolve(defs(listOf(
            node("a", recipe("nuclear:*")),
            node("b", recipe("create:missing_*")),
            node("c", """{"type":"mod","id":"nuclear"}""")
        )))
        assertEquals(1, r.problems.size)
        assertTrue("create:missing_*" in r.problems.single())
        assertEquals(2, r.notes.size)
    }

    @Test
    fun baselineIsRemovedFromTheGatedSet() {
        val groups = mapOf("basics" to Group(unlocks = listOf(RecipeUnlock("minecraft:oak_planks"))))
        val settings = ResearchSettings(baseline = listOf("basics"), locked = emptyList())
        val r = resolve(defs(listOf(node("a", recipe("minecraft:oak_planks"), recipe("minecraft:iron_axe"))), groups, settings = settings))
        assertEquals(setOf("minecraft:iron_axe"), r.gated.recipes)
        assertTrue(r.problems.single().contains("baseline"))
    }

    @Test
    fun groupsExpandAndLockedSettingsGate() {
        val groups = mapOf("g" to Group(unlocks = listOf(RecipeTypeUnlock("create:milling"))))
        val settings = ResearchSettings(baseline = emptyList(), locked = listOf(BlockUnlock("minecraft:loom")))
        val r = resolve(defs(listOf(node("a", """{"type":"group","id":"g"}""")), groups, settings = settings))
        assertEquals(setOf("create:milling/wheat"), r.nodes.getValue("t:a").recipes)
        assertEquals(setOf("minecraft:loom"), r.gated.blocks)
    }

    private fun setup(): Country {
        val levels = LevelsConfig(rules = emptyMap(), rewards = mapOf(3 to listOf(RecipeUnlock("minecraft:iron_axe"), BlockUnlock("minecraft:loom"))), table = listOf(100, 200))
        val d = defs(listOf(node("a", recipe("minecraft:iron_pickaxe"), block("create:mechanical_press"))), levels = levels)
        Research.defs = d
        Gate.install(resolve(d))
        return Country("alpha")
    }

    @Test
    fun allowedTruthTable() {
        val country = setup()
        val pickaxe = "minecraft:iron_pickaxe"
        val axe = "minecraft:iron_axe"
        val planks = "minecraft:oak_planks"
        assertTrue(recipeOpen(null, planks))
        assertTrue(recipeOpen(country, planks))
        assertFalse(recipeOpen(null, pickaxe))
        assertFalse(recipeOpen(country, pickaxe))
        country.research.done["t:a"] = 1
        Gate.invalidate(country)
        assertTrue(recipeOpen(country, pickaxe))
        assertTrue(blockOpen(country, "create:mechanical_press"))
        assertFalse(blockOpen(null, "create:mechanical_press"))
        assertFalse(recipeOpen(country, axe))
        country.level = 3
        Gate.invalidate(country)
        assertTrue(recipeOpen(country, axe))
        assertTrue(blockOpen(country, "minecraft:loom"))
        assertFalse(recipeOpen(null, axe))
    }

    @Test
    fun invalidationAndDisbandDropTheCache() {
        val country = setup()
        val pickaxe = "minecraft:iron_pickaxe"
        country.research.done["t:a"] = 1
        assertTrue(recipeOpen(country, pickaxe))
        country.research.done.clear()
        Gate.invalidate(country)
        assertFalse(recipeOpen(country, pickaxe))
        country.research.done["t:a"] = 1
        Gate.invalidate(country)
        assertTrue(recipeOpen(country, pickaxe))
        assertFalse(recipeOpen(Country("alpha"), pickaxe))
    }

    @Test
    fun whyExplainsSources() {
        val country = setup()
        val why = Gate.why(country, "minecraft:iron_axe")
        assertTrue(why.gated)
        assertEquals(listOf(3), why.levels)
        assertFalse(why.has)
        assertFalse(Gate.why(null, "minecraft:oak_planks").gated)
    }

    @Test
    fun extrasRoundTripAndHiddenSet() {
        setup()
        val decoded = extras(IdExtras.RECIPES)
        assertEquals(listOf("minecraft:iron_axe", "minecraft:iron_pickaxe"), decoded.ids)
        assertEquals(mapOf("t:a" to listOf(1)), decoded.nodes)
        assertEquals(mapOf(3 to listOf(0)), decoded.levels)
        assertEquals(setOf("minecraft:iron_pickaxe", "minecraft:iron_axe"), decoded.hidden(emptyList(), 1))
        assertEquals(setOf("minecraft:iron_axe"), decoded.hidden(listOf("t:a"), 1))
        assertEquals(emptySet(), decoded.hidden(listOf("t:a"), 3))
        assertTrue(IdExtras.decode(emptyMap(), IdExtras.RECIPES).ids.isEmpty())
    }

    @Test
    fun producersMapOutputItemsToLockedRecipes() {
        setup()
        val decoded = extras(IdExtras.RECIPES)
        assertEquals(listOf("minecraft:iron_axe"), decoded.producing("minecraft:iron_axe"))
        assertTrue(decoded.producing("minecraft:oak_planks").isEmpty())
    }

    @Test
    fun blockExtrasRoundTripAndLookup() {
        setup()
        val decoded = extras(IdExtras.BLOCKS)
        assertEquals(listOf("create:mechanical_press", "minecraft:loom"), decoded.ids)
        assertEquals(setOf("create:mechanical_press", "minecraft:loom"), decoded.hidden(emptyList(), 1))
        assertEquals(setOf("minecraft:loom"), decoded.hidden(listOf("t:a"), 1))
        assertEquals(emptySet(), decoded.hidden(listOf("t:a"), 3))
        assertEquals(listOf("t:a"), decoded.unlockedBy("create:mechanical_press"))
        assertEquals(listOf(3), decoded.unlockedByLevels("minecraft:loom"))
        assertEquals(listOf("minecraft:iron_pickaxe", "minecraft:iron_axe").sorted(), extras(IdExtras.RECIPES).ids)
        assertTrue(IdExtras.decode(emptyMap(), IdExtras.BLOCKS).unlockedBy("x").isEmpty())
    }
}
