package kami.economy.villager

import kami.economy.KamiEconomy
import kami.libs.net.Packets
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.item.trading.MerchantOffers

class LineView(val side: Int, val price: Long, val net: Long, val unit: Double, val spread: Double, val demand: Double)

class CardView(val slot: Int, val label: String, val balance: Long)

class VillagerSync(
    val container: Int, val offers: MerchantOffers, val level: Int, val xp: Int, val progress: Boolean, val restock: Boolean,
    val lines: List<LineView>, val card: CardView?, val tax: Int
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val LABEL = 64
        private val packets = Packets.forMod(KamiEconomy.ID)
        val TYPE = CustomPacketPayload.Type<VillagerSync>(packets.id("villager"))
        val CODEC = packets.codec<VillagerSync>(::write, ::read)

        private fun write(b: FriendlyByteBuf, v: VillagerSync) {
            b.writeVarInt(v.container)
            MerchantOffers.STREAM_CODEC.encode(b as RegistryFriendlyByteBuf, v.offers)
            b.writeVarInt(v.level); b.writeVarInt(v.xp); b.writeBoolean(v.progress); b.writeBoolean(v.restock); b.writeVarInt(v.tax)
            b.writeCollection(v.lines) { bb, l -> bb.writeVarInt(l.side); bb.writeVarLong(l.price + 1); bb.writeVarLong(l.net + 1); bb.writeDouble(l.unit); bb.writeDouble(l.spread); bb.writeDouble(l.demand) }
            b.writeNullable(v.card) { bb, c -> bb.writeVarInt(c.slot); bb.writeUtf(c.label, LABEL); bb.writeVarLong(c.balance) }
        }

        private fun read(b: FriendlyByteBuf): VillagerSync {
            val container = b.readVarInt()
            val offers = MerchantOffers.STREAM_CODEC.decode(b as RegistryFriendlyByteBuf)
            val level = b.readVarInt(); val xp = b.readVarInt(); val progress = b.readBoolean(); val restock = b.readBoolean(); val tax = b.readVarInt()
            val lines = b.readList { bb -> LineView(bb.readVarInt(), bb.readVarLong() - 1, bb.readVarLong() - 1, bb.readDouble(), bb.readDouble(), bb.readDouble()) }
            val card = b.readNullable { bb -> CardView(bb.readVarInt(), bb.readUtf(LABEL), bb.readVarLong()) }
            return VillagerSync(container, offers, level, xp, progress, restock, lines, card, tax)
        }
    }
}
