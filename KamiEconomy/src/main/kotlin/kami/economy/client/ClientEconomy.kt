package kami.economy.client

import kami.economy.client.admin.MarketSection
import kami.economy.client.ui.MarketApp
import kami.economy.client.ui.PersonalPages
import kami.economy.world.CoinRules
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.Modules
import kami.libs.ui.app.registerOpenKey
import kami.libs.ui.style.Icons
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object ClientEconomy {
    fun init() {
        Modules.register(AppModule("market", "kami_economy.module.market", Icons.COIN, 20) { MarketApp.open() })
        PersonalPages.register()
        MarketSection.register()
        CoinRules.hideFromTabs()
        registerOpenKey("kami_economy", GLFW.GLFW_KEY_M) { MarketApp.open() }
        MarketApp.opening.register("kami_economy")
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            MarketApp.instance.reset()
            MarketSection.clear()
        }
        FORGE_BUS.addListener<ItemTooltipEvent> { TooltipHook.onTooltip(it) }
    }
}
