package kami.economy.client

import kami.economy.client.ui.MarketApp
import kami.economy.net.Net
import kami.economy.net.PriceDelta
import kami.economy.net.Snap
import kami.libs.net.SnapshotPayload
import kami.libs.net.decode
import net.neoforged.neoforge.network.PacketDistributor

object ClientHooks {
    fun request(name: String, vararg args: String) = PacketDistributor.sendToServer(Net.act(name, args.toList()))

    fun snapshot(s: SnapshotPayload) {
        val snap = s.decode<Snap>() ?: return
        MarketApp.receive(snap)
    }

    fun priceDelta(d: PriceDelta) = d.entries.forEach { PriceCache.update(it.item, it.price) }
}
