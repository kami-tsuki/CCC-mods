package kami.economy.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.world.item.ItemStack

class SellCell(
    x: Int, y: Int, size: Int,
    private val stack: ItemStack,
    private val onOpen: () -> Unit
) : AbstractWidget(x, y, size, size, stack.hoverName) {

    init {
        setTooltip(Tooltip.create(stack.hoverName))
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (isHoveredOrFocused) g.fill(x, y, x + width, y + height, Ui.HOVER)
        val font = Minecraft.getInstance().font
        val ix = x + (width - 16) / 2
        val iy = y + (height - 16) / 2 - 3
        g.renderItem(stack, ix, iy)
        g.renderItemDecorations(font, stack, ix, iy)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = output.add(NarratedElementType.TITLE, message)

    override fun onClick(mx: Double, my: Double) = onOpen()
}
