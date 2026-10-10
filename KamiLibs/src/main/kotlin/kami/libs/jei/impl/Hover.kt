package kami.libs.jei.impl

import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.runtime.IJeiRuntime
import mezz.jei.api.runtime.IRecipesGui
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack

object Hover {
    @Volatile
    var runtime: IJeiRuntime? = null

    fun typing(): Boolean = runtime?.ingredientListOverlay?.hasKeyboardFocus() == true

    fun item(): ItemStack? {
        val rt = runtime ?: return null
        val list = rt.ingredientListOverlay
        if (list.hasKeyboardFocus()) return null
        val found = list.getIngredientUnderMouse(VanillaTypes.ITEM_STACK)
            ?: rt.bookmarkOverlay.getIngredientUnderMouse(VanillaTypes.ITEM_STACK)
            ?: if (Minecraft.getInstance().screen is IRecipesGui) rt.recipesGui.getIngredientUnderMouse(VanillaTypes.ITEM_STACK).orElse(null) else null
        return found?.takeIf { !it.isEmpty }
    }
}
