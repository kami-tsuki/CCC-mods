package kami.geology.net

import kami.geology.KamiGeology
import kami.geology.client.Guide
import kami.geology.config.ConfigStore
import kami.geology.config.Ore
import kami.libs.net.Packets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.neoforged.neoforge.server.ServerLifecycleHooks

@Serializable
data class GuideOre(
    val id: String,
    val blocks: List<String>,
    val shape: String? = null,
    val spacing: Int = 0,
    val chance: Double = 0.0,
    val height: List<Int> = emptyList(),
    val provinces: Map<String, Double> = emptyMap(),
    val anchor: String? = null,
    val anchorChance: Double = 0.0,
    val halo: List<String> = emptyList(),
    val core: String? = null,
    val coreChance: Double = 0.0,
    val scatter: Double = 0.0,
    val scatterHeight: List<Int> = emptyList()
)

@Serializable
class GuideData(val dimensions: List<String>, val ores: List<GuideOre>)

class OreGuide(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        private const val MAX_JSON = 262144
        private val packets = Packets.forMod(KamiGeology.ID)
        val TYPE = CustomPacketPayload.Type<OreGuide>(packets.id("ore_guide"))
        val CODEC = packets.codec<OreGuide>(
            { buf: FriendlyByteBuf, v -> buf.writeUtf(v.json, MAX_JSON) },
            { buf -> OreGuide(buf.readUtf(MAX_JSON)) }
        )
    }
}

object GuideSync {
    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(OreGuide.TYPE, OreGuide.CODEC) { payload, _ -> Guide.receive(payload.json) }
    }

    fun guide(): GuideData {
        val settings = ConfigStore.current ?: return GuideData(emptyList(), emptyList())
        return GuideData(settings.general.dimensions, settings.ores.map(::entry))
    }

    fun send(players: Collection<ServerPlayer>) {
        val payload = OreGuide(Json.encodeToString(guide()))
        players.filter { it.connection.hasChannel(OreGuide.TYPE) }.forEach { PacketDistributor.sendToPlayer(it, payload) }
    }

    fun sendAll() = ServerLifecycleHooks.getCurrentServer()?.playerList?.players?.let(::send)

    private fun id(block: Block) = BuiltInRegistries.BLOCK.getKey(block).toString()

    private fun entry(ore: Ore): GuideOre {
        val deposit = ore.deposit
        val anchor = deposit?.anchor
        return GuideOre(
            ore.id,
            (listOf(ore.stone.block, ore.deepslate.block) + ore.hosts.values.map { it.block }).map(::id).distinct(),
            deposit?.shape?.kind?.name?.lowercase(),
            deposit?.spacing ?: 0,
            deposit?.chance ?: 0.0,
            deposit?.let { it.limit ?: it.height }.orEmpty(),
            ore.config.provinces,
            anchor?.ore,
            anchor?.chance ?: 0.0,
            ore.config.halo?.blocks?.sortedByDescending { it.weight }?.map { it.block }.orEmpty(),
            ore.core?.let { id(it.block) },
            ore.config.core?.chance ?: 0.0,
            ore.scatter?.perChunk ?: 0.0,
            ore.scatter?.height.orEmpty()
        )
    }
}
