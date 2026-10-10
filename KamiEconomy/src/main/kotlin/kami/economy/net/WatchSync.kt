package kami.economy.net

import kami.economy.Config
import kami.economy.KamiEconomy
import kami.economy.client.WatchKey
import kami.economy.economy.Watchlist
import kami.libs.net.Packets
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

class WatchIdsPayload(val ids: List<String>, val thresholdPct: Float) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<WatchIdsPayload>(Packets.forMod(KamiEconomy.ID).id("watch_ids"))
        val CODEC = Packets.forMod(KamiEconomy.ID).codec<WatchIdsPayload>(
            { b, v -> b.writeCollection(v.ids) { bb, s -> bb.writeUtf(s, 128) }; b.writeFloat(v.thresholdPct) },
            { b -> WatchIdsPayload(b.readList { bb -> bb.readUtf(128) }, b.readFloat()) }
        )
    }
}

object WatchSync {
    fun register(r: net.neoforged.neoforge.network.registration.PayloadRegistrar) =
        r.playToClient(WatchIdsPayload.TYPE, WatchIdsPayload.CODEC) { v, _ -> WatchKey.receive(v) }

    fun send(p: ServerPlayer) =
        PacketDistributor.sendToPlayer(p, WatchIdsPayload(Watchlist.of(p.stringUUID).toList(), Config.s.discordMoveThresholdPct.toFloat()))
}
