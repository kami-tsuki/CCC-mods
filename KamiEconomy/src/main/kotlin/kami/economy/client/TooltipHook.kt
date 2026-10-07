package kami.economy.client

import kami.libs.economy.Coins
import kami.libs.ui.style.Format
import kami.libs.ui.style.Glyphs
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import java.util.Locale

object TooltipHook {
    private var lastItem: Item? = null
    private var lastInfo: PriceInfo? = null
    private var lastLocale: Locale? = null
    private var line: Component = Component.empty()

    fun onTooltip(e: ItemTooltipEvent) {
        val item = e.itemStack.item
        val info = PriceCache.of(BuiltInRegistries.ITEM.getKey(item).toString()) ?: return
        if (item !== lastItem || info !== lastInfo || Format.locale != lastLocale) {
            lastItem = item
            lastInfo = info
            lastLocale = Format.locale
            line = build(info)
        }
        e.toolTip.add(line)
    }

    private fun build(info: PriceInfo): Component {
        val (glyph, color) = when (info.direction) {
            Direction.UP -> Glyphs.Glyph.UP to Palette.success
            Direction.DOWN -> Glyphs.Glyph.DOWN to Palette.danger
            Direction.FLAT -> Glyphs.Glyph.FLAT to Palette.textSecondary
        }
        val amount = Coins.compactParts(info.price)
        return Component.literal(tr("kami_economy.tooltip.market") + " ").withStyle { it.withColor(Palette.textSecondary) }
            .append(Component.literal(amount.amount).withStyle { it.withColor(Palette.text) })
            .append(Component.literal(amount.glyph.toString()).withStyle { it.withColor(0xFFFFFF) })
            .append(Component.literal(" ").append(Glyphs.component(glyph).withColor(color)))
    }
}
