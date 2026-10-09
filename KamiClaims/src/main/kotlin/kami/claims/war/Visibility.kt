package kami.claims.war

import kami.claims.Realm
import java.util.UUID

enum class Seen { HIDDEN, RANGE, ALWAYS }

object Visibility {
    fun see(viewerOp: Boolean, same: Boolean, enemies: Boolean) = when {
        viewerOp || same -> Seen.ALWAYS
        enemies -> Seen.HIDDEN
        else -> Seen.RANGE
    }

    fun warring(country: String) = Realm.data.wars.any { it.active && country in it.countries }
}

class Pos(val id: UUID, val country: String?, val dim: String, val x: Double, val y: Double, val z: Double)

class Entry(val id: UUID, val dim: String, val x: Int, val y: Int, val z: Int)

class Selection(val entries: List<Entry>, val friendly: Set<UUID>, val war: Boolean = false) {
    val empty get() = entries.isEmpty() && friendly.isEmpty() && !war
}

object PositionFilter {
    const val CAP = 200
    const val MOVE = 2

    fun select(
        viewer: Pos, opAll: Boolean, targets: Collection<Pos>, share: Boolean, hideEnemies: Boolean,
        enemies: (String, String) -> Boolean, family: (String, String) -> Boolean, cap: Int = CAP, warring: Boolean = true
    ): Selection {
        val own = viewer.country
        val shown = ArrayList<Pair<Double, Entry>>()
        val friendly = ArrayList<Pair<Double, UUID>>()
        for (t in targets) {
            if (t.id == viewer.id) continue
            val foe = own != null && t.country != null && enemies(own, t.country)
            val kin = !foe && own != null && t.country != null && (own == t.country || family(own, t.country))
            val seen = Visibility.see(opAll, share && kin, foe)
            val dx = t.x - viewer.x
            val dz = t.z - viewer.z
            val dist = dx * dx + dz * dz
            if (kin) friendly += dist to t.id
            if (seen != Seen.ALWAYS) continue
            shown += dist to Entry(t.id, t.dim, Math.floor(t.x).toInt(), Math.floor(t.y).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()), Math.floor(t.z).toInt())
        }
        val war = hideEnemies && !opAll && own != null && warring
        return Selection(shown.sortedBy { it.first }.take(cap).map { it.second }, if (war) friendly.sortedBy { it.first }.take(cap).mapTo(LinkedHashSet()) { it.second } else emptySet(), war)
    }

    fun changed(prev: Selection?, next: Selection): Boolean {
        if (prev == null) return !next.empty
        if (prev.war != next.war || prev.friendly != next.friendly) return true
        if (prev.entries.size != next.entries.size) return true
        val before = prev.entries.associateBy { it.id }
        return next.entries.any { e ->
            val b = before[e.id] ?: return@any true
            b.dim != e.dim || Math.abs(b.x - e.x) >= MOVE || Math.abs(b.y - e.y) >= MOVE || Math.abs(b.z - e.z) >= MOVE
        }
    }
}
