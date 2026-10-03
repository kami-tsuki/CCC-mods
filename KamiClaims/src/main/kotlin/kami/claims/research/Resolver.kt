package kami.claims.research

import kami.libs.mc.Selectors

class RecipeFact(val id: String, val type: String, val outputs: List<String> = emptyList(), val inputs: List<String> = emptyList(), val display: List<String> = outputs)

class WorldFacts(
    val recipes: List<RecipeFact>,
    val blocks: List<String>,
    val itemTagged: (tag: String, id: String) -> Boolean = { _, _ -> false },
    val blockTagged: (tag: String, id: String) -> Boolean = { _, _ -> false },
    val modLoaded: (String) -> Boolean = { true }
)

class Matched(val recipes: Set<String> = emptySet(), val blocks: Set<String> = emptySet()) {
    val isEmpty get() = recipes.isEmpty() && blocks.isEmpty()

    operator fun minus(other: Matched) = Matched(recipes - other.recipes, blocks - other.blocks)
    operator fun contains(id: String) = id in recipes || id in blocks
}

fun Iterable<Matched>.merged(): Matched {
    val recipes = HashSet<String>()
    val blocks = HashSet<String>()
    forEach { recipes += it.recipes; blocks += it.blocks }
    return Matched(recipes, blocks)
}

class Resolution(
    val gated: Matched,
    val nodes: Map<String, Matched>,
    val levels: Map<Int, Matched>,
    val outputs: Map<String, List<String>>,
    val problems: List<String>,
    val notes: List<String>
) {
    companion object {
        val EMPTY = Resolution(Matched(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList())
    }
}

class Resolver(private val world: WorldFacts, private val defs: ResearchDefs) {
    private val memo = HashMap<Pair<String, List<String?>>, Matched>()
    private val problems = ArrayList<String>()
    private val notes = ArrayList<String>()

    fun run(): Resolution {
        val baseline = all(defs.settings.baseline.map(::GroupRef), "settings.baseline")
        val selected = defs.nodes.mapValues { (key, node) -> all(node.unlocks, key) }
        selected.forEach { (key, part) ->
            val overlap = (part.recipes intersect baseline.recipes).size + (part.blocks intersect baseline.blocks).size
            if (overlap > 0) problems += "$key: $overlap selected ids are also in the baseline and stay open"
        }
        val nodes = selected.mapValues { it.value - baseline }
        val levels = defs.levels.rewards.mapValues { (level, rewards) -> all(rewards, "level $level") - baseline }
        val locked = all(defs.settings.locked, "settings.locked") - baseline
        val gated = (nodes.values + levels.values + locked).merged()
        val outputs = world.recipes.filter { it.id in gated.recipes && it.display.isNotEmpty() }.associate { it.id to it.display }
        return Resolution(gated, nodes, levels, outputs, problems, notes)
    }

    private fun all(unlocks: List<Unlock>, where: String) = unlocks.map { select(it, where) }.merged()

    private fun select(unlock: Unlock, where: String): Matched = when (unlock) {
        is GroupRef -> all(defs.groups[unlock.id]?.unlocks.orEmpty(), "$where via ${unlock.id}")
        is RecipeUnlock -> cached("recipe", listOf(unlock.id), where) { matchRecipes { Selectors.glob(unlock.id, it.id) } }
        is RecipeTypeUnlock -> cached("recipe_type", listOf(unlock.id), where) { matchRecipes { Selectors.glob(unlock.id, it.type) } }
        is RecipesUnlock -> cached("recipes", listOf(unlock.recipeType, unlock.input, unlock.output), where) {
            matchRecipes { recipe ->
                (unlock.recipeType == null || Selectors.glob(unlock.recipeType, recipe.type)) &&
                    (unlock.output == null || recipe.outputs.any { matches(unlock.output, it, world.itemTagged) }) &&
                    (unlock.input == null || recipe.inputs.any { matches(unlock.input, it, world.itemTagged) })
            }
        }
        is OutputUnlock -> cached("output", listOf(unlock.id), where) { matchRecipes { recipe -> recipe.outputs.any { matches(unlock.id, it, world.itemTagged) } } }
        is ModUnlock -> cached("mod", listOf(unlock.id), where) {
            Matched(
                world.recipes.filter { recipe -> Selectors.namespace(recipe.id) == unlock.id || recipe.outputs.any { Selectors.namespace(it) == unlock.id } }.map { it.id }.toSet(),
                world.blocks.filter { Selectors.namespace(it) == unlock.id }.toSet()
            )
        }
        is BlockUnlock -> cached("block", listOf(unlock.id), where) {
            Matched(blocks = world.blocks.filter { matches(unlock.id, it, world.blockTagged) }.toSet())
        }
        is CapacityUnlock, is MoneyReward, is FeatureUnlock, is TokenUnlock, is BuffUnlock, is BuffPointsUnlock, is LoanUnlock, is LoanSlotsUnlock, is ResearchSpeedUnlock -> Matched()
    }

    private fun matches(spec: String, id: String, tagged: (String, String) -> Boolean) =
        if (spec.startsWith('#')) tagged(spec.drop(1), id) else Selectors.glob(spec, id)

    private fun matchRecipes(test: (RecipeFact) -> Boolean) = Matched(recipes = world.recipes.filter(test).map { it.id }.toSet())

    private fun cached(kind: String, specs: List<String?>, where: String, compute: () -> Matched): Matched =
        memo.getOrPut(kind to specs) {
            compute().also { if (it.isEmpty) report(kind, specs, where) }
        }

    private fun report(kind: String, specs: List<String?>, where: String) {
        val spec = specs.joinToString("|")
        val given = specs.filterNotNull()
        val namespaces = if (kind == "mod") given else given.map(Selectors::namespace)
        val missing = namespaces.firstOrNull { it != "*" && !world.modLoaded(it) }
        if (missing != null) notes += "$where: $kind $spec skipped, mod $missing is not installed"
        else problems += "$where: $kind $spec matches nothing"
    }
}
