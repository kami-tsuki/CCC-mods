package kami.libs.net

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import kotlinx.serialization.json.Json

class ActPayload(
    val name: String,
    val args: List<String>,
    val asCountry: String = "",
    val rid: Int = 0,
    private val payloadType: CustomPacketPayload.Type<ActPayload>
) : CustomPacketPayload {
    override fun type() = payloadType

    class Channel(val type: CustomPacketPayload.Type<ActPayload>, val codec: StreamCodec<RegistryFriendlyByteBuf, ActPayload>) {
        operator fun invoke(name: String, args: List<String> = emptyList(), asCountry: String = "", rid: Int = 0) =
            ActPayload(name, args, asCountry, rid, type)
    }

    companion object {
        const val MAX_COUNTRY = 48

        fun channel(net: Packets.ModPackets, path: String = "act", maxArgs: Int = 8, maxArg: Int = 4096, withCountry: Boolean = true): Channel {
            val type = CustomPacketPayload.Type<ActPayload>(net.id(path))
            val codec = net.codec<ActPayload>(
                { b, v ->
                    b.writeUtf(v.name, 32)
                    b.writeVarInt(v.args.size)
                    v.args.forEach { b.writeUtf(it, maxArg) }
                    if (withCountry) {
                        b.writeUtf(v.asCountry, MAX_COUNTRY)
                        b.writeVarInt(v.rid)
                    }
                },
                { b ->
                    val name = b.readUtf(32)
                    val count = b.readVarInt().also { require(it in 0..maxArgs) { "list too long" } }
                    val args = List(count) { b.readUtf(maxArg) }
                    val asCountry = if (withCountry) b.readUtf(MAX_COUNTRY) else ""
                    val rid = if (withCountry) b.readVarInt() else 0
                    ActPayload(name, args, asCountry, rid, type)
                }
            )
            return Channel(type, codec)
        }
    }
}

class SnapshotPayload(val json: String, private val payloadType: CustomPacketPayload.Type<SnapshotPayload>) : CustomPacketPayload {
    override fun type() = payloadType

    class Channel(val type: CustomPacketPayload.Type<SnapshotPayload>, val codec: StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload>) {
        operator fun invoke(json: String) = SnapshotPayload(json, type)
    }

    companion object {
        fun channel(net: Packets.ModPackets, path: String = "snapshot", maxBytes: Int = 1_048_576): Channel {
            val type = CustomPacketPayload.Type<SnapshotPayload>(net.id(path))
            val codec = net.codec<SnapshotPayload>({ b, v -> b.writeUtf(v.json, maxBytes) }, { b -> SnapshotPayload(b.readUtf(maxBytes), type) })
            return Channel(type, codec)
        }
    }
}

@PublishedApi
internal val snapshotJson = Json { ignoreUnknownKeys = true }

inline fun <reified T> SnapshotPayload.decode(): T? = runCatching { snapshotJson.decodeFromString<T>(json) }.getOrNull()
