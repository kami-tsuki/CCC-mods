package kami.economy.client.villager

import kami.economy.villager.VillagerMenu
import kami.economy.villager.VillagerSync
import kami.economy.villager.VillagerTrade
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object VillagerClient {
    fun init() = MOD_BUS.addListener<RegisterMenuScreensEvent> { it.register(VillagerTrade.MENU.get(), ::VillagerScreen) }

    fun sync(s: VillagerSync) {
        val m = Minecraft.getInstance().player?.containerMenu as? VillagerMenu ?: return
        if (m.containerId == s.container) m.apply(s)
    }
}
