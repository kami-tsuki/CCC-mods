package kami.economy.client

import kami.economy.client.ui.MarketScreen
import kami.economy.net.Act
import kami.economy.net.PriceDelta
import kami.economy.net.Snap
import kami.economy.net.Snapshot
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.PacketDistributor

object ClientHooks {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(name: String, vararg args: String) = PacketDistributor.sendToServer(Act(name, args.toList()))

    fun snapshot(s: Snapshot) {
        val snap = runCatching { json.decodeFromString<Snap>(s.json) }.getOrNull() ?: return
        val mc = Minecraft.getInstance()
        val screen = mc.screen
        if (screen is MarketScreen) screen.update(snap) else if (snap.open) mc.setScreen(MarketScreen(snap))
    }

    fun priceDelta(d: PriceDelta) = d.entries.forEach { PriceCache.update(it.item, it.price) }
}
