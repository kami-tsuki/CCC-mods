package kami.libs.net

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

object Packets {
    fun id(modId: String, path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(modId, path)

    fun <T : CustomPacketPayload> codec(
        write: (FriendlyByteBuf, T) -> Unit,
        read: (FriendlyByteBuf) -> T
    ): StreamCodec<RegistryFriendlyByteBuf, T> =
        StreamCodec.of<RegistryFriendlyByteBuf, T>({ buf, v -> write(buf, v) }, { buf -> read(buf) })
}
