package kami.claims.research

import kami.libs.mc.ItemSpec
import kami.libs.mc.RegistryTags
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.neoforged.fml.ModList

object ServerFacts {
    fun read(server: MinecraftServer): WorldFacts {
        val access = server.registryAccess()
        val recipes = server.recipeManager.recipes.map { holder ->
            val result = runCatching { holder.value().getResultItem(access) }.getOrNull()?.takeUnless { it.isEmpty }
            val outputs = result?.let { listOf(BuiltInRegistries.ITEM.getKey(it.item).toString()) }.orEmpty()
            val display = result?.let { listOf(ItemSpec.spec(it)) }.orEmpty()
            val inputs = runCatching { holder.value().ingredients.flatMap { ingredient -> ingredient.items.map { BuiltInRegistries.ITEM.getKey(it.item).toString() } }.distinct() }.getOrDefault(emptyList())
            RecipeFact(holder.id().toString(), BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().type).toString(), outputs, inputs, display)
        }
        return WorldFacts(
            recipes,
            BuiltInRegistries.BLOCK.keySet().map { it.toString() },
            { tag, id -> RegistryTags.contains(BuiltInRegistries.ITEM, tag, id) },
            { tag, id -> RegistryTags.contains(BuiltInRegistries.BLOCK, tag, id) },
            { ModList.get().isLoaded(it) }
        )
    }
}
