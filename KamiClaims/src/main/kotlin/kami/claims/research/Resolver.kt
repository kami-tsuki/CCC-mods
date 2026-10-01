package kami.claims.research

import kami.libs.mc.Selectors

class RecipeFact(val id: String, val type: String, val outputs: List<String> = emptyList(), val inputs: List<String> = emptyList())

class WorldFacts(
    val recipes: List<RecipeFact>,
    val blocks: List<String>,
    val itemTagged: (tag: String, id: String) -> Boolean = { _, _ -> false },
    val blockTagged: (tag: String, id: String) -> Boolean = { _, _ -> false },
    val modLoaded: (String) -> Boolean = { true }
)

class Matched(val recipes: Set<String> = emptySet(), val blocks: Set<String> = emptySet()) {
    val isEmpty get() = recipes.isEmpty() && blocks.isEmpty()

    operator fun plus(other: Matched) = Matched(recipes + other.recipes, blocks + other.blocks)
    operator fun minus(other: Matched) = Matched(recipes - other.recipes, blocks - other.blocks)
    operator fun contains(id: String) = id in recipes || id in blocks
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
    private val memo = HashMap<String, Matched>()
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
        val gated = (nodes.values + levels.values + locked).fold(Matched()) { sum, part -> sum + part }
        val outputs = world.recipes.filter { it.id in gated.recipes && it.outputs.isNotEmpty() }.associate { it.id to it.outputs }
        return Resolution(gated, nodes, levels, outputs, problems, notes)
    }

    private fun all(unlocks: List<Unlock>, where: String) = unlocks.fold(Matched()) { sum, unlock -> sum + select(unlock, where) }

    private fun select(unlock: Unlock, where: String): Matched = when (unlock) {
        is GroupRef -> all(defs.groups[unlock.id]?.unlocks.orEmpty().filter { it !is GroupRef }, "$where via ${unlock.id}")
        is RecipeUnlock -> cached("recipe", unlock.id, where) { matchRecipes { Selectors.glob(unlock.id, it.id) } }
        is RecipeTypeUnlock -> cached("recipe_type", unlock.id, where) { matchRecipes { Selectors.glob(unlock.id, it.type) } }
        is RecipesUnlock -> cached("recipes", "${unlock.recipeType}|${unlock.input}|${unlock.output}", where) {
            matchRecipes { recipe ->
                (unlock.recipeType == null || Selectors.glob(unlock.recipeType, recipe.type)) &&
                    (unlock.output == null || recipe.outputs.any { outputMatches(unlock.output, it) }) &&
                    (unlock.input == null || recipe.inputs.any { outputMatches(unlock.input, it) })
            }
        }
        is OutputUnlock -> cached("output", unlock.id, where) { matchRecipes { recipe -> recipe.outputs.any { outputMatches(unlock.id, it) } } }
        is ModUnlock -> cached("mod", unlock.id, where) {
            Matched(
                world.recipes.filter { recipe -> Selectors.namespace(recipe.id) == unlock.id || recipe.outputs.any { Selectors.namespace(it) == unlock.id } }.map { it.id }.toSet(),
                world.blocks.filter { Selectors.namespace(it) == unlock.id }.toSet()
            )
        }
        is BlockUnlock -> cached("block", unlock.id, where) {
            Matched(blocks = world.blocks.filter { blockMatches(unlock.id, it) }.toSet())
        }
        is CapacityUnlock, is MoneyReward, is FeatureUnlock, is TokenUnlock, is BuffUnlock, is BuffPointsUnlock, is LoanUnlock, is LoanSlotsUnlock -> Matched()
    }

    private fun outputMatches(spec: String, item: String) =
        if (spec.startsWith('#')) world.itemTagged(spec.drop(1), item) else Selectors.glob(spec, item)

    private fun blockMatches(spec: String, block: String) =
        if (spec.startsWith('#')) world.blockTagged(spec.drop(1), block) else Selectors.glob(spec, block)

    private fun matchRecipes(test: (RecipeFact) -> Boolean) = Matched(recipes = world.recipes.filter(test).map { it.id }.toSet())

    private fun cached(kind: String, spec: String, where: String, compute: () -> Matched): Matched =
        memo.getOrPut("$kind:$spec") {
            compute().also { if (it.isEmpty) report(kind, spec, where) }
        }

    private fun report(kind: String, spec: String, where: String) {
        val namespaces = when (kind) {
            "mod" -> listOf(spec)
            "recipes" -> spec.split('|').filter { it != "null" }.map(Selectors::namespace)
            else -> listOf(Selectors.namespace(spec))
        }
        val missing = namespaces.firstOrNull { it != "*" && !world.modLoaded(it) }
        if (missing != null) notes += "$where: $kind $spec skipped, mod $missing is not installed"
        else problems += "$where: $kind $spec matches nothing"
    }
}
