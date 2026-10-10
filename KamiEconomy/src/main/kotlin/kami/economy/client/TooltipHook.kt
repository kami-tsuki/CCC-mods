package kami.economy.client

import kami.economy.client.ui.signedPercent
import kami.economy.net.PriceInfoPayload
import kami.libs.ui.style.Format
import kami.libs.ui.style.Glyphs
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import java.util.Locale

object TooltipHook {
    val info = InfoCache<PriceInfoPayload>(30_000L, 5_000L)
    private var lastSpec = ""
    private var lastInfo: PriceInfo? = null
    private var lastLot = 0
    private var lastLocale: Locale? = null
    private var line: Component = Component.empty()

    fun onTooltip(e: ItemTooltipEvent) {
        val id = MarketLink.market(e.itemStack) ?: return
        val price = PriceCache.shown(id) ?: return
        val lot = PriceCache.shownLot(id) ?: return
        if (id != lastSpec || price != lastInfo || lot != lastLot || Format.locale != lastLocale) {
            lastSpec = id
            lastInfo = price
            lastLot = lot
            lastLocale = Format.locale
            line = build(price, lot)
        }
        e.toolTip.add(line)
        if (Screen.hasShiftDown()) details(e, id, price, lot)
    }

    private fun details(e: ItemTooltipEvent, id: String, price: PriceInfo, lot: Int) {
        val count = e.itemStack.count
        if (count > 1) e.toolTip.add(muted(tr("kami_economy.tooltip.stack", count, Format.unitMoney(price.price.toLong(), lot), Format.rateMoney(price.price.toDouble() * count / lot))))
        val now = System.currentTimeMillis()
        if (info.request(id, now)) ClientHooks.request("price_info", id)
        val i = info.get(id) ?: return
        e.toolTip.add(muted(tr("kami_economy.tooltip.depth", Format.unitMoney(i.bid.toLong(), i.lot), Format.unitMoney(i.ask.toLong(), i.lot), signedPercent(i.change / 10.0))))
        val trade = if (i.embargo) tr("kami_economy.tooltip.embargo") else if (i.tariff > 0) tr("kami_economy.tooltip.tariff", i.tariff) else return
        e.toolTip.add(Component.literal(trade).withStyle { it.withColor(Palette.warning) })
    }

    private fun muted(text: String) = Component.literal(text).withStyle { it.withColor(Palette.textSecondary) }

    private fun build(info: PriceInfo, lot: Int): Component {
        val (glyph, color) = when (info.direction) {
            Direction.UP -> Glyphs.Glyph.UP to Palette.success
            Direction.DOWN -> Glyphs.Glyph.DOWN to Palette.danger
            Direction.FLAT -> Glyphs.Glyph.FLAT to Palette.textSecondary
        }
        return Component.literal(tr("kami_economy.tooltip.market") + " ").withStyle { it.withColor(Palette.textSecondary) }
            .append(Component.literal(tr("kami_economy.tooltip.unit", Format.unitMoney(info.price.toLong(), lot))).withStyle { it.withColor(Palette.text) })
            .append(Component.literal(" ").append(Glyphs.component(glyph).withColor(color)))
    }
}
