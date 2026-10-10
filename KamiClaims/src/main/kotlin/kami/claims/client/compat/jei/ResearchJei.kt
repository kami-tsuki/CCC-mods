package kami.claims.client.compat.jei

import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.claims.net.NodeView
import kami.libs.jei.JeiModule
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.registration.IRecipeCategoryRegistration

object ResearchJei : JeiModule {
    override fun categories(reg: IRecipeCategoryRegistration) {
        reg.addRecipeCategories(ResearchCategory(reg.jeiHelpers.guiHelper))
    }

    override fun recipes(): Map<RecipeType<*>, List<Any>> = mapOf(ResearchCategory.TYPE to open())

    override fun listen(changed: () -> Unit): () -> Unit = ClientResearch.listen(changed)

    override fun hidden(): Set<String> = ClientResearch.hiddenRecipes()

    private fun open(): List<NodeView> = ClientResearch.trees.ifEmpty { ClientResearch.defs.trees }
        .flatMap { it.nodes }
        .filter { ClientResearch.status(it.key) != NodeStatus.DONE }
        .sortedWith(compareBy({ ClientResearch.status(it.key) }, { it.level }, { it.timeMs }))
}
