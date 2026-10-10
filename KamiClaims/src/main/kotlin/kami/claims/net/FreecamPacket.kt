package kami.claims.net

import kami.claims.KamiClaims
import kami.libs.net.Packets
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

private val net = Packets.forMod(KamiClaims.ID)

class FreecamPacket(val state: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FreecamPacket>(net.id("freecam"))
        val CODEC = net.codec<FreecamPacket>({ b, v -> b.writeByte(v.state) }, { b -> FreecamPacket(b.readUnsignedByte().toInt()) })
    }
}
