package kami.claims.client.xaero

import kami.claims.service.View
import kami.libs.xaero.ChunkLayer
import kami.libs.xaero.ChunkPaint
import net.minecraft.network.chat.Component

class ClaimsChunkLayer(private val data: ClaimData, private val style: ClaimStyle) : ChunkLayer {
    override val id = "claims"
    override val toggle = ""
    override val order = 10

    private fun fill(e: View.Entry, base: Int): Int = when {
        e.flags and View.ENEMY != 0 -> if (style.tint()) argb(WAR_RED, 0x70) else argb(base, 0x38)
        e.flags and View.MINE != 0 -> argb(GOLD, 0x90)
        e.flags and (View.CLAIMABLE or View.OFFER) != 0 -> argb(CLAIMABLE_GREEN, 0x80)
        e.flags and (View.OWN or View.ALLY) != 0 -> argb(base, 0x58)
        e.flags and View.TAKEN != 0 -> argb(base, 0x28)
        else -> argb(base, 0x38)
    }

    private fun edge(e: View.Entry, base: Int): Int = when {
        e.flags and View.CAPITAL != 0 -> argb(0xFFFFFF, 0xFF)
        e.flags and View.DEBT != 0 -> argb(WAR_RED, 0xE0)
        else -> argb(base, 0xD0)
    }

    override fun regionHash(dim: String, rx: Int, rz: Int): Int {
        if (!data.enabled) return 0
        val h = data.regionHash(dim, rx, rz)
        if (h == 0) return 0
        return (h * 31 + style.mode().hashCode()) * 31 + if (style.tint()) 1 else 0
    }

    override fun paint(dim: String, cx: Int, cz: Int): ChunkPaint? {
        if (!data.enabled) return null
        val e = data.at(dim, cx, cz)
        if (e == null) {
            if (!data.reserved(dim, cx, cz)) return null
            val hatch = argb(0x66CCFF, if ((cx + cz) and 1 == 0) 0x60 else 0x18)
            return ChunkPaint(hatch, hatch, hatch, hatch, hatch)
        }
        val base = style.base(data, e)
        val centre = fill(e, base)
        val edge = edge(e, base)
        fun side(dx: Int, dz: Int) = if (data.at(dim, cx + dx, cz + dz)?.country == e.country) centre else edge
        return ChunkPaint(centre, side(0, -1), side(1, 0), side(0, 1), side(-1, 0))
    }

    override fun tooltip(dim: String, cx: Int, cz: Int, blunt: Boolean): Component? {
        if (!data.enabled) return null
        val e = data.at(dim, cx, cz) ?: return null
        val name = data.country(e)?.name ?: "?"
        if (!blunt) return Component.literal(name)
        val out = Component.literal(name).withColor(0xFFAA00)
        data.typeName(e)?.let { out.append(Component.literal(" - " + style.typeLabel(it)).withColor(0xAAAAAA)) }
        if (e.flags and View.CLAIMABLE != 0) out.append(Component.literal(" (").append(Component.translatable("kami_claims.map.chunk.claimable")).append(")").withColor(0x55FF55))
        else if (e.flags and View.MINE != 0) out.append(Component.literal(" (").append(Component.translatable("kami_claims.map.chunk.mine")).append(")").withColor(0xFFFF55))
        return out
    }
}

class WarChunkLayer(private val data: ClaimData) : ChunkLayer {
    override val id = "war"
    override val toggle = "claims.war"
    override val order = 15

    private fun kind(e: View.Entry) = when {
        e.flags and View.STAKED != 0 -> View.STAKED
        e.flags and View.ENEMY != 0 -> View.ENEMY
        e.flags and View.SAFE != 0 -> View.SAFE
        else -> 0
    }

    private fun fill(kind: Int) = when (kind) {
        View.STAKED -> argb(WAR_ORANGE, 0x70)
        View.SAFE -> argb(SAFE_GREEN, 0x18)
        else -> 0
    }

    private fun edge(kind: Int) = when (kind) {
        View.STAKED -> argb(WAR_ORANGE, 0xFF)
        View.ENEMY -> argb(WAR_RED, 0xFF)
        else -> argb(SAFE_GREEN, 0xFF)
    }

    override fun regionHash(dim: String, rx: Int, rz: Int): Int = if (!data.enabled) 0 else data.regionHash(dim, rx, rz).let { if (it == 0) 0 else it * 17 + 3 }

    override fun paint(dim: String, cx: Int, cz: Int): ChunkPaint? {
        if (!data.enabled) return null
        val e = data.at(dim, cx, cz) ?: return null
        val kind = kind(e)
        if (kind == 0) return null
        val centre = fill(kind)
        val edge = edge(kind)
        fun side(dx: Int, dz: Int) = if (data.at(dim, cx + dx, cz + dz)?.let(::kind) == kind) centre else edge
        return ChunkPaint(centre, side(0, -1), side(1, 0), side(0, 1), side(-1, 0))
    }

    override fun tooltip(dim: String, cx: Int, cz: Int, blunt: Boolean): Component? {
        if (!data.enabled || !blunt) return null
        val e = data.at(dim, cx, cz) ?: return null
        return when (kind(e)) {
            View.STAKED -> Component.translatable("kami_claims.xaero.war.staked").withColor(WAR_ORANGE)
            View.ENEMY -> Component.translatable("kami_claims.xaero.war.enemy").withColor(WAR_RED)
            View.SAFE -> Component.translatable("kami_claims.xaero.war.safe").withColor(SAFE_GREEN)
            else -> null
        }
    }
}
