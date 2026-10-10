package kami.economy.client.compat.jei

import com.mojang.blaze3d.platform.InputConstants
import kami.economy.client.ui.MarketApp
import kami.economy.client.ui.signedPercent
import kami.economy.net.InfoQuest
import kami.economy.net.InfoVendor
import kami.libs.mc.ItemSpec
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import kami.libs.xaero.MapPoint
import kami.libs.xaero.XaeroMaps
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.builder.ITooltipBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.gui.inputs.IJeiInputHandler
import mezz.jei.api.gui.inputs.IJeiUserInput
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

private const val W = 160
private const val ROW = 10
private const val PRICE_Y = 21
private const val SPARK_Y = 54
private const val SPARK_H = 16
private const val VENDORS_Y = 76
private const val VENDOR_ROWS = 3
private const val QUEST_ROWS = 2
private const val SLOT_W = 22
private const val WAYPOINT_COLOR = 0x55FF55

private const val TEXT = 0xFF404040.toInt()
private const val MUTED = 0xFF707070.toInt()
private const val GOOD = 0xFF2E7D32.toInt()
private const val BAD = 0xFFA82020.toInt()
private const val LINK = 0xFF1F5FA8.toInt()

data class MarketEntry(val item: String)

private fun openMarket(item: String): () -> Unit = {
    MarketApp.instance.openItem(item)
    MarketApp.open()
}

private fun waypoint(v: InfoVendor): (() -> Unit)? {
    if (!XaeroMaps.available) return null
    return {
        val name = JeiStore.stack(v.item).hoverName.string
        XaeroMaps.tempWaypoint(MapPoint("jei:vendor:${v.dim}:${v.x}:${v.y}:${v.z}", v.dim, v.x, v.y, v.z, name, name.take(2).uppercase(), WAYPOINT_COLOR))
    }
}

private fun vendorText(v: InfoVendor): String =
    tr(if (v.sell) "kami_economy.jei.vendor.sell" else "kami_economy.jei.vendor.buy", Format.number(v.count), Format.money(v.price.toLong()), v.x, v.y, v.z)

private fun questText(q: InfoQuest): String =
    tr("kami_economy.jei.quest", Format.money(q.lotPrice.toLong()), Format.number(q.lot), Format.duration(q.expiresAt - System.currentTimeMillis()))

private fun boostText(q: InfoQuest): String = q.boosts.joinToString(" · ") { tr("kami_economy.quest.ui.boost", tr("kami_economy.quest.kind.${it.kind}"), it.pct, it.hours) }

private class Click(private val area: ScreenRectangle, private val act: () -> (() -> Unit)?) : IJeiInputHandler {
    override fun getArea() = area

    override fun handleInput(mouseX: Double, mouseY: Double, input: IJeiUserInput): Boolean {
        if (input.key.type != InputConstants.Type.MOUSE || input.key.value != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        val run = act() ?: return false
        if (!input.isSimulate) run()
        return true
    }
}

abstract class EcoCategory<T : Any>(helper: IGuiHelper, private val type: RecipeType<T>, private val key: String, iconItem: ItemStack, private val w: Int, private val h: Int) : IRecipeCategory<T> {
    private val icon = helper.createDrawableItemStack(iconItem)
    protected val font get() = Minecraft.getInstance().font

    override fun getRecipeType() = type
    override fun getTitle(): Component = Component.translatable("kami_economy.jei.$key")
    override fun getWidth() = w
    override fun getHeight() = h
    override fun getIcon(): IDrawable = icon
}

class MarketCategory(helper: IGuiHelper) : EcoCategory<MarketEntry>(helper, TYPE, "market", ItemStack(Items.EMERALD), W, VENDORS_Y + (VENDOR_ROWS + QUEST_ROWS) * ROW + 4) {
    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: MarketEntry, focuses: IFocusGroup) {
        val stack = JeiStore.stack(recipe.item)
        builder.addInputSlot(1, 1).addItemStack(stack)
        builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT).addItemStack(stack)
    }

    override fun createRecipeExtras(builder: IRecipeExtrasBuilder, recipe: MarketEntry, focuses: IFocusGroup) {
        repeat(VENDOR_ROWS) { i ->
            builder.addInputHandler(Click(ScreenRectangle(0, VENDORS_Y + i * ROW, W, ROW)) { JeiStore.info(recipe.item)?.vendors?.getOrNull(i)?.let(::waypoint) })
        }
        builder.addInputHandler(Click(ScreenRectangle(0, 20, W, height - 20)) { openMarket(recipe.item) })
    }

    override fun draw(recipe: MarketEntry, view: IRecipeSlotsView, g: GuiGraphics, mouseX: Double, mouseY: Double) {
        g.drawString(font, Draw.fit(JeiStore.stack(recipe.item).hoverName.string, W - SLOT_W), SLOT_W, 1, TEXT, false)
        val info = JeiStore.info(recipe.item)
        if (info == null) {
            g.drawString(font, tr("kami_economy.jei.loading"), SLOT_W, PRICE_Y, MUTED, false)
            return
        }
        row(g, "kami_economy.admin.side.buy", Format.unitMoney(info.ask.toLong(), info.lot), 0, TEXT)
        row(g, "kami_economy.admin.side.sell", Format.unitMoney(info.bid.toLong(), info.lot), 1, TEXT)
        row(g, "kami_economy.admin.col.course", signedPercent(info.change / 10.0), 2, if (info.change >= 0) GOOD else BAD)
        spark(g, info.closes)
        var y = VENDORS_Y
        info.vendors.forEach {
            g.drawString(font, Draw.fit(vendorText(it), W), 0, y, if (XaeroMaps.available) LINK else TEXT, false)
            y += ROW
        }
        y = VENDORS_Y + VENDOR_ROWS * ROW + 2
        info.quests.take(QUEST_ROWS).forEach {
            g.drawString(font, Draw.fit(questText(it), W), 0, y, GOOD, false)
            y += ROW
        }
    }

    override fun getTooltip(tooltip: ITooltipBuilder, recipe: MarketEntry, view: IRecipeSlotsView, mouseX: Double, mouseY: Double) {
        val row = (mouseY.toInt() - VENDORS_Y) / ROW
        if (mouseY >= VENDORS_Y && row < VENDOR_ROWS && XaeroMaps.available && JeiStore.info(recipe.item)?.vendors?.getOrNull(row) != null) tooltip.add(Component.translatable("kami_economy.jei.waypoint.tip").withColor(MUTED))
        else if (mouseY >= 20) tooltip.add(Component.translatable("kami_economy.jei.open.tip").withColor(MUTED))
    }

    private fun row(g: GuiGraphics, key: String, value: String, i: Int, color: Int) {
        val y = PRICE_Y + i * ROW
        g.drawString(font, tr(key), 0, y, MUTED, false)
        g.drawString(font, value, W - font.width(value), y, color, false)
    }

    private fun spark(g: GuiGraphics, closes: List<Int>) {
        if (closes.size < 2) {
            g.drawString(font, tr("kami_economy.jei.nohistory"), 0, SPARK_Y + 5, MUTED, false)
            return
        }
        val lo = closes.min()
        val span = (closes.max() - lo).coerceAtLeast(1)
        val color = if (closes.last() >= closes.first()) GOOD else BAD
        closes.mapIndexed { i, v -> i * (W - 1) / (closes.size - 1) to SPARK_Y + SPARK_H - (v - lo) * SPARK_H / span }
            .zipWithNext().forEach { (a, b) -> Draw.line(g, a.first, a.second, b.first, b.second, color) }
    }

    companion object {
        val TYPE: RecipeType<MarketEntry> = RecipeType.create("kami_economy", "market", MarketEntry::class.java)
    }
}

