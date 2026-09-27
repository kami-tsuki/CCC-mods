package kami.economy.client

import kami.economy.client.ui.MarketApp
import kami.economy.net.Act
import kami.economy.net.PriceDelta
import kami.economy.net.Snap
import kami.economy.net.Snapshot
import kotlinx.serialization.json.Json
import net.neoforged.neoforge.network.PacketDistributor

object ClientHooks {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(name: String, vararg args: String) = PacketDistributor.sendToServer(Act(name, args.toList()))

    fun snapshot(s: Snapshot) {
        val snap = runCatching { json.decodeFromString<Snap>(s.json) }.getOrNull() ?: return
        MarketApp.receive(snap)
    }

    fun priceDelta(d: PriceDelta) = d.entries.forEach { PriceCache.update(it.item, it.price) }
}
