package kami.claims.service

import kami.claims.*
import kami.claims.war.Fame
import kami.claims.war.SafeChunks
import kami.claims.war.Wars
import kotlin.math.roundToInt

object View {
    const val OWN = 1
    const val ALLY = 2
    const val CAPITAL = 4
    const val MINE = 8
    const val CLAIMABLE = 16
    const val DEBT = 32
    const val TAKEN = 64
    const val FREE = 128
    const val OFFER = 256
    const val MOVING = 512
    const val ASSIGNED = 1024
    const val SAFE = 2048
    const val STAKED = 4096
    const val ENEMY = 8192
    const val WAR_ACTIVE = 1
    const val WAR_FAIRPLAY = 2

    class Entry(val dim: Int, val x: Int, val z: Int, val country: Int, val type: Int, val flags: Int)
    class CountryView(val name: String, val color: Int, val relation: Int, val flag: Int = 0, val secondary: Int = 0xFFFFFF, val reputation: Int = 0, val fame: Double? = null) {
        val pattern get() = flag shr 8
        val emblem get() = flag and 0xFF
    }
    class Reserved(val dim: Int, val x: Int, val z: Int)
    class Payload(val rev: Int, val dims: List<String>, val types: List<String>, val countries: List<CountryView>, val entries: List<Entry>, val reserved: List<Reserved> = emptyList(), val war: Int = 0)

    fun color(c: Country) = if (c.color != 0) c.color and 0xFFFFFF else auto(c.id)

