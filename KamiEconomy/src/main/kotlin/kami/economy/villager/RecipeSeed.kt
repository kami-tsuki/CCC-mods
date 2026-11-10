package kami.economy.villager

import kami.economy.quest.DishGraph
import kami.economy.quest.Dishes

object RecipeSeed {
    private val memo = HashMap<String, Double?>()

    fun reset() = memo.clear()

    fun of(id: String): Double? {
        if (id in memo) return memo[id]
        val dish = runCatching { DishGraph.solve(Dishes.recipes, listOf(id), VillagerCourses::marketUnit, { 0.0 }, { false })[id] }.getOrNull()
        return dish?.takeUnless { it.guessed || it.price <= 0 || it.inputs.isEmpty() }?.price.also { memo[id] = it }
    }
}
