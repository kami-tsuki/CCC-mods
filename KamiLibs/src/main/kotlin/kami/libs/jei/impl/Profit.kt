package kami.libs.jei.impl

import kami.libs.jei.KamiJei
import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import mezz.jei.api.gui.builder.ITooltipBuilder
import mezz.jei.api.gui.ingredient.IRecipeSlotView
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap

private const val TTL_MS = 1000L
private const val GAIN = 0xFF2E9E2E.toInt()
private const val LOSS = 0xFFC03030.toInt()
private const val FLAT = 0xFF707070.toInt()

class ProfitDecorator : IRecipeCategoryDecorator<Any> {
    private class Quote(val input: Double, val output: Double, val percent: Long)
    private class Cached(val at: Long, val quote: Quote?)

    private val cache = WeakHashMap<Any, Cached>()

    override fun draw(recipe: Any, category: IRecipeCategory<Any>, view: IRecipeSlotsView, g: GuiGraphics, mx: Double, my: Double) {
        val quote = quote(recipe, view) ?: return
        val font = Minecraft.getInstance().font
        val text = ProfitMath.label(quote.percent)
        val color = if (quote.percent > 0) GAIN else if (quote.percent < 0) LOSS else FLAT
        g.drawString(font, text, category.width - font.width(text) - 2, 1, color, false)
    }

    override fun decorateTooltips(builder: ITooltipBuilder, recipe: Any, category: IRecipeCategory<Any>, view: IRecipeSlotsView, mx: Double, my: Double) {
        val quote = quote(recipe, view) ?: return
        val width = Minecraft.getInstance().font.width(ProfitMath.label(quote.percent))
        if (my !in 0.0..10.0 || mx < category.width - width - 3 || mx > category.width) return
        builder.add(Component.literal(tr("kami_libs.jei.profit", Format.rateMoney(quote.input), Format.rateMoney(quote.output))))
    }

    private fun quote(recipe: Any, view: IRecipeSlotsView): Quote? {
        if (KamiJei.priceSources.isEmpty()) return null
        val now = System.currentTimeMillis()
        cache[recipe]?.takeIf { now - it.at < TTL_MS }?.let { return it.quote }
        val fresh = compute(view)
        cache[recipe] = Cached(now, fresh)
        return fresh
    }

    private fun compute(view: IRecipeSlotsView): Quote? {
        val input = ProfitMath.total(view.getSlotViews(RecipeIngredientRole.INPUT).filter { !it.isEmpty }.map(::slotPrice)) ?: return null
        val output = ProfitMath.total(view.getSlotViews(RecipeIngredientRole.OUTPUT).filter { !it.isEmpty }.map(::slotPrice)) ?: return null
        return Quote(input, output, ProfitMath.percent(input, output) ?: return null)
    }

    private fun slotPrice(slot: IRecipeSlotView): Double? =
        ProfitMath.cheapest(slot.itemStacks.map { stack -> unit(stack)?.times(stack.count) }.toList())

    private fun unit(stack: ItemStack): Double? = KamiJei.priceSources.firstNotNullOfOrNull { it(stack) }
}
