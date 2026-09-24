package kami.economy.client.ui

import kami.libs.economy.Coins
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class ItemCell(
    x: Int, y: Int, size: Int,
    private val stack: ItemStack,
    private val price: Int,
    private val available: Int,
    private val infinite: Boolean,
    private val onBuy: (Int) -> Unit
) : AbstractWidget(x, y, size, size, stack.hoverName) {

    init {
        val stock = if (infinite) "\nUnlimited stock (no player is undercutting it)" else "\n${Ui.fmt(available)} available"
        setTooltip(Tooltip.create(
            Component.empty()
                .append(stack.hoverName)
                .append(Component.literal("\n${Ui.fmt(price)} spurs each"))
                .append(Component.literal(stock))
                .append(Component.literal("\nClick to buy 1  •  Shift-click for a stack"))
        ))
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (isHoveredOrFocused) g.fill(x, y, x + width, y + height, Ui.HOVER)
        g.renderItem(stack, x + (width - 16) / 2, y + 3)
        val font = Minecraft.getInstance().font
        val amount = Coins.compactParts(price)
        val tx = x + (width - Ui.coinWidth(font, amount)) / 2
        Ui.drawCoin(g, font, tx, y + height - 10, amount, Ui.ACCENT)
        if (infinite) g.drawString(font, "∞", x + width - 8, y + 1, Ui.GOOD, false)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = output.add(NarratedElementType.TITLE, message)

    override fun onClick(mx: Double, my: Double) = onBuy(if (Screen.hasShiftDown()) stack.maxStackSize else 1)
}
