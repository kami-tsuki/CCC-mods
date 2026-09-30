package kami.geology.command

import java.util.Locale
import kami.libs.ui.style.Format
import kami.libs.text.Phrase
import kami.geology.map.Heatmap
import kami.geology.world.WorldContext
import net.minecraft.core.BlockPos
import java.util.SplittableRandom
import kotlin.math.min

object Audit {
    private const val CELL = 4

    fun run(world: WorldContext, origin: BlockPos, samples: Int, claimChunks: Int, radius: Int): List<Phrase> {
        val settings = world.settings
        val ores = settings.ores
        val size = claimChunks * 16
        val cells = size / CELL
        val mask = (0 until min(ores.size, Heatmap.MAX_ORES)).fold(0L) { m, i -> m or (1L shl i) }
        val random = SplittableRandom(world.seed xor origin.asLong())
        val supply = Array(samples) { FloatArray(ores.size).also { row ->
            val x = Math.floorDiv(origin.x + random.nextInt(-radius, radius + 1), CELL) * CELL
            val z = Math.floorDiv(origin.z + random.nextInt(-radius, radius + 1), CELL) * CELL
            val query = Heatmap.Query(x, z, CELL, cells, cells, world.level.minBuildHeight, world.level.maxBuildHeight - 1)
            Heatmap.totals(world, query, mask)?.copyInto(row)
        } }

        val threshold = settings.general.auditThreshold
        val lines = ArrayList<Phrase>()
        lines += Phrase.of("kami_geology.audit.header", Phrase.value(samples), Phrase.value(claimChunks), Phrase.value(radius), Phrase.value(threshold))
        ores.forEachIndexed { i, ore ->
            val values = supply.map { it[i] }.sorted()
            val share = values.count { it >= threshold } * 100 / samples
            lines += Phrase.of("kami_geology.audit.ore", GeoText.orePhrase(ore.id).asValue(), percent(share), Phrase.value(short(values[samples / 2])), Phrase.value(short(values[(samples * 9 / 10).coerceAtMost(samples - 1)])))
        }
        val counts = supply.map { row -> row.count { it >= threshold } }
        lines += Phrase.of("kami_geology.audit.per_area", Phrase.value(Format.decimal(counts.average(), locale = Locale.ROOT)), percent(counts.count { it == 0 } * 100 / samples))
        val index = ores.withIndex().associate { it.value.id to it.index }
        settings.general.chains.forEach { (name, needs) ->
            val slots = needs.mapNotNull { index[it] }
            if (slots.size != needs.size) return@forEach
            val self = supply.count { row -> slots.all { row[it] >= threshold } } * 100 / samples
            lines += Phrase.of("kami_geology.audit.chain", Phrase.value(name), needs.joinToString(" + "), percent(self))
        }
        return lines
    }

    private fun percent(n: Int) = Phrase.of("kami_libs.unit.percent", n).asValue()

    private fun short(v: Float) = when {
        v >= 1_000_000f -> "%.1fM".format(v / 1_000_000f)
        v >= 1_000f -> "%.1fk".format(v / 1_000f)
        else -> "%.0f".format(v)
    }
}
