package kami.economy.client.compat.jei

import kami.economy.client.PriceCache
import kami.libs.jei.JeiModule
import mezz.jei.api.recipe.RecipeType
import kami.libs.mc.ItemSpec
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration

object EconomyJei : JeiModule {
    override fun categories(reg: IRecipeCategoryRegistration) {
        val helper = reg.jeiHelpers.guiHelper
        reg.addRecipeCategories(MarketCategory(helper), WantedCategory(helper), VendorCategory(helper))
    }

    override fun catalysts(reg: IRecipeCatalystRegistration) {
        ItemSpec.stack("numismatics:vendor").takeUnless { it.isEmpty }?.let { reg.addRecipeCatalyst(it, VendorCategory.TYPE) }
    }

    override fun recipes(): Map<RecipeType<*>, List<Any>> = mapOf(
        MarketCategory.TYPE to PriceCache.items.filter { !JeiStore.stack(it).isEmpty }.sorted().map(::MarketEntry),
        WantedCategory.TYPE to JeiStore.quests.filter { !JeiStore.stack(it.item).isEmpty },
        VendorCategory.TYPE to JeiStore.vendors.filter { !JeiStore.stack(it.item).isEmpty }
    )

    override fun listen(changed: () -> Unit): () -> Unit = JeiStore.listen(changed)
}
