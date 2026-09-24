package kami.economy.client.ui

import kami.economy.economy.StackCodec
import kami.economy.net.AuctionLine
import kami.libs.economy.Coins
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class AuctionRow(
    x: Int, y: Int, w: Int, h: Int,
    private val a: AuctionLine,
    private val onSelect: () -> Unit
) : AbstractWidget(x, y, w, h, Component.literal(a.label)) {

    private val stack: ItemStack = Minecraft.getInstance().level?.registryAccess()?.let { StackCodec.decode(a.stackData, it) } ?: ItemStack.EMPTY

    init {
        val bid = if (a.currentBid > 0) "Current bid: ${Ui.fmt(a.currentBid)}" else "Starting price: ${Ui.fmt(a.startPrice)}"
        val buyNow = if (a.buyNowPrice > 0) "\nBuy now: ${Ui.fmt(a.buyNowPrice)}" else ""
        setTooltip(Tooltip.create(Component.literal("${a.label}\n$bid$buyNow")))
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (isHoveredOrFocused) g.fill(x, y, x + width, y + height, Ui.HOVER)
        val font = Minecraft.getInstance().font
        val ty = y + (height - font.lineHeight) / 2
        if (!stack.isEmpty) g.renderItem(stack, x + 2, y + (height - 16) / 2)
        val textX = x + 2 + (if (stack.isEmpty) 0 else 18)
        val price = if (a.currentBid > 0) a.currentBid else a.startPrice
        val priceAmount = Coins.compactParts(price)
        val priceX = x + width - 8 - Ui.coinWidth(font, priceAmount)
        Ui.drawCoin(g, font, priceX, ty, priceAmount, Ui.ACCENT)

        val tag = if (a.mine) " (yours)" else ""
        val nameMaxW = priceX - textX - 6
        g.drawString(font, font.plainSubstrByWidth("${a.label}$tag", nameMaxW.coerceAtLeast(0)), textX, ty, if (a.mine) Ui.DIM else Ui.TEXT, false)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = output.add(NarratedElementType.TITLE, message)

    override fun onClick(mx: Double, my: Double) = onSelect()
}
