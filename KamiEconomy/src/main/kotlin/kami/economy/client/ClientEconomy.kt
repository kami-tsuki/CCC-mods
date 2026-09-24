package kami.economy.client

import com.mojang.blaze3d.platform.InputConstants
import kami.economy.net.Act
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientEconomy {
    private val open = KeyMapping("key.kami_economy.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.kami_economy")

    fun init() {
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(open) }
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            while (open.consumeClick()) if (Minecraft.getInstance().screen == null) PacketDistributor.sendToServer(Act("open", emptyList()))
        }
        FORGE_BUS.addListener<ItemTooltipEvent> { TooltipHook.onTooltip(it) }
    }
}
