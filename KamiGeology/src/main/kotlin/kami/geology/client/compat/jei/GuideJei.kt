package kami.geology.client.compat.jei

import kami.geology.client.Guide
import kami.libs.jei.JeiModule
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.registration.IRecipeCategoryRegistration

class GuideJei : JeiModule {
    override fun categories(reg: IRecipeCategoryRegistration) {
        reg.addRecipeCategories(GuideCategory(reg.jeiHelpers.guiHelper))
    }

    override fun recipes(): Map<RecipeType<*>, List<Any>> = mapOf(GuideCategory.TYPE to Guide.data.ores)

    override fun listen(changed: () -> Unit): () -> Unit = Guide.listen(changed)
}
