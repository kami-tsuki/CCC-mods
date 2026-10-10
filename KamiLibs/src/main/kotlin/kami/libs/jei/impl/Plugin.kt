package kami.libs.jei.impl

import kami.libs.KamiLibs
import kami.libs.jei.KamiJei
import kami.libs.ui.app.AppScreen
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.registration.IAdvancedRegistration
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.resources.ResourceLocation

@JeiPlugin
class KamiJeiPlugin : IModPlugin {
    override fun getPluginUid(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, "main")

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        KamiJei.modules.forEach { it.categories(registration) }
    }

    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        KamiJei.modules.forEach { it.catalysts(registration) }
    }

    override fun registerGuiHandlers(registration: IGuiHandlerRegistration) {
        registration.addGuiScreenHandler(AppScreen::class.java, AppScreenHandler)
        registration.addGlobalGuiHandler(AppExclusions)
        registration.addGhostIngredientHandler(AppScreen::class.java, AppGhost)
    }

    @Suppress("UNCHECKED_CAST")
    override fun registerAdvanced(registration: IAdvancedRegistration) {
        val decorator = ProfitDecorator()
        registration.jeiHelpers.allRecipeTypes.forEach { registration.addRecipeCategoryDecorator(it as RecipeType<Any>, decorator) }
    }

    override fun onRuntimeAvailable(jeiRuntime: IJeiRuntime) {
        Hover.runtime = jeiRuntime
        Sync.start(jeiRuntime)
    }

    override fun onRuntimeUnavailable() {
        Sync.stop()
        Hover.runtime = null
    }
}
