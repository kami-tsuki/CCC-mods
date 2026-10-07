package kami.libs.net

import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.ModList
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

object VersionCheck : CustomPacketPayload {
    private val TYPE = CustomPacketPayload.Type<VersionCheck>(ResourceLocation.fromNamespaceAndPath("kami_libs", "version"))

    override fun type() = TYPE

    fun summary(): String {
        val mods = ModList.get().mods.filter { it.modId.startsWith("kami_") }.sortedBy { it.modId }.map { it.modId.removePrefix("kami_") to it.version.toString() }
        val versions = mods.map { it.second }.distinct()
        return if (versions.size == 1) "Kami ${versions[0]} (${mods.joinToString { it.first }})" else "Kami " + mods.joinToString { "${it.first} ${it.second}" }
    }

    fun register(e: RegisterPayloadHandlersEvent) {
        e.registrar(summary()).playToClient(TYPE, StreamCodec.unit(this)) { _, _ -> }
    }
}
