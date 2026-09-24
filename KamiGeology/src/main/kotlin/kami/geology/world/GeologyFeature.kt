package kami.geology.world

import kami.geology.KamiGeology
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import java.util.concurrent.atomic.AtomicInteger

class GeologyFeature : Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
    private val failures = AtomicInteger()

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val world = Worlds.of(level.level) ?: return false
        val chunk = level.getChunk(context.origin())
        val chunkPos = chunk.pos
        for (ore in world.settings.ores) {
            try {
                if (ore.deposit != null) {
                    world.sitesIn(ore, chunkPos.minBlockX, chunkPos.minBlockZ, chunkPos.maxBlockX, chunkPos.maxBlockZ).forEach { site ->
                        Painter.deposit(level, chunk, site, world.noise)
                        Painter.outcrop(chunk, site, world.noise)
                    }
                }
                Painter.scatter(level, chunk, ore, context.random(), world)
            } catch (e: Exception) {
                if (failures.incrementAndGet() <= MAX_LOGGED) KamiGeology.LOG.error("Ore '{}' failed at chunk {}", ore.id, chunkPos, e)
            }
        }
        return true
    }

    private companion object {
        const val MAX_LOGGED = 5
    }
}
