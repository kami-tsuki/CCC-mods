package kami.claims.research

import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import kami.libs.compat.Mods
import kami.libs.mc.ItemSpec
import kami.libs.mc.RegistryTags
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeSerializer

object ServerFacts {
    fun read(server: MinecraftServer): WorldFacts {
        val access = server.registryAccess()
        val recipes = server.recipeManager.recipes.map { holder ->
            val result = runCatching { holder.value().getResultItem(access) }.getOrNull()?.takeUnless { it.isEmpty }
            val outputs = result?.let { listOf(ItemSpec.id(it)) }.orEmpty()
            val display = result?.let { listOf(ItemSpec.spec(it)) }.orEmpty()
            val inputs = runCatching { holder.value().ingredients.flatMap { ingredient -> ingredient.items.map { ItemSpec.id(it) } }.distinct() }.getOrDefault(emptyList())
            RecipeFact(holder.id().toString(), BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().type).toString(), outputs, inputs, display, BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().serializer).toString()) { fluids(holder.value(), access) }
        }
        return WorldFacts(
            recipes,
            BuiltInRegistries.BLOCK.keySet().map { it.toString() },
            { tag, id -> RegistryTags.contains(BuiltInRegistries.ITEM, tag, id) },
            { tag, id -> RegistryTags.contains(BuiltInRegistries.BLOCK, tag, id) },
            Mods::loaded
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun fluids(recipe: Recipe<*>, access: RegistryAccess): List<String> = runCatching {
        val codec = (recipe.serializer as RecipeSerializer<Recipe<*>>).codec().codec()
        val json = codec.encodeStart(RegistryOps.create(JsonOps.INSTANCE, access), recipe).getOrThrow().asJsonObject
        val results = json.getAsJsonArray("results")?.toList().orEmpty() + listOfNotNull(json.get("result"))
        results.mapNotNull { it as? JsonObject }
            .filter { it.has("amount") }
            .mapNotNull { it.get("id")?.asString?.let(ResourceLocation::tryParse) }
            .filter { BuiltInRegistries.FLUID.containsKey(it) }
            .map { it.toString() }
            .distinct()
    }.getOrDefault(emptyList())
}
