package kami.geology.world

import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap
import kami.geology.config.ConfigStore
import kami.geology.config.Settings
import kami.geology.net.HardnessSync
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.server.ServerLifecycleHooks

object Hardness {
    @Volatile
    private var table = Reference2FloatOpenHashMap<Block>().apply { defaultReturnValue(1f) }

    @JvmStatic
    fun scale(block: Block, speed: Float): Float = if (speed > 0f) speed * table.getFloat(block) else speed

    fun apply(factors: Map<String, Float>) {
        table = Reference2FloatOpenHashMap<Block>(factors.size).apply {
            defaultReturnValue(1f)
            factors.forEach { (id, factor) ->
                ResourceLocation.tryParse(id)?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }?.let { put(it, factor) }
            }
        }
    }

    fun clear() = apply(emptyMap())

    fun update(settings: Settings?) {
        val factors = factors(settings)
        apply(factors)
        ServerLifecycleHooks.getCurrentServer()?.playerList?.players?.forEach { send(it, factors) }
    }

    fun send(player: ServerPlayer, factors: Map<String, Float> = factors(ConfigStore.current)) {
        if (player.connection.hasChannel(HardnessSync.TYPE)) PacketDistributor.sendToPlayer(player, HardnessSync(factors))
    }

    private fun factors(settings: Settings?): Map<String, Float> {
        val mining = settings?.general?.mining?.takeIf { it.enabled } ?: return emptyMap()
        val stone = mining.stone.toFloat()
        val deep = mining.deepslate.toFloat()
        val out = LinkedHashMap<Block, Float>()
        settings.ores.forEach { ore ->
            if (ore.deepslate.block != ore.stone.block) out[ore.deepslate.block] = deep
            out.putIfAbsent(ore.stone.block, stone)
            ore.hosts.forEach { (host, state) -> out.putIfAbsent(state.block, if (ore.deepslateRule.test(host.defaultBlockState())) deep else stone) }
            ore.core?.let { out.putIfAbsent(it.block, stone) }
        }
        return out.entries.associate { BuiltInRegistries.BLOCK.getKey(it.key).toString() to it.value }
    }
}
