package kami.claims.client

import kami.claims.service.View
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import xaero.map.highlight.ChunkHighlighter

class KamiHighlighter : ChunkHighlighter(true) {
    private val sides = arrayOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)

    private fun name(dim: ResourceKey<Level>) = dim.location().toString()
    private fun on() = ClientClaims.prefs.overlay
    private fun entry(dim: ResourceKey<Level>, x: Int, z: Int) = if (on()) ClientClaims.at(name(dim), x, z) else null

    private fun pack(rgb: Int, alpha: Int) = ((rgb shr 16 and 255) shl 24) or ((rgb shr 8 and 255) shl 16) or ((rgb and 255) shl 8) or alpha

    private fun base(e: View.Entry) = ClientClaims.country(e)?.color ?: 0x888888

    private fun fill(e: View.Entry): Int = when {
        e.flags and View.MINE != 0 -> pack(0xFFD54F, 0x90)
        e.flags and View.CLAIMABLE != 0 -> pack(0x5CFF7A, 0x80)
        e.flags and (View.OWN or View.ALLY) != 0 -> pack(base(e), 0x58)
        else -> pack(base(e), 0x38)
    }

    private fun border(e: View.Entry): Int = if (e.flags and View.DEBT != 0) pack(0xFF3B3B, 0xE0) else pack(base(e), 0xD0)

    private fun describe(e: View.Entry): Component {
        val country = ClientClaims.country(e)?.name ?: "?"
        val type = ClientClaims.typeName(e)
        return Component.literal("§6$country" + (type?.let { " §7- $it" } ?: "") + if (e.flags and View.CLAIMABLE != 0) " §a(free plot)" else if (e.flags and View.MINE != 0) " §e(your plot)" else "")
    }

    override fun regionHasHighlights(dim: ResourceKey<Level>, regionX: Int, regionZ: Int) = on() && ClientClaims.regionHash(name(dim), regionX, regionZ) != 0

    override fun calculateRegionHash(dim: ResourceKey<Level>, regionX: Int, regionZ: Int) = if (on()) ClientClaims.regionHash(name(dim), regionX, regionZ) else 0

    override fun chunkIsHighlit(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int) = entry(dim, chunkX, chunkZ) != null

    override fun getColors(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int): IntArray? {
        val e = entry(dim, chunkX, chunkZ) ?: return null
        val centre = fill(e)
        val edge = border(e)
        return IntArray(5) { i ->
            if (i == 0) centre
            else ClientClaims.at(name(dim), chunkX + sides[i - 1].first, chunkZ + sides[i - 1].second).let { n -> if (n?.country == e.country) centre else edge }
        }
    }

    override fun getChunkHighlightSubtleTooltip(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int): Component? =
        entry(dim, chunkX, chunkZ)?.let { Component.literal(ClientClaims.country(it)?.name ?: "") }

    override fun getChunkHighlightBluntTooltip(dim: ResourceKey<Level>, chunkX: Int, chunkZ: Int): Component? =
        entry(dim, chunkX, chunkZ)?.let(::describe)

    override fun addMinimapBlockHighlightTooltips(list: MutableList<Component>, dim: ResourceKey<Level>, blockX: Int, blockZ: Int, width: Int) {
        entry(dim, blockX shr 4, blockZ shr 4)?.let { list.add(describe(it)) }
    }
}
