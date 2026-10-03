package kami.economy.client

import com.mojang.blaze3d.platform.InputConstants
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.Modules
import kami.libs.ui.style.Icons
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientEconomy {
    private val open = KeyMapping("key.kami_economy.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.kami_economy")

    fun init() {
        Modules.register(AppModule("market", "kami_economy.module.market", Icons.COIN) { ClientHooks.request("open") })
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(open) }
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            while (open.consumeClick()) if (Minecraft.getInstance().screen == null) ClientHooks.request("open")
        }
        FORGE_BUS.addListener<ItemTooltipEvent> { TooltipHook.onTooltip(it) }
    }
}
