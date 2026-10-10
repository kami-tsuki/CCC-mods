package kami.libs.jei.impl

import kami.libs.jei.KamiJei
import kami.libs.ui.app.AppScreen
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.gui.handlers.IGhostIngredientHandler
import mezz.jei.api.gui.handlers.IGlobalGuiHandler
import mezz.jei.api.gui.handlers.IGuiProperties
import mezz.jei.api.gui.handlers.IScreenHandler
import mezz.jei.api.ingredients.ITypedIngredient
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.Rect2i

object AppScreenHandler : IScreenHandler<AppScreen> {
    override fun apply(screen: AppScreen): IGuiProperties? {
        val w = screen.app.window
        if (w.isEmpty || screen.width <= 1 || screen.height <= 1) return null
        return object : IGuiProperties {
            override fun screenClass(): Class<out Screen> = screen.javaClass
            override fun guiLeft() = w.x
            override fun guiTop() = w.y
            override fun guiXSize() = w.w
            override fun guiYSize() = w.h
            override fun screenWidth() = screen.width
            override fun screenHeight() = screen.height
        }
    }
}

object AppExclusions : IGlobalGuiHandler {
    override fun getGuiExtraAreas(): Collection<Rect2i> {
        val w = (Minecraft.getInstance().screen as? AppScreen)?.app?.window?.takeIf { !it.isEmpty } ?: return emptyList()
        return listOf(Rect2i(w.x, w.y, w.w, w.h))
    }
}

object AppGhost : IGhostIngredientHandler<AppScreen> {
    override fun <I> getTargetsTyped(screen: AppScreen, ingredient: ITypedIngredient<I>, doStart: Boolean): List<IGhostIngredientHandler.Target<I>> {
        val stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK).orElse(null) ?: return emptyList()
        return KamiJei.dropSources.flatMap { it() }.map { drop ->
            object : IGhostIngredientHandler.Target<I> {
                override fun getArea() = Rect2i(drop.x, drop.y, drop.w, drop.h)
                override fun accept(value: I) = drop.accept(stack.copy())
            }
        }
    }

    override fun onComplete() {}
}
