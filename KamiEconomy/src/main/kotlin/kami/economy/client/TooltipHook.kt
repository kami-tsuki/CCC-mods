package kami.economy.client

import kami.libs.economy.Coins
import kami.libs.ui.Theme
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent

object TooltipHook {
    fun onTooltip(e: ItemTooltipEvent) {
        val id = BuiltInRegistries.ITEM.getKey(e.itemStack.item).toString()
        val info = PriceCache.of(id) ?: return
        val arrow = when (info.direction) {
            Direction.UP -> "▲" to Theme.GOOD
            Direction.DOWN -> "▼" to Theme.BAD
            Direction.FLAT -> "▬" to Theme.DIM
        }
        val amount = Coins.compactParts(info.price)
        e.toolTip.add(
            Component.literal("Market: ").withStyle { it.withColor(Theme.DIM) }
                .append(Component.literal(amount.amount).withStyle { it.withColor(Theme.TEXT) })
                .append(Component.literal(amount.glyph.toString()).withStyle { it.withColor(0xFFFFFF) })
                .append(Component.literal(" ${arrow.first}").withStyle { it.withColor(arrow.second) })
        )
    }
}
