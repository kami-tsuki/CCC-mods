package kami.geology.net

import kami.geology.KamiGeology
import kami.libs.net.Packets
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

private fun id(path: String) = Packets.id(KamiGeology.ID, path)

private fun <T : CustomPacketPayload> codec(write: (FriendlyByteBuf, T) -> Unit, read: (FriendlyByteBuf) -> T) =
    Packets.codec(write, read)

class OreInfo(
    val id: String,
    val color: Int,
    val deposit: Boolean,
    val scatterColumn: Float,
    val scatterLow: Int,
    val scatterHigh: Int,
    val scatterTriangle: Boolean,
    val scatterHomes: List<Int>?
)

class ProvinceInfo(val name: String, val color: Int)

class OpenMap(
    val dimension: String,
    val x: Int,
    val z: Int,
    val minY: Int,
    val maxY: Int,
    val ores: List<OreInfo>,
    val provinces: List<ProvinceInfo>,
    val lockX0: Int? = null,
    val lockZ0: Int? = null,
    val lockW: Int? = null,
    val lockH: Int? = null
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenMap>(id("open_map"))
        val CODEC = codec<OpenMap>(
            { buf, v ->
                buf.writeUtf(v.dimension)
                buf.writeInt(v.x)
                buf.writeInt(v.z)
                buf.writeInt(v.minY)
                buf.writeInt(v.maxY)
                buf.writeCollection(v.ores) { b, o ->
                    b.writeUtf(o.id)
                    b.writeInt(o.color)
                    b.writeBoolean(o.deposit)
                    b.writeFloat(o.scatterColumn)
                    b.writeInt(o.scatterLow)
                    b.writeInt(o.scatterHigh)
                    b.writeBoolean(o.scatterTriangle)
                    b.writeBoolean(o.scatterHomes != null)
                    o.scatterHomes?.let { homes -> b.writeCollection(homes) { hb, index -> hb.writeVarInt(index) } }
                }
                buf.writeCollection(v.provinces) { b, p ->
                    b.writeUtf(p.name)
                    b.writeInt(p.color)
                }
                buf.writeBoolean(v.lockX0 != null)
                if (v.lockX0 != null) {
                    buf.writeInt(v.lockX0)
                    buf.writeInt(v.lockZ0!!)
                    buf.writeInt(v.lockW!!)
                    buf.writeInt(v.lockH!!)
                }
            },
            { buf ->
                val dimension = buf.readUtf()
                val x = buf.readInt()
                val z = buf.readInt()
                val minY = buf.readInt()
                val maxY = buf.readInt()
                val ores = buf.readList { b ->
                    OreInfo(
                        b.readUtf(), b.readInt(), b.readBoolean(), b.readFloat(), b.readInt(), b.readInt(), b.readBoolean(),
                        if (b.readBoolean()) b.readList { hb -> hb.readVarInt() } else null
                    )
                }
                val provinces = buf.readList { b -> ProvinceInfo(b.readUtf(), b.readInt()) }
                val locked = buf.readBoolean()
                val lx0 = if (locked) buf.readInt() else null
                val lz0 = if (locked) buf.readInt() else null
                val lw = if (locked) buf.readInt() else null
                val lh = if (locked) buf.readInt() else null
                OpenMap(dimension, x, z, minY, maxY, ores, provinces, lx0, lz0, lw, lh)
            }
        )
    }
}

class MapRequest(
    val seq: Int,
    val x0: Int, val z0: Int, val cell: Int, val w: Int, val h: Int,
    val y0: Int, val y1: Int
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<MapRequest>(id("map_request"))
        val CODEC = codec<MapRequest>(
            { buf, v ->
                buf.writeVarInt(v.seq)
                buf.writeInt(v.x0)
                buf.writeInt(v.z0)
                buf.writeVarInt(v.cell)
                buf.writeVarInt(v.w)
                buf.writeVarInt(v.h)
                buf.writeInt(v.y0)
                buf.writeInt(v.y1)
            },
            { buf ->
                MapRequest(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readInt())
            }
        )
    }
}

class MapLayer(val seq: Int, val ore: Int, val data: ByteArray) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<MapLayer>(id("map_layer"))
        val CODEC = codec<MapLayer>(
            { buf, v ->
                buf.writeVarInt(v.seq)
                buf.writeVarInt(v.ore + 1)
                buf.writeByteArray(v.data)
            },
            { buf -> MapLayer(buf.readVarInt(), buf.readVarInt() - 1, buf.readByteArray()) }
        )
    }
}

class MapDone(val seq: Int, val wallMs: Int, val provinceMs: Int, val sitesMs: Int, val paintMs: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<MapDone>(id("map_done"))
        val CODEC = codec<MapDone>(
            { buf, v ->
                buf.writeVarInt(v.seq)
                buf.writeVarInt(v.wallMs)
                buf.writeVarInt(v.provinceMs)
                buf.writeVarInt(v.sitesMs)
                buf.writeVarInt(v.paintMs)
            },
            { buf -> MapDone(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()) }
        )
    }
}

class ProbeRequest(val seq: Int, val x: Int, val z: Int, val y0: Int, val y1: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ProbeRequest>(id("probe_request"))
        val CODEC = codec<ProbeRequest>(
            { buf, v ->
                buf.writeVarInt(v.seq)
                buf.writeInt(v.x)
                buf.writeInt(v.z)
                buf.writeInt(v.y0)
                buf.writeInt(v.y1)
            },
            { buf -> ProbeRequest(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()) }
        )
    }
}

class ProbeResponse(val seq: Int, val x: Int, val z: Int, val lines: List<String>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ProbeResponse>(id("probe_response"))
        val CODEC = codec<ProbeResponse>(
            { buf, v ->
                buf.writeVarInt(v.seq)
                buf.writeInt(v.x)
                buf.writeInt(v.z)
                buf.writeCollection(v.lines) { b, line -> b.writeUtf(line) }
            },
            { buf -> ProbeResponse(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readList { b -> b.readUtf() }) }
        )
    }
}
