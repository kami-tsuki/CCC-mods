package kami.claims.net

import kami.claims.KamiClaims
import kami.claims.war.Entry
import kami.claims.war.PositionFilter
import kami.claims.war.Selection
import kami.libs.net.Packets
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

private val net = Packets.forMod(KamiClaims.ID)

class PositionsPacket(val data: Selection) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PositionsPacket>(net.id("positions"))
        val CODEC = net.codec<PositionsPacket>(::write, ::read)

        fun write(b: FriendlyByteBuf, v: PositionsPacket) {
            b.writeVarInt(v.data.entries.size)
            v.data.entries.forEach { b.writeUUID(it.id); b.writeUtf(it.dim, 256); b.writeInt(it.x); b.writeShort(it.y); b.writeInt(it.z) }
            b.writeVarInt(v.data.friendly.size)
            v.data.friendly.forEach(b::writeUUID)
            b.writeBoolean(v.data.war)
        }

        fun read(b: FriendlyByteBuf): PositionsPacket {
            val entries = List(b.count()) { Entry(b.readUUID(), b.readUtf(256), b.readInt(), b.readShort().toInt(), b.readInt()) }
            val friendly = List(b.count()) { b.readUUID() }.toSet()
            return PositionsPacket(Selection(entries, friendly, b.readBoolean()))
        }

        private fun FriendlyByteBuf.count() = readVarInt().also { require(it in 0..PositionFilter.CAP) { "list too long" } }
    }
}
