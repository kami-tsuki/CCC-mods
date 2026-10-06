package kami.geology.client.compat.jei

import kami.geology.KamiGeology
import kami.geology.client.Guide
import kami.geology.command.GeoText
import kami.geology.net.GuideOre
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

private const val W = 170
private const val H = 130
private const val SLOT = 18
private const val TEXT_Y = 24
private const val LINE = 10
private const val TEXT = 0xFF404040.toInt()

class GuideCategory(guiHelper: IGuiHelper) : IRecipeCategory<GuideOre> {
    private val icon = guiHelper.createDrawableItemStack(ItemStack(KamiGeology.PROSPECTORS_BY_TIER.first().get()))

    override fun getRecipeType() = TYPE
    override fun getTitle(): Component = Component.translatable("kami_geology.jei.guide")
    override fun getWidth() = W
    override fun getHeight() = H
    override fun getIcon(): IDrawable = icon

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: GuideOre, focuses: IFocusGroup) {
        recipe.blocks.mapNotNull(::stack).forEachIndexed { i, stack -> builder.addOutputSlot(i * SLOT, 5).addItemStack(stack) }
    }

    override fun draw(recipe: GuideOre, recipeSlotsView: IRecipeSlotsView, g: GuiGraphics, mouseX: Double, mouseY: Double) {
        val font = Minecraft.getInstance()?.font ?: return
        val x = recipe.blocks.size * SLOT + 4
        g.drawString(font, GeoText.oreLabel(recipe.id), x, 9, TEXT, false)
        lines(recipe).flatMap { font.split(Component.literal(it), W) }.take((H - TEXT_Y) / LINE)
            .forEachIndexed { i, line -> g.drawString(font, line, 0, TEXT_Y + i * LINE, TEXT, false) }
    }

    private fun stack(id: String) = ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
        ?.let(::ItemStack)?.takeUnless { it.isEmpty }

    private fun blockName(id: String) = stack(id)?.hoverName?.string ?: GeoText.name(id.substringAfter(':'))

    private fun pct(v: Double) = "${(v * 100).roundToInt()}%"

    private fun range(h: List<Int>) = h.takeIf { it.size >= 2 }?.let { tr("kami_geology.jei.y", it[0], it[1]) }

    private fun lines(o: GuideOre): List<String> = listOfNotNull(
        o.shape?.let { shape ->
            val parts = listOfNotNull(trOr("kami_geology.shape.$shape", GeoText.name(shape)), range(o.height), tr("kami_geology.jei.density", pct(o.chance), o.spacing))
            tr("kami_geology.jei.deposits", parts.joinToString(" · "))
        },
        tr("kami_geology.jei.biomes", o.provinces.entries.sortedByDescending { it.value }.joinToString(" · ") { GeoText.provinceLabel(it.key) + " " + pct(it.value) }
            .ifEmpty { tr("kami_geology.jei.everywhere") }),
        o.anchor?.let { tr("kami_geology.jei.anchor", GeoText.oreLabel(it), pct(o.anchorChance)) },
        o.halo.takeIf { it.isNotEmpty() }?.let { tr("kami_geology.jei.signs", it.joinToString(", ", transform = ::blockName)) },
        o.core?.let { tr("kami_geology.jei.core", blockName(it), pct(o.coreChance)) },
        o.scatter.takeIf { it > 0 }?.let { tr("kami_geology.jei.veins", it) + (range(o.scatterHeight)?.let { y -> " · $y" } ?: "") },
        Guide.data.dimensions.takeUnless { it == listOf("minecraft:overworld") }
            ?.let { tr("kami_geology.jei.dimensions", it.joinToString(", ") { d -> GeoText.name(d.substringAfter(':')) }) }
    )

    companion object {
        val TYPE: RecipeType<GuideOre> = RecipeType.create("kami_geology", "guide", GuideOre::class.java)
    }
}
