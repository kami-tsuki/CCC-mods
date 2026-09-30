package kami.economy.client

import kami.libs.ui.text.tr
import kami.libs.economy.Coins
import kami.libs.ui.style.Palette
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent

object TooltipHook {
    fun onTooltip(e: ItemTooltipEvent) {
        val id = BuiltInRegistries.ITEM.getKey(e.itemStack.item).toString()
        val info = PriceCache.of(id) ?: return
        val arrow = when (info.direction) {
            Direction.UP -> "▲" to Palette.success
            Direction.DOWN -> "▼" to Palette.danger
            Direction.FLAT -> "▬" to Palette.textSecondary
        }
        val amount = Coins.compactParts(info.price)
        e.toolTip.add(
            Component.literal(tr("kami_economy.tooltip.market") + " ").withStyle { it.withColor(Palette.textSecondary) }
                .append(Component.literal(amount.amount).withStyle { it.withColor(Palette.text) })
                .append(Component.literal(amount.glyph.toString()).withStyle { it.withColor(0xFFFFFF) })
                .append(Component.literal(" ${arrow.first}").withStyle { it.withColor(arrow.second) })
        )
    }
}
