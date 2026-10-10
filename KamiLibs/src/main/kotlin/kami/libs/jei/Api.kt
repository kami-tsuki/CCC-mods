package kami.libs.jei

import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import net.minecraft.world.item.ItemStack

data class DropTarget(val x: Int, val y: Int, val w: Int, val h: Int, val accept: (ItemStack) -> Unit)

interface JeiModule {
    fun categories(reg: IRecipeCategoryRegistration)
    fun recipes(): Map<RecipeType<*>, List<Any>>
    fun listen(changed: () -> Unit): () -> Unit
    fun hidden(): Set<String> = emptySet()
    fun catalysts(reg: IRecipeCatalystRegistration) {}
}
