package kami.economy.villager

import kami.economy.Config
import kami.economy.Fill
import kami.economy.LOG
import kami.economy.Market
import kami.economy.Traded
import kami.economy.economy.EnchantBooks
import kami.economy.economy.Stocks
import kami.economy.economy.Variants
import kami.economy.now
import kami.economy.quest.DishGraph
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.db.Table
import kotlinx.serialization.encodeToString
import net.minecraft.world.item.ItemStack
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class CourseState { SEED, LIVE, LOCKED }

class VCourse(var course: Double, var floor: Double, var state: CourseState, var fills: Long = 0, var last: Long = now(), val samples: MutableList<Double> = mutableListOf(), val lot: Int = 1)

object VillagerCourses {
    private const val SAMPLES = 15
    private const val EPS = 1e-6
    private val table = Table<Map<String, VCourse>>("eco_villager", listOf("spec"), listOf("course", "floor", "state", "fills", "last", "samples", "lot")) { m ->
        m.map { (k, v) -> listOf(k, v.course, v.floor, v.state.name, v.fills, v.last, Db.json.encodeToString(v.samples), v.lot) }
    }
    private val entries = HashMap<String, VCourse>()
    private var attached = false

    fun load() {
        entries.clear()
        entries += Db.read {
            query("SELECT * FROM eco_villager") { r ->
                runCatching { r.string("spec") to VCourse(r.double("course"), r.double("floor"), CourseState.valueOf(r.string("state")), r.long("fills"), r.long("last"), Db.json.decodeFromString(r.string("samples")), r.int("lot")) }
                    .onFailure { LOG.warn("Skipped a broken villager price row: {}", it.message) }.getOrNull()
            }.filterNotNull()
        }
        table.prime(entries)
        attached = true
    }

    fun unload() { attached = false }

    internal fun changes(): (Session.() -> Unit)? = if (attached) table.sync(entries) else null

    fun all(): Map<String, VCourse> = entries

    private val s get() = Config.s.villagers

    private fun toCore(id: String, amount: Double): Double {
        val v = Variants.of(id)
        return amount * (v?.mul ?: 1) / (v?.div ?: 1)
    }

    fun marketUnit(id: String): Double? {
        val core = Variants.core(id)
        val c = Stocks.course(core).takeIf { it > 0 } ?: return null
        return toCore(id, c / Config.s.lotOf(core))
    }

    fun emeraldUnit() = Stocks.good(Specs.EMERALD)?.let { marketUnit(Specs.EMERALD) } ?: s.emeraldPrice.takeIf { it > 0 }

    fun lot(item: String) = entries[item]?.lot?.takeIf { it > 1 }

    fun listed(item: String) = item in entries

    private fun current(e: VCourse, t: Long = now()) = if (e.state == CourseState.SEED) VillagerMath.decayed(e.course, e.floor, e.last, t, s.decayPct) else e.course

    fun unit(spec: Spec, side: Side, emeraldsPerUnit: Double?): Double? {
        if (spec.enchants.isEmpty()) return single(spec.key, spec.market, side, emeraldsPerUnit)
        val base = single(spec.base, true, side, null) ?: 0.0
        return spec.enchants.fold(base) { sum, e -> sum + (single(e.book, true, side, null, VillagerMath.enchantEmeralds(e.treasure)) ?: return null) * EnchantBooks.factor(e.level) }
    }

    private fun single(key: String, market: Boolean, side: Side, emeralds: Double?, fallback: Double? = null): Double? {
        val core = if (market) Variants.core(key) else key
        val ratio = if (market) toCore(key, 1.0) else 1.0
        val e = entries[core]
        if (e?.state == CourseState.LOCKED) return e.course * ratio
        val listed = market && Stocks.good(core) == null
        if (e?.state == CourseState.SEED && (listed || !market)) {
            emeralds?.let { sample(e, it / ratio, side) }
            return list(core, e, listed) * ratio
        }
        if (market) marketUnit(key)?.let { return it }
        if (e != null) return current(e) * ratio
        val epu = emeralds?.div(ratio) ?: fallback
        val seed = VillagerMath.seed(if (market) RecipeSeed.of(core) else null, epu?.let { u -> emeraldUnit()?.let { u * it } }, side, s) ?: return null
        val fresh = VCourse(seed, seed / VillagerMath.seedMul(side, s), CourseState.SEED, samples = listOfNotNull(epu).toMutableList(), lot = if (listed) lotFor(core, seed) else 1)
        entries[core] = fresh
        return list(core, fresh, listed) * ratio
    }