    fun auto(id: String): Int {
        val h = Math.floorMod(id.hashCode(), 360) / 60f
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

    const val REL_NONE = 0
    const val REL_MEMBER = 1
    const val REL_ALLY = 2
    const val REL_FAMILY = 3
    const val REL_BANISHED = 4
    const val REL_ENEMY = 5

    fun mapRelation(c: Country, viewer: String): Int {
        val home = Realm.of(viewer)
        return when {
            home?.id == c.id -> REL_MEMBER
            home != null && (c.parent == home.id || home.parent == c.id || (home.parent != null && home.parent == c.parent)) -> REL_FAMILY
            c.outsiders[viewer] == Rank.BANISHED -> REL_BANISHED
            c.outsiders[viewer] == Rank.ALLIED -> REL_ALLY
            else -> REL_NONE
        }
    }

    fun relation(c: Country, viewer: String) = when (c.rank(viewer)) {
        null, Rank.BANISHED -> 0
        Rank.ALLIED -> 2
        else -> 1
    }

    fun privileged(c: Country, viewer: String) = Config.s.can(c.members[viewer]?.rank, Cap.DETAILS)

    fun flags(cl: Claim, c: Country, viewer: String, held: Collection<Key> = Realm.held(c.id)[viewer].orEmpty(), scope: (String) -> Boolean = Scope.of(viewer, false), enemy: Boolean = false): Int {
        if (enemy) return ENEMY
        val inScope = scope(c.id)
        val rel = relation(c, viewer)
        val member = c.members[viewer]
        var f = if (rel == 0) 0 else if (rel == 1) OWN else ALLY
        if (cl.capital && inScope) f = f or CAPITAL
        if (member != null && viewer in cl.workers && Work.holds(member, cl.type)) f = f or ASSIGNED
        if (cl.plot) f = f or plot(cl, c, viewer, held, inScope)
        if (rel == 0) return f
        if (privileged(c, viewer)) {
            if (cl.debt > 0) f = f or DEBT
            if (cl.free) f = f or FREE
        }
        return f
    }

    private fun plot(cl: Claim, c: Country, viewer: String, held: Collection<Key>, inScope: Boolean): Int {
        if (!Housing.tenanted(cl)) {
            return (if (Config.s.can(c.members[viewer]?.rank, Cap.PLOT)) CLAIMABLE else 0) or (if (Housing.blocker(c, cl, viewer, held) == null) OFFER else 0)
        }
        return when {
            cl.owner == viewer -> MINE or (if (cl.state == Tenancy.MOVING_OUT) MOVING else 0)
            cl.roles[viewer] == Role.HOUSEHOLD -> MINE
            inScope -> TAKEN
            else -> 0
        }
    }

    private class Base(
        val rev: Int, val cfg: Any, val dims: List<String>, val types: List<String>, val typeIndex: Map<String, Int>,
        val claims: List<Claim>, val dimOf: IntArray, val countryOf: IntArray, val countries: List<Country>, val views: List<CountryView>, val reserved: List<Pair<String, Reserved>>,
        val held: Map<String, Map<String, List<Key>>>, val safe: List<Set<Key>>, val stakes: List<Pair<Set<String>, Set<Key>>>
    )

    private var base: Base? = null

    private fun base(): Base {
        base?.takeIf { it.rev == Realm.claimRev && it.cfg === Config.s }?.let { return it }
        val dims = Config.s.dimensions
        val types = Config.s.types.keys.toList()
        val dimIndex = dims.withIndex().associate { it.value to it.index }
        val typeIndex = types.withIndex().associate { it.value to it.index }
        val index = HashMap<String, Int>()
        val countries = ArrayList<Country>()
        val views = ArrayList<CountryView>()
        val claims = ArrayList<Claim>(Realm.data.claims.size)
        val dimOf = ArrayList<Int>(Realm.data.claims.size)
        val countryOf = ArrayList<Int>(Realm.data.claims.size)
        val fame by lazy { Fame.scores() }
        val held = HashMap<String, HashMap<String, ArrayList<Key>>>()
        Realm.data.claims.forEach { cl -> cl.owner?.let { held.getOrPut(it) { HashMap() }.getOrPut(cl.country) { ArrayList() } += cl.key } }
        Realm.data.claims.forEach { cl ->
            val dim = dimIndex[cl.dim] ?: return@forEach
            val c = Realm.data.countries[cl.country] ?: return@forEach
            val idx = index.getOrPut(c.id) {
                countries += c
                views += CountryView(c.name, color(c), REL_NONE, (c.flag.pattern shl 8) or c.flag.emblem, c.flag.secondary, c.reputation.roundToInt(), fame[c.id])
                countries.size - 1
            }
            claims += cl
            dimOf += dim
            countryOf += idx
        }
        val reserved = Realm.data.reserves.filter { it.until > kami.claims.now() }.mapNotNull { r -> dimIndex[r.dim]?.let { r.country to Reserved(it, r.x, r.z) } }
        val safe = countries.map { c -> SafeChunks.active(c).map { it.key }.toSet() }
        val stakes = Realm.data.wars.filter { it.active && it.stakes.chunks.isNotEmpty() }.map { it.countries.toSet() to it.stakes.chunks.map { r -> r.key }.toSet() }
        return Base(Realm.claimRev, Config.s, dims, types, typeIndex, claims, dimOf.toIntArray(), countryOf.toIntArray(), countries, views, reserved, held, safe, stakes).also { base = it }
    }

    fun build(viewer: String, op: Boolean = false): Payload {
        val b = base()
        val held = b.held[viewer].orEmpty()
        val home = Realm.of(viewer)
        val scope = Scope.of(viewer, op)
        val foes = if (op || home == null) emptySet() else b.countries.filter { Wars.enemies(home.id, it.id) }.map { it.id }.toSet()
        val staked = home?.let { h -> b.stakes.filter { h.id in it.first }.flatMap { it.second }.toSet() }.orEmpty()
        val countries = b.countries.mapIndexed { i, c -> b.views[i].let { CountryView(it.name, it.color, if (c.id in foes) REL_ENEMY else mapRelation(c, viewer), it.flag, it.secondary, it.reputation, it.fame) } }
        val entries = ArrayList<Entry>(b.claims.size)
        b.claims.forEachIndexed { i, cl ->
            val c = b.countries[b.countryOf[i]]
            val enemy = c.id in foes
            val inScope = scope(c.id)
            var flags = flags(cl, c, viewer, held[c.id].orEmpty(), scope, enemy)
            if (inScope && cl.key in b.safe[b.countryOf[i]]) flags = flags or SAFE
            if ((inScope || enemy) && cl.key in staked) flags = flags or STAKED
            val type = if (!enemy && (inScope || flags and MINE != 0)) b.typeIndex[cl.type] ?: -1 else -1
            entries += Entry(b.dimOf[i], cl.x, cl.z, b.countryOf[i], type, flags)
        }
        val reserved = b.reserved.filter { scope(it.first) }.map { it.second }
        val war = if (home != null && Wars.involved(home)) WAR_ACTIVE or (if (Config.s.visibility.fairPlay && !op) WAR_FAIRPLAY else 0) else 0
        return Payload(b.rev, b.dims, b.types, countries, entries, reserved, war)
    }

    fun signature(d: Payload): Int {
        var h = 17
        d.countries.forEach { h = 31 * h + it.name.hashCode(); h = 31 * h + it.color; h = 31 * h + it.relation; h = 31 * h + it.flag; h = 31 * h + it.secondary; h = 31 * h + it.reputation; h = 31 * h + it.fame.hashCode() }
        d.entries.forEach { h = 31 * h + it.dim; h = 31 * h + it.x; h = 31 * h + it.z; h = 31 * h + it.country; h = 31 * h + it.type; h = 31 * h + it.flags }
        d.reserved.forEach { h = 31 * h + it.dim; h = 31 * h + it.x; h = 31 * h + it.z }
        h = 31 * h + d.war
        return 31 * h + d.dims.hashCode() * 7 + d.types.hashCode()
    }
}
