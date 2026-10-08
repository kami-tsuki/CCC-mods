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

    private fun CraftingRecipe.inputs(): Set<String>? =
        ingredients.filterNot { it.isEmpty }.map { i -> i.items.map(ItemSpec::spec).toSet() }.takeIf { l -> l.isNotEmpty() && l.all { it.isNotEmpty() && it == l[0] } }?.get(0)

    private fun CraftingRecipe.slots() = ingredients.count { !it.isEmpty }

    fun read(server: MinecraftServer) {
        val access = server.registryAccess()
        val compress = HashMap<String, MutableSet<String>>()
        val expand = HashMap<String, MutableSet<String>>()
        server.recipeManager.getAllRecipesFor(RecipeType.CRAFTING).forEach { holder ->
            val r = holder.value()
            if (r !is ShapedRecipe && r !is ShapelessRecipe) return@forEach
            val out = runCatching { r.getResultItem(access) }.getOrNull()?.takeUnless { it.isEmpty } ?: return@forEach
            val input = r.inputs() ?: return@forEach
            val result = ItemSpec.spec(out)
            if (result in input) return@forEach
            when {
                out.count == 1 && r.slots() == 9 && (r !is ShapedRecipe || (r.width == 3 && r.height == 3)) -> compress.getOrPut(result) { HashSet() } += input
                out.count == 9 && r.slots() == 1 -> expand.getOrPut(result) { HashSet() } += input
            }
        }
        pairs = expand.flatMap { (small, bigs) -> bigs.filter { big -> compress[big]?.contains(small) == true }.map { small to it } }
            .filter { Blacklist.sellable(it.first) && Blacklist.sellable(it.second) }.distinct()
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