class WantedCategory(helper: IGuiHelper) : EcoCategory<InfoQuest>(helper, TYPE, "wanted", ItemStack(Items.WRITABLE_BOOK), W, 34) {
    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: InfoQuest, focuses: IFocusGroup) {
        builder.addInputSlot(1, 8).addItemStack(JeiStore.stack(recipe.item))
    }

    override fun createRecipeExtras(builder: IRecipeExtrasBuilder, recipe: InfoQuest, focuses: IFocusGroup) {
        builder.addInputHandler(Click(ScreenRectangle(SLOT_W, 0, W - SLOT_W, height)) { openMarket(recipe.item) })
    }

    override fun draw(recipe: InfoQuest, view: IRecipeSlotsView, g: GuiGraphics, mouseX: Double, mouseY: Double) {
        g.drawString(font, Draw.fit(JeiStore.stack(recipe.item).hoverName.string, W - SLOT_W), SLOT_W, 1, TEXT, false)
        g.drawString(font, Draw.fit(questText(recipe), W - SLOT_W), SLOT_W, 12, GOOD, false)
        g.drawString(font, Draw.fit(boostText(recipe), W - SLOT_W), SLOT_W, 23, MUTED, false)
    }

    override fun getTooltip(tooltip: ITooltipBuilder, recipe: InfoQuest, view: IRecipeSlotsView, mouseX: Double, mouseY: Double) {
        tooltip.add(Component.translatable("kami_economy.jei.open.tip").withColor(MUTED))
    }

    companion object {
        val TYPE: RecipeType<InfoQuest> = RecipeType.create("kami_economy", "wanted", InfoQuest::class.java)
    }
}

class VendorCategory(helper: IGuiHelper) : EcoCategory<InfoVendor>(helper, TYPE, "vendors", ItemSpec.stack("numismatics:vendor").takeUnless { it.isEmpty } ?: ItemStack(Items.BARREL), W, 34) {
    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: InfoVendor, focuses: IFocusGroup) {
        val slot = if (recipe.sell) builder.addOutputSlot(1, 8) else builder.addInputSlot(1, 8)
        slot.addItemStack(JeiStore.stack(recipe.item))
    }

    override fun createRecipeExtras(builder: IRecipeExtrasBuilder, recipe: InfoVendor, focuses: IFocusGroup) {
        builder.addInputHandler(Click(ScreenRectangle(SLOT_W, 0, W - SLOT_W, height)) { waypoint(recipe) ?: openMarket(recipe.item) })
    }

    override fun draw(recipe: InfoVendor, view: IRecipeSlotsView, g: GuiGraphics, mouseX: Double, mouseY: Double) {
        g.drawString(font, Draw.fit(JeiStore.stack(recipe.item).hoverName.string, W - SLOT_W), SLOT_W, 1, TEXT, false)
        g.drawString(font, Draw.fit(vendorText(recipe), W - SLOT_W), SLOT_W, 12, if (XaeroMaps.available) LINK else TEXT, false)
        g.drawString(font, Draw.fit(tr("kami_economy.jei.vendor.each", Format.unitMoney(recipe.price.toLong(), recipe.count)), W - SLOT_W), SLOT_W, 23, MUTED, false)
    }

    override fun getTooltip(tooltip: ITooltipBuilder, recipe: InfoVendor, view: IRecipeSlotsView, mouseX: Double, mouseY: Double) {
        tooltip.add(Component.translatable(if (XaeroMaps.available) "kami_economy.jei.waypoint.tip" else "kami_economy.jei.open.tip").withColor(MUTED))
    }

    companion object {
        val TYPE: RecipeType<InfoVendor> = RecipeType.create("kami_economy", "vendors", InfoVendor::class.java)
    }
}
