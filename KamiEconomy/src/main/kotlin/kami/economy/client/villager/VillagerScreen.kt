package kami.economy.client.villager

import com.mojang.blaze3d.systems.RenderSystem
import dev.ithundxr.createnumismatics.content.backend.Coin
import kami.economy.villager.Line
import kami.economy.villager.LineView
import kami.economy.villager.Side
import kami.economy.villager.Specs
import kami.economy.villager.VillagerMath
import kami.economy.villager.VillagerMenu
import kami.libs.mc.ItemSpec
import kami.libs.ui.style.Format
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.npc.VillagerData
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.MerchantOffer

class VillagerScreen(menu: VillagerMenu, inv: Inventory, title: Component) : AbstractContainerScreen<VillagerMenu>(menu, inv, title) {
    private var rows = emptyList<Button>()
    private var scroll = 0
    private var dragging = false

    init {
        imageWidth = 276
    }

    override fun init() {
        super.init()
        rows = (0 until ROWS).map { k -> addRenderableWidget(Button.builder(Component.empty()) { pick(k + scroll) }.bounds(leftPos + 5, topPos + 18 + k * 20, 88, 20).build().also { it.visible = false }) }
    }

    private fun pick(index: Int) {
        if (index !in menu.offers.indices) return
        menu.selected = index
        minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, index)
    }

    override fun renderLabels(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val level = menu.level
        val name = if (level in 1..5 && menu.progress) Component.translatable("merchant.title", title, Component.translatable("merchant.level.$level")) else title
        g.drawString(font, name, 49 + imageWidth / 2 - font.width(name) / 2, 6, LABEL, false)
        val trades = Component.translatable("merchant.trades")
        g.drawString(font, trades, 53 - font.width(trades) / 2, 6, LABEL, false)
        val card = menu.card
        val text = if (card == null) Component.translatable("kami_economy.villager.card.none").withStyle(ChatFormatting.DARK_RED)
            else Component.literal("${card.label} · ${Format.money(card.balance)}").withStyle(if (affordable()) ChatFormatting.DARK_GRAY else ChatFormatting.DARK_RED)
        g.drawString(font, text, CARD_X + 18, CARD_Y + 4, LABEL, false)
    }

    private fun line(i: Int = menu.selected): LineView? = menu.lines.getOrNull(i)

    private fun affordable() = line()?.let { !it.sell || it.price < 0 || (menu.card?.balance ?: 0) >= it.price } ?: true

    override fun renderBg(g: GuiGraphics, partial: Float, mouseX: Int, mouseY: Int) {
        g.blit(TEXTURE, leftPos, topPos, 0, 0f, 0f, imageWidth, imageHeight, 512, 256)
        val o = menu.offers.getOrNull(menu.selected) ?: return
        if (o.isOutOfStock) g.blitSprite(OUT_OF_STOCK, leftPos + 182, topPos + 35, 0, 28, 21)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partial: Float) {
        super.render(g, mouseX, mouseY, partial)
        val offers = menu.offers
        rows.forEachIndexed { k, b -> b.visible = k < offers.size }
        if (offers.isEmpty()) return renderTooltip(g, mouseX, mouseY)
        scroll = scroll.coerceIn(0, maxOf(0, offers.size - ROWS))
        scroller(g, offers.size)
        for (k in 0 until minOf(ROWS, offers.size - scroll)) row(g, scroll + k, leftPos + 5, topPos + 19 + k * 20)
        val selected = offers.getOrNull(menu.selected)
        if (selected != null && menu.progress) bar(g, selected)
        cardIcon(g)
        resultCoin(g)
        if (selected?.isOutOfStock == true && menu.restock && isHovering(186, 35, 22, 21, mouseX.toDouble(), mouseY.toDouble()))
            g.renderTooltip(font, Component.translatable("merchant.deprecated"), mouseX, mouseY)
        tooltips(g, mouseX, mouseY)
        RenderSystem.enableDepthTest()
        renderTooltip(g, mouseX, mouseY)
    }

    private fun row(g: GuiGraphics, i: Int, x: Int, y: Int) {
        val o = menu.offers[i]
        val l = line(i)
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 100f)
        when {
            l == null || l.barter -> {
                item(g, o.costA, x + 5, y)
                if (!o.costB.isEmpty) item(g, o.costB, x + 35, y)
                item(g, o.result, x + 68, y)
            }
            l.sell -> {
                money(g, l.price, x + 5, y)
                extra(o)?.let { item(g, it, x + 35, y) }
                item(g, o.result, x + 68, y)
            }
            else -> {
                item(g, o.baseCostA, x + 5, y)
                money(g, l.net, x + 68, y)
            }
        }
        RenderSystem.enableBlend()
        g.blitSprite(if (o.isOutOfStock) ARROW_OUT else ARROW, x + 55, y + 3, 0, 10, 9)
        g.pose().popPose()
    }

    private fun extra(o: MerchantOffer): ItemStack? = listOf(o.baseCostA, o.costB).firstOrNull { !it.isEmpty && Specs.emeralds(ItemSpec.id(it), 1) == null }

    private fun item(g: GuiGraphics, s: ItemStack, x: Int, y: Int) {
        g.renderFakeItem(s, x, y)
        g.renderItemDecorations(font, s, x, y)
    }

    private fun money(g: GuiGraphics, amount: Long, x: Int, y: Int) {
        val coin = (Coin.entries.lastOrNull { it.value <= amount } ?: Coin.SPUR).asStack(1)
        g.renderFakeItem(coin, x, y)
        g.renderItemDecorations(font, coin, x, y, if (amount < 0) "?" else Format.number(amount))
    }

    private fun cardIcon(g: GuiGraphics) {
        val slot = menu.card?.slot ?: return
        val stack = minecraft?.player?.inventory?.getItem(slot) ?: return
        g.pose().pushPose()
        g.pose().translate((leftPos + CARD_X).toFloat(), (topPos + CARD_Y).toFloat(), 0f)
        g.pose().scale(0.75f, 0.75f, 1f)
        g.renderFakeItem(stack, 0, 0)
        g.pose().popPose()
    }

    private fun resultCoin(g: GuiGraphics) {
        val l = line() ?: return
        if (!l.buy || menu.offers.getOrNull(menu.selected)?.isOutOfStock != false) return
        money(g, l.net, leftPos + 220, topPos + 37)
    }

    private fun bar(g: GuiGraphics, offer: MerchantOffer) {
        val level = menu.level
        if (level !in 1..4) return
        g.blitSprite(XP_BG, leftPos + 136, topPos + 16, 0, 102, 5)
        val min = VillagerData.getMinXpPerLevel(level)
        if (menu.xp < min || !VillagerData.canLevelUp(level)) return
        val f = 102f / (VillagerData.getMaxXpPerLevel(level) - min)
        val cur = minOf(Mth.floor(f * (menu.xp - min)), 102)
        g.blitSprite(XP_CUR, 102, 5, 0, 0, leftPos + 136, topPos + 16, 0, cur, 5)
        val next = offer.xp
        if (next > 0) g.blitSprite(XP_NEXT, 102, 5, cur, 0, leftPos + 136 + cur, topPos + 16, 0, minOf(Mth.floor(next * f), 102 - cur), 5)
    }

    private fun scroller(g: GuiGraphics, size: Int) {
        val steps = size + 1 - ROWS
        if (steps <= 1) return g.blitSprite(SCROLLER_OFF, leftPos + 94, topPos + 18, 0, 6, 27)
        val step = 1 + (139 - (27 + (steps - 1) * 139 / steps)) / steps + 139 / steps
        val y = if (scroll == steps - 1) 113 else minOf(113, scroll * step)
        g.blitSprite(SCROLLER, leftPos + 94, topPos + 18 + y, 0, 6, 27)
    }

    private fun tooltips(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (onCard(mouseX.toDouble(), mouseY.toDouble()))
            return g.renderComponentTooltip(font, listOf(Component.translatable("kami_economy.villager.card.hint")), mouseX, mouseY)
        if (isHovering(220, 37, 16, 16, mouseX.toDouble(), mouseY.toDouble())) line()?.takeIf { it.buy }?.let { return priceTip(g, it, mouseX, mouseY) }
        val k = rows.indexOfFirst { it.visible && it.isHovered }.takeIf { it >= 0 } ?: return
        val i = k + scroll
        val o = menu.offers.getOrNull(i) ?: return
        val l = line(i)
        val dx = mouseX - rows[k].x
        when {
            l != null && !l.barter && (if (l.sell) dx < 20 else dx > 65) -> priceTip(g, l, mouseX, mouseY)
            dx < 20 -> g.renderTooltip(font, if (l == null || l.barter) o.costA else o.baseCostA, mouseX, mouseY)
            dx in 31..49 && !o.costB.isEmpty -> g.renderTooltip(font, o.costB, mouseX, mouseY)
            dx > 65 -> g.renderTooltip(font, o.result, mouseX, mouseY)
        }
    }

    private fun priceTip(g: GuiGraphics, l: LineView, x: Int, y: Int) {
        if (l.price < 0) return g.renderComponentTooltip(font, listOf(Component.translatable("kami_economy.villager.price.none")), x, y)
        val lines = listOfNotNull(
            Component.translatable(if (l.sell) "kami_economy.villager.price.pay" else "kami_economy.villager.price.get", Format.money(l.net)).withStyle(ChatFormatting.GOLD),
            Component.translatable("kami_economy.villager.price.course", Format.rateMoney(l.unit)).withStyle(ChatFormatting.GRAY),
            Component.translatable("kami_economy.villager.price.spread", Format.percent(l.spread, 1)).withStyle(ChatFormatting.GRAY),
            Component.translatable("kami_economy.villager.price.demand", Format.percent(l.demand - 1, 0)).withStyle(ChatFormatting.GRAY).takeIf { l.demand > 1.0001 },
            Component.translatable("kami_economy.villager.price.tax", Format.percent(menu.tax / 100.0), Format.money(VillagerMath.tax(l.price, menu.tax))).withStyle(ChatFormatting.DARK_GRAY)
        )
        g.renderComponentTooltip(font, lines, x, y)
    }

    private fun onCard(x: Double, y: Double) = isHovering(CARD_X, CARD_Y, 160, 12, x, y)

    override fun mouseClicked(x: Double, y: Double, button: Int): Boolean {
        dragging = menu.offers.size > ROWS && x > leftPos + 94 && x < leftPos + 100 && y > topPos + 18 && y <= topPos + 158
        if (onCard(x, y)) {
            minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, if (button == 1) VillagerMenu.CLEAR else VillagerMenu.CYCLE)
            return true
        }
        return super.mouseClicked(x, y, button)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        if (menu.offers.size > ROWS) scroll = Mth.clamp((scroll - dy).toInt(), 0, menu.offers.size - ROWS)
        return true
    }

    override fun mouseDragged(x: Double, y: Double, button: Int, dx: Double, dy: Double): Boolean {
        if (!dragging) return super.mouseDragged(x, y, button, dx, dy)
        val f = ((y.toFloat() - topPos - 18 - 13.5f) / (139 - 27f)) * (menu.offers.size - ROWS) + 0.5f
        scroll = Mth.clamp(f.toInt(), 0, menu.offers.size - ROWS)
        return true
    }

    private val LineView.sell get() = side == Side.SELL.ordinal
    private val LineView.buy get() = side == Side.BUY.ordinal
    private val LineView.barter get() = side == Line.BARTER

    companion object {
        private const val ROWS = 7
        private const val LABEL = 4210752
        private const val CARD_X = 108
        private const val CARD_Y = 58
        private val TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/villager.png")
        private val OUT_OF_STOCK = ResourceLocation.withDefaultNamespace("container/villager/out_of_stock")
        private val XP_BG = ResourceLocation.withDefaultNamespace("container/villager/experience_bar_background")
        private val XP_CUR = ResourceLocation.withDefaultNamespace("container/villager/experience_bar_current")
        private val XP_NEXT = ResourceLocation.withDefaultNamespace("container/villager/experience_bar_result")
        private val SCROLLER = ResourceLocation.withDefaultNamespace("container/villager/scroller")
        private val SCROLLER_OFF = ResourceLocation.withDefaultNamespace("container/villager/scroller_disabled")
        private val ARROW = ResourceLocation.withDefaultNamespace("container/villager/trade_arrow")
        private val ARROW_OUT = ResourceLocation.withDefaultNamespace("container/villager/trade_arrow_out_of_stock")
    }
}
