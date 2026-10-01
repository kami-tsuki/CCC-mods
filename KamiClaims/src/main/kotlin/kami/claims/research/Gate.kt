package kami.claims.research

import kami.claims.Country
import net.minecraft.resources.ResourceLocation

class IdSet(ids: Set<String>) {
    private val byNamespace = ids.groupBy({ it.substringBefore(':', "minecraft") }, { it.substringAfter(':') }).mapValues { it.value.toHashSet() }

    fun contains(namespace: String, path: String) = byNamespace[namespace]?.contains(path) == true

    operator fun contains(id: String) = contains(id.substringBefore(':', "minecraft"), id.substringAfter(':'))

    companion object {
        val EMPTY = IdSet(emptySet())
    }
}

class Unlocked(val recipes: IdSet, val blocks: IdSet)

class Why(val gated: Boolean, val nodes: List<String>, val levels: List<Int>, val has: Boolean)

object Gate {
    private class Entry(val country: Country, val unlocked: Unlocked)

    private val cache = HashMap<String, Entry>()
    private var gatedRecipes = IdSet.EMPTY
    private var gatedBlocks = IdSet.EMPTY

    var resolution = Resolution.EMPTY
        private set

    fun install(next: Resolution) {
        resolution = next
        gatedRecipes = IdSet(next.gated.recipes)
        gatedBlocks = IdSet(next.gated.blocks)
        cache.clear()
    }

    fun invalidate(country: Country) {
        cache.remove(country.id)
    }

    fun gated(recipe: ResourceLocation) = gatedRecipes.contains(recipe.namespace, recipe.path)

    fun gatedBlock(block: ResourceLocation) = gatedBlocks.contains(block.namespace, block.path)

    fun recipe(country: Country?, recipe: ResourceLocation) = recipe(country, recipe.namespace, recipe.path)

    fun recipe(country: Country?, namespace: String, path: String) =
        !gatedRecipes.contains(namespace, path) || (country != null && unlocked(country).recipes.contains(namespace, path))

    fun block(country: Country?, block: ResourceLocation) = block(country, block.namespace, block.path)

    fun block(country: Country?, namespace: String, path: String) =
        !gatedBlocks.contains(namespace, path) || (country != null && unlocked(country).blocks.contains(namespace, path))

    fun why(country: Country?, id: String): Why {
        val gated = id in resolution.gated
        val has = !gated || (country != null && id in unlockedMatched(country))
        return Why(gated, resolution.nodes.filterValues { id in it }.keys.toList(), resolution.levels.filterValues { id in it }.keys.sorted(), has)
    }

    private fun unlocked(country: Country): Unlocked {
        val cached = cache[country.id]
        if (cached != null && cached.country === country) return cached.unlocked
        val matched = unlockedMatched(country)
        return Unlocked(IdSet(matched.recipes), IdSet(matched.blocks)).also { cache[country.id] = Entry(country, it) }
    }

    private fun unlockedMatched(country: Country): Matched {
        val level = Research.level(country)
        val parts = country.research.done.keys.mapNotNull { resolution.nodes[it] } + resolution.levels.filterKeys { it <= level }.values
        return parts.merged()
    }
}
