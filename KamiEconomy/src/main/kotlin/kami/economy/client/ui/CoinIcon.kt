package kami.economy.client.ui

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object CoinIcon {
    const val SIZE = 16

    private val stack: ItemStack by lazy {
        val item = ResourceLocation.tryParse("numismatics:spur")?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.SUNFLOWER
        ItemStack(item)
    }

    fun render(g: GuiGraphics, x: Int, y: Int) = g.renderItem(stack, x, y)
}
