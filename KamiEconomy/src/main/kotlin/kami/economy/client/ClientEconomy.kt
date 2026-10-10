package kami.economy.client

import kami.economy.client.admin.MarketSection
import kami.economy.client.compat.JadeEconomyClient
import kami.economy.client.ui.MarketApp
import kami.economy.client.ui.PersonalPages
import kami.economy.client.compat.jei.EconomyJei
import kami.economy.client.compat.jei.JeiStore
import kami.economy.client.compat.xaero.VendorPoints
import kami.economy.world.CoinRules
import kami.libs.compat.Mods
import kami.libs.compat.Soft
import kami.libs.jei.KamiJei
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.Modules
import kami.libs.ui.app.registerOpenKey
import kami.libs.ui.style.Icons
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientEconomy {
    fun init() {
        Modules.register(AppModule("market", "kami_libs.common.market", Icons.COIN, 20) { MarketApp.open() })
        PersonalPages.register()
        MarketSection.register()
        CoinRules.hideFromTabs()
        VendorPoints.init()
        if (KamiJei.present) {
            KamiJei.module(EconomyJei)
            JeiStore.init()
        }
        MOD_BUS.addListener<FMLClientSetupEvent> {
            Soft.expect(Mods.JEI, "mezz.jei.common.util.ServerCommandUtil", "executeGive", "setHotbarSlot", "mousePickupItemStack", "giveToInventory")
        }
        MarketLink.init()
        WatchKey.init()
        Worth.init()
        JadeEconomyClient.register()
        registerOpenKey("kami_economy", GLFW.GLFW_KEY_UNKNOWN) { MarketApp.open() }
        MarketApp.opening.register("kami_economy")
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            MarketApp.instance.reset()
            MarketSection.clear()
            VendorPoints.clear()
            kami.economy.economy.ClientVariants.map = emptyMap()
            TooltipHook.info.clear()
        }
        FORGE_BUS.addListener<ItemTooltipEvent> { TooltipHook.onTooltip(it) }
    }
}
