package kami.essentials.client

import com.mojang.blaze3d.platform.InputConstants
import kami.essentials.net.Net
import kami.libs.net.SnapshotPayload
import kami.libs.net.decode
import kami.libs.text.Text
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.Modules
import kami.libs.ui.style.Icons
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientEssentials {
    private val key = KeyMapping("key.kami_essentials.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.kami_essentials")

    fun init() {
        Modules.register(AppModule("essentials", "kami_essentials.module", Icons.SETTINGS) { open() })
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(key) }
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            while (key.consumeClick()) if (Minecraft.getInstance().screen == null) open()
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { PrefsApp.instance.snap = null }
    }

    private fun open() {
        val mc = Minecraft.getInstance()
        val app = PrefsApp.instance
        if ((mc.screen as? AppScreen)?.app !== app) mc.setScreen(AppScreen(app, Text.msg("kami_essentials.module")))
        request("open")
    }

    fun request(name: String, vararg args: String) {
        if (Minecraft.getInstance().connection?.hasChannel(Net.actType) == true) PacketDistributor.sendToServer(Net.act(name, *args))
    }

    fun receive(s: SnapshotPayload) {
        PrefsApp.instance.snap = s.decode() ?: return
    }
}