    private fun list(core: String, e: VCourse, listed: Boolean): Double {
        val u = current(e)
        if (listed) Market.book(core).takeIf { abs(it.course - u * Config.s.lotOf(core)) > EPS }?.let {
            it.course = u * Config.s.lotOf(core)
            Market.dirty = true
        }
        return u
    }

    private fun lotFor(core: String, unit: Double) =
        if (core in Config.s.lots || Market.peek(core)?.let { it.sells.isNotEmpty() || it.buys.isNotEmpty() } == true) Config.s.lotOf(core) else DishGraph.lot(unit)

    private fun sample(e: VCourse, epu: Double, side: Side) {
        if (e.state != CourseState.SEED || e.fills > 0 || e.samples.size >= SAMPLES || epu in e.samples) return
        e.samples += epu
        val raw = (VillagerMath.median(e.samples) ?: return) * (emeraldUnit() ?: return)
        val t = now()
        e.course = minOf(current(e, t), raw * VillagerMath.seedMul(side, s))
        e.floor = minOf(e.floor, raw)
        e.last = t
    }

    fun fill(spec: Spec, unitPrice: Double, count: Int, side: Side) {
        if (count <= 0 || unitPrice <= 0) return
        val t = now()
        if (spec.market) return plain(spec.key, unitPrice, count.toDouble(), side, t)
        if (spec.enchants.isEmpty()) {
            entries[spec.key]?.let { move(it, unitPrice, count.toDouble(), t) }
            return
        }
        val base = single(spec.base, true, side, null) ?: 0.0
        val parts = spec.enchants.mapNotNull { e -> single(e.book, true, side, null)?.let { e to it * EnchantBooks.factor(e.level) } }
        val total = parts.sumOf { it.second }
        if (total <= 0) return
        val rest = (unitPrice - base).coerceAtLeast(0.0)
        parts.forEach { (e, v) -> EnchantBooks.factor(e.level).let { f -> plain(e.book, rest * v / total / f, count * f, side, t) } }
    }

    private fun plain(id: String, unitPrice: Double, count: Double, side: Side, t: Long) {
        val core = Variants.core(id)
        val coreCount = toCore(id, count)
        val coreUnit = unitPrice * count / coreCount
        val e = entries[core]
        val c = Stocks.course(core)
        if (c <= 0 || e?.state == CourseState.LOCKED) {
            e?.let { move(it, coreUnit, coreCount, t) }
            return
        }
        val lot = Config.s.lotOf(core)
        val book = Market.book(core)
        book.course = Stocks.afterPlayer(core, c, coreUnit * lot, s.impact * coreCount / lot)
        e?.let {
            it.state = CourseState.LIVE
            it.course = book.course / lot
            it.fills++
            it.last = t
        }
        Market.data.traded.getOrPut(core) { Traded() }.apply { if (side == Side.SELL) bought += coreCount.roundToLong() else sold += coreCount.roundToLong(); last = t }
        book.lastFill = (coreUnit * lot).toInt()
        book.recentFills += Fill(book.lastFill, coreCount.roundToInt().coerceAtLeast(1), t)
        Market.dirty = true
    }

    private fun move(e: VCourse, unitPrice: Double, weight: Double, t: Long) {
        e.fills++
        if (e.state != CourseState.LOCKED) {
            e.course = VillagerMath.pull(current(e, t), unitPrice, s.impact * weight, Config.s.courseDepth, Config.s.courseClip)
            e.state = CourseState.LIVE
        }
        e.last = t
    }

    fun auctioned(stack: ItemStack, price: Int) {
        if (stack.isEmpty || price <= 0) return
        val spec = Specs.of(stack)
        if (!spec.market) fill(spec, price.toDouble() / stack.count, stack.count, Side.SELL)
    }

    fun set(key: String, course: Double, lock: Boolean) {
        val e = entries.getOrPut(key) { VCourse(course, course, CourseState.LIVE) }
        e.course = course
        e.state = if (lock) CourseState.LOCKED else CourseState.LIVE
        e.last = now()
    }

    fun unlock(key: String) {
        val e = entries[key]?.takeIf { it.state == CourseState.LOCKED } ?: return
        e.state = if (e.fills > 0) CourseState.LIVE else CourseState.SEED
        e.last = now()
    }

    fun reset(key: String) { entries.remove(key) }
}
