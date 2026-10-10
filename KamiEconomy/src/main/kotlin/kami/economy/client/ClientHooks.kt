package kami.economy.client

import kami.economy.client.compat.jei.JeiStore
import kami.economy.client.ui.MarketApp
import kami.economy.client.compat.xaero.VendorPoints
import kami.economy.net.Net
import kami.economy.net.JeiPayload
import kami.economy.net.PriceDelta
import kami.economy.net.PriceInfoPayload
import kami.economy.net.Snap
import kami.libs.net.SnapshotPayload
import kami.libs.net.decode
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.PacketDistributor

object ClientHooks {
    fun request(name: String, vararg args: String) {
        if (Minecraft.getInstance().connection != null) PacketDistributor.sendToServer(Net.act(name, args.toList()))
    }

    fun snapshot(s: SnapshotPayload) {
        val snap = s.decode<Snap>() ?: return
        snap.vendors.ifEmpty { snap.detail?.vendors.orEmpty() }.takeIf { it.isNotEmpty() }?.let(VendorPoints::update)
        MarketApp.receive(snap)
    }

    fun variants(map: Map<String, kami.economy.economy.Variant>) {
        kami.economy.economy.ClientVariants.map = map
        PriceCache.clearShown()
    }

    fun priceInfo(p: PriceInfoPayload) = TooltipHook.info.put(p.item, p, System.currentTimeMillis())

    fun priceDelta(d: PriceDelta) {
        d.entries.forEach { PriceCache.lot(it.item, it.lot); PriceCache.update(it.item, it.price); WatchKey.moved(it.item, it.price) }
        JeiStore.prices()
    }

    fun jei(p: JeiPayload) = JeiStore.put(p)
}
