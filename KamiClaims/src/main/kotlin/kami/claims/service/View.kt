package kami.claims.service

import kami.claims.*

import kotlin.math.abs

object View {
    const val OWN = 1
    const val ALLY = 2
    const val CAPITAL = 4
    const val MINE = 8
    const val CLAIMABLE = 16
    const val DEBT = 32
    const val TAKEN = 64
    const val FREE = 128

    class Entry(val dim: Int, val x: Int, val z: Int, val country: Int, val type: Int, val flags: Int)
    class CountryView(val name: String, val color: Int, val relation: Int)
    class Payload(val rev: Int, val dims: List<String>, val types: List<String>, val countries: List<CountryView>, val entries: List<Entry>)

    fun color(c: Country) = if (c.color != 0) c.color and 0xFFFFFF else auto(c.id)

    fun auto(id: String): Int {
        val h = (abs(id.hashCode()) % 360) / 60f
        val v = 0.85f
        val p = v * 0.45f
        val f = h - h.toInt()
        val q = v * (1 - 0.55f * f)
        val t = v * (1 - 0.55f * (1 - f))
        val (r, g, b) = when (h.toInt() % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }

    fun relation(c: Country, viewer: String) = when (c.rank(viewer)) {
        null, Rank.BANISHED -> 0
        Rank.ALLIED -> 2
        else -> 1
    }

    fun privileged(c: Country, viewer: String) = c.members[viewer]?.let { it.rank >= Config.s.min(Cap.DETAILS) } == true

    fun flags(cl: Claim, c: Country, viewer: String): Int {
        val rel = relation(c, viewer)
        if (rel == 0) return 0
        var f = if (rel == 1) OWN else ALLY
        if (cl.capital) f = f or CAPITAL
        val rank = c.members[viewer]?.rank ?: return f
        if (cl.type == "residential") f = f or when {
            cl.owner == viewer -> MINE
            cl.owner == null && rank >= Config.s.min(Cap.PLOT) -> CLAIMABLE
            cl.owner != null -> TAKEN
            else -> 0
        }
        if (privileged(c, viewer)) {
            if (cl.debt > 0) f = f or DEBT
            if (cl.free) f = f or FREE
        }
        return f
    }

    fun build(viewer: String): Payload {
        val dims = Config.s.dimensions
        val types = Config.s.types.keys.toList()
        val index = HashMap<String, Int>()
        val countries = ArrayList<CountryView>()
        val entries = ArrayList<Entry>(Realm.data.claims.size)
        Realm.data.claims.forEach { cl ->
            val dim = dims.indexOf(cl.dim)
            val c = Realm.data.countries[cl.country]
            if (dim < 0 || c == null) return@forEach
            val idx = index.getOrPut(c.id) { countries += CountryView(c.name, color(c), relation(c, viewer)); countries.size - 1 }
            val flags = flags(cl, c, viewer)
            entries += Entry(dim, cl.x, cl.z, idx, if (flags == 0) -1 else types.indexOf(cl.type), flags)
        }
        return Payload(Realm.rev, dims, types, countries, entries)
    }
}
