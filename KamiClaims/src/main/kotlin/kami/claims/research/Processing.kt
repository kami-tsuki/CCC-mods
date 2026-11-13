package kami.claims.research

import kami.claims.Realm
import net.minecraft.core.RegistryAccess
import kami.libs.mc.ItemSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder

object Processing {
    @JvmStatic
    fun burned(holder: RecipeHolder<*>, access: RegistryAccess) {
        if (!RecipeContext.active) return
        done(holder.value(), holder.value().getResultItem(access))
    }

    @JvmStatic
    fun rolled(recipe: Recipe<*>, results: List<ItemStack>) {
        if (!RecipeContext.active) return
        done(recipe, results.firstOrNull { !it.isEmpty } ?: return)
    }

    private val lossless = setOf("create:filling", "create:emptying")

    fun credits(type: String, input: String?, output: String) = type !in lossless && input != output

    private fun done(recipe: Recipe<*>, result: ItemStack) {
        val country = Realm.country(RecipeContext.countryId()) ?: return
        val type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.type)?.toString() ?: return
        val output = ItemSpec.id(result)
        val input = recipe.ingredients.firstOrNull()?.items?.singleOrNull()?.let(ItemSpec::id)
        if (credits(type, input, output)) Progress.processed(country, type, output)
    }
}
