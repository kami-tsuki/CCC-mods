package kami.claims.research

import kami.claims.Realm
import kami.claims.social.Perms
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.libs.text.Phrase
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.level.block.Block
import java.lang.reflect.Method
import java.util.Optional

object RecipeFilter {
    @JvmStatic
    fun locked(holder: RecipeHolder<*>) = !RecipeContext.allowed(holder.id)

    @JvmStatic
    fun <H : RecipeHolder<*>> holders(list: List<H>): List<H> {
        if (list.isEmpty() || !RecipeContext.active) return list
        var kept: ArrayList<H>? = null
        for (i in list.indices) {
            val holder = list[i]
            if (RecipeContext.allowed(holder.id)) kept?.add(holder)
            else if (kept == null) kept = ArrayList<H>(list.size).also { it.addAll(list.subList(0, i)) }
        }
        return kept ?: list
    }

    @JvmStatic
    fun <H : RecipeHolder<*>> optional(found: Optional<H>): Optional<H> =
        if (found.isPresent && locked(found.get())) Optional.empty() else found

    @JvmStatic
    fun blockRecipes(recipes: List<Any>): List<Any> {
        if (recipes.isEmpty() || !RecipeContext.active) return recipes
        val kept = recipes.filter { RecipeContext.allowedBlock(resultBlock(it) ?: return@filter true) }
        return if (kept.size == recipes.size) recipes else kept
    }

    private val resultBlockGetters = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>) = runCatching { type.getMethod("getResultBlock") }.getOrNull()
    }

    private fun resultBlock(recipe: Any): ResourceLocation? = runCatching {
        (resultBlockGetters.get(recipe.javaClass)?.invoke(recipe) as? Block)?.let(BuiltInRegistries.BLOCK::getKey)
    }.getOrNull()

    @JvmStatic
    fun bypassed(player: ServerPlayer) = Perms.has(player, Perms.RESEARCH_BYPASS)

    @JvmStatic
    fun allowedFor(player: ServerPlayer, recipe: ResourceLocation): Boolean {
        if (!Gate.gated(recipe) || bypassed(player)) return true
        return Gate.recipe(Realm.of(player.stringUUID), recipe)
    }

    @JvmStatic
    fun denyCraft(player: ServerPlayer, recipe: ResourceLocation): Boolean {
        if (allowedFor(player, recipe)) return false
        player.bar(Chat.bar(Tone.BAD, Phrase.of("kami_claims.research.locked.block", recipe.toString()).component()))
        return true
    }
}
