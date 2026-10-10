package kami.economy.client

import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.Locale

object Worth {
    private var value = 0L
    private var at = 0L
    private var text = ""
    private var locale: Locale? = null

    fun init() {
        FORGE_BUS.addListener<ScreenEvent.Render.Post> {
            val screen = it.screen as? AbstractContainerScreen<*> ?: return@addListener
            if (!MarketPrefs.prefs.showWorth) return@addListener
            val player = Minecraft.getInstance().player ?: return@addListener
            val now = System.currentTimeMillis()
            if (now - at > 500) {
                at = now
                val inv = player.inventory
                val total = (inv.items + inv.armor + inv.offhand).sumOf { s ->
                    val id = MarketLink.market(s) ?: return@sumOf 0.0
                    val price = PriceCache.shown(id)?.price ?: return@sumOf 0.0
                    val lot = PriceCache.shownLot(id) ?: return@sumOf 0.0
                    price.toDouble() * s.count / lot
                }.toLong()
                if (total != value || Format.locale != locale || text.isEmpty()) {
                    value = total
                    locale = Format.locale
                    text = if (total > 0) tr("kami_economy.worth.line", Format.money(total)) else ""
                }
            }
            if (text.isNotEmpty()) Draw.textRight(it.guiGraphics, text, screen.guiLeft + screen.xSize - 4, screen.guiTop + screen.ySize + 3, Palette.textMuted)
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { value = 0; at = 0; text = "" }
    }
}
