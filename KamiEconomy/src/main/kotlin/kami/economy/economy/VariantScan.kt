package kami.economy.economy

import kami.economy.Market
import kami.economy.net.Net
import kami.libs.mc.ItemSpec
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe

object VariantScan {
    private var pairs: List<Pair<String, String>> = emptyList()

    private fun CraftingRecipe.single(): String? =
        ingredients.map { i -> i.items.map(ItemSpec::spec).distinct() }.takeIf { l -> l.isNotEmpty() && l.all { it.size == 1 } }?.map { it[0] }?.distinct()?.singleOrNull()

    fun read(server: MinecraftServer) {
        val access = server.registryAccess()
        val compress = HashSet<Pair<String, String>>()
        val expand = HashSet<Pair<String, String>>()
        server.recipeManager.getAllRecipesFor(RecipeType.CRAFTING).forEach { holder ->
            val r = holder.value()
            if (r !is ShapedRecipe && r !is ShapelessRecipe) return@forEach
            val out = runCatching { r.getResultItem(access) }.getOrNull()?.takeUnless { it.isEmpty } ?: return@forEach
            val input = r.single() ?: return@forEach
            val result = ItemSpec.spec(out)
            when {
                input == result -> Unit
                out.count == 1 && r.ingredients.size == 9 && (r !is ShapedRecipe || (r.width == 3 && r.height == 3)) -> compress += input to result
                out.count == 9 && r.ingredients.size == 1 -> expand += result to input
            }
        }
        pairs = compress.filter { it in expand && Blacklist.sellable(it.first) && Blacklist.sellable(it.second) }
        apply()
    }

    fun apply() {
        val starters = Market.starterIds()
        val next = Variants.derive(pairs) { it in starters }
        if (next == Variants.map) return
        Variants.map = next
        Notify.server?.let(Net::broadcastVariants)
    }
}
