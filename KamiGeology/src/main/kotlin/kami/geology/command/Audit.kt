package kami.geology.command

import kami.geology.map.Heatmap
import kami.geology.world.WorldContext
import net.minecraft.core.BlockPos
import java.util.SplittableRandom
import kotlin.math.min

object Audit {
    private const val CELL = 4

    fun run(world: WorldContext, origin: BlockPos, samples: Int, claimChunks: Int, radius: Int): List<String> {
        val settings = world.settings
        val ores = settings.ores
        val size = claimChunks * 16
        val cells = size / CELL
        val mask = (0 until min(ores.size, Heatmap.MAX_ORES)).fold(0L) { m, i -> m or (1L shl i) }
        val random = SplittableRandom(world.seed xor origin.asLong())
        val supply = Array(samples) { FloatArray(ores.size) }
        for (s in 0 until samples) {
            val x = Math.floorDiv(origin.x + random.nextInt(-radius, radius + 1), CELL) * CELL
            val z = Math.floorDiv(origin.z + random.nextInt(-radius, radius + 1), CELL) * CELL
            val query = Heatmap.Query(x, z, CELL, cells, cells, world.level.minBuildHeight, world.level.maxBuildHeight - 1)
            Heatmap.totals(world, query, mask)?.copyInto(supply[s])
        }

        val threshold = settings.general.auditThreshold
        val lines = ArrayList<String>()
        lines += "Audit: $samples areas of ${claimChunks}x$claimChunks chunks within $radius blocks, supply = at least $threshold ore blocks"
        ores.forEachIndexed { i, ore ->
            val values = supply.map { it[i] }.sorted()
            val share = values.count { it >= threshold } * 100 / samples
            lines += "${ore.id}: $share% of areas have supply, median ${short(values[samples / 2])}, top 10% from ${short(values[(samples * 9 / 10).coerceAtMost(samples - 1)])}"
        }
        val counts = supply.map { row -> row.count { it >= threshold } }
        lines += "Ores per area: average ${"%.1f".format(counts.average())}, none in ${counts.count { it == 0 } * 100 / samples}% of areas"
        val index = ores.withIndex().associate { it.value.id to it.index }
        settings.general.chains.forEach { (name, needs) ->
            val slots = needs.mapNotNull { index[it] }
            if (slots.size != needs.size) return@forEach
            val self = supply.count { row -> slots.all { row[it] >= threshold } } * 100 / samples
            lines += "chain $name (${needs.joinToString("+")}): $self% of areas can supply it alone"
        }
        return lines
    }

    private fun short(v: Float) = when {
        v >= 1_000_000f -> "%.1fM".format(v / 1_000_000f)
        v >= 1_000f -> "%.1fk".format(v / 1_000f)
        else -> "%.0f".format(v)
    }
}
