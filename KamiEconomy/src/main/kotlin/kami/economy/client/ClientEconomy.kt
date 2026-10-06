package kami.economy.client

import com.mojang.blaze3d.platform.InputConstants
import kami.economy.client.admin.MarketSection
import kami.economy.client.ui.MarketApp
import kami.economy.client.ui.PersonalPages
import kami.economy.world.CoinRules
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.Modules
import kami.libs.ui.style.Icons
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientEconomy {
    private val open = KeyMapping("key.kami_economy.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.kami_economy")

    fun init() {
        Modules.register(AppModule("market", "kami_economy.module.market", Icons.COIN, 20) { MarketApp.open() })
        PersonalPages.register()
        MarketSection.register()
        CoinRules.hideFromTabs()
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(open) }
        MOD_BUS.addListener<RegisterGuiLayersEvent> { it.registerAboveAll(ResourceLocation.fromNamespaceAndPath("kami_economy", "opening")) { g, _ -> MarketApp.opening.render(g) } }
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            MarketApp.opening.tick()
            while (open.consumeClick()) if (Minecraft.getInstance().screen == null) MarketApp.open()
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            MarketApp.instance.reset()
            MarketSection.clear()
        }
        FORGE_BUS.addListener<ItemTooltipEvent> { TooltipHook.onTooltip(it) }
    }
}
