package kami.economy.client.ui

import kami.libs.economy.Coins
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class OrderRow(
    x: Int, y: Int, w: Int, h: Int,
    private val stack: ItemStack,
    private val amount: Int,
    private val price: Int,
    private val onEdit: () -> Unit,
    private val onCancel: () -> Unit
) : AbstractWidget(x, y, w, h, stack.hoverName) {
    private var cancelX = x

    init {
        setTooltip(Tooltip.create(Component.literal("Click to edit the price • click Cancel to reclaim the remaining items and their payment")))
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (isHoveredOrFocused) g.fill(x, y, x + width, y + height, Ui.HOVER)
        val font = Minecraft.getInstance().font
        g.renderItem(stack, x + 3, y + (height - 16) / 2)
        val ty = y + (height - font.lineHeight) / 2

        val cancelW = font.width("Cancel")
        cancelX = x + width - 8 - cancelW
        val cancelHover = mouseX in cancelX - 4..x + width && mouseY in y..y + height
        g.drawString(font, "Cancel", cancelX, ty, if (cancelHover) Ui.BAD else Ui.WARN, false)

        val priceAmount = Coins.compactParts(price)
        val priceX = cancelX - 12 - Ui.coinWidth(font, priceAmount)
        Ui.drawCoin(g, font, priceX, ty, priceAmount, Ui.ACCENT)

        val qtyText = "x${Ui.compact(amount)}"
        val qtyX = priceX - 12 - font.width(qtyText)
        g.drawString(font, qtyText, qtyX, ty, Ui.DIM, false)

        val nameMaxW = qtyX - (x + 24) - 6
        g.drawString(font, font.plainSubstrByWidth(stack.hoverName.string, nameMaxW.coerceAtLeast(0)), x + 24, ty, Ui.TEXT, false)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = output.add(NarratedElementType.TITLE, message)

    override fun onClick(mx: Double, my: Double) {
        if (mx >= cancelX - 4) onCancel() else onEdit()
    }
}
