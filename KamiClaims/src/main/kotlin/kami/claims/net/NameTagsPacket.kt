package kami.claims.net

import kami.claims.KamiClaims
import kami.claims.Rank
import kami.libs.net.Packets
import kami.libs.skills.SkillClass
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID
import kotlin.math.max

private val net = Packets.forMod(KamiClaims.ID)

data class TagView(val id: UUID, val country: String?, val color: Int, val rank: Int, val cls: Int, val level: Int)

class NameTagsPacket(val tags: Map<UUID, TagView>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val CAP = 64
        val TYPE = CustomPacketPayload.Type<NameTagsPacket>(net.id("nametags"))
        val CODEC = net.codec<NameTagsPacket>(::write, ::read)

        fun write(b: FriendlyByteBuf, v: NameTagsPacket) {
            require(v.tags.size <= CAP) { "list too long" }
            b.writeVarInt(v.tags.size)
            v.tags.values.forEach {
                b.writeUUID(it.id); b.writeUtf(it.country ?: "", 64); b.writeInt(it.color)
                b.writeByte(it.rank + 1); b.writeByte(it.cls + 1); b.writeVarInt(it.level)
            }
        }

        fun read(b: FriendlyByteBuf): NameTagsPacket {
            val n = b.readVarInt()
            require(n in 0..CAP) { "list too long" }
            return NameTagsPacket(List(n) {
                TagView(b.readUUID(), b.readUtf(64).ifEmpty { null }, b.readInt(), b.readUnsignedByte().toInt() - 1, b.readUnsignedByte().toInt() - 1, b.readVarInt())
            }.associateBy { it.id })
        }
    }
}

enum class RowKind { COUNTRY, RANK, NOMAD, CLASS, LEVEL }

data class TagRow(val kind: RowKind, val text: String, val color: Int, val level: Int = 0)

object TagRows {
    const val GRAY = 0xAAAAAA

    fun rows(t: TagView): List<TagRow> {
        val first = when {
            t.country == null -> TagRow(RowKind.NOMAD, "", GRAY)
            t.rank >= 0 -> TagRow(RowKind.RANK, Rank.entries.getOrNull(t.rank)?.name?.lowercase() ?: "citizen", readable(t.color))
            else -> TagRow(RowKind.COUNTRY, t.country, readable(t.color))
        }
        val second = when {
            t.cls >= 0 -> SkillClass.entries.getOrNull(t.cls)?.let { TagRow(RowKind.CLASS, it.id, it.color, t.level) }
            t.level > 0 -> TagRow(RowKind.LEVEL, "", GRAY, t.level)
            else -> null
        }
        return listOfNotNull(first, second)
    }

    fun readable(c: Int): Int {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        if (max(r, max(g, b)) >= 0x70) return c and 0xFFFFFF
        return ((r + 255) / 2 shl 16) or ((g + 255) / 2 shl 8) or ((b + 255) / 2)
    }
}
