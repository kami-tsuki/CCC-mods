package kami.economy.quest

import kami.economy.Config
import kami.economy.LOG
import kami.economy.Market
import kami.economy.Order
import kami.economy.QuestSettings
import kami.economy.economy.Blacklist
import kami.economy.economy.FillLinePlan
import kami.economy.economy.Matching
import kami.economy.economy.Stocks
import kami.economy.economy.Trade
import kami.economy.economy.TradeStatus
import kami.economy.economy.Variants
import kami.economy.now
import kami.libs.claims.Boost
import kami.libs.claims.ClaimsApi
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.economy.ServerBank
import kami.libs.stats.Staff
import kami.libs.stats.Stats
import kotlinx.serialization.encodeToString
import kotlin.random.Random

private class Totals {
    val players = HashMap<String, Int>()
    val countries = HashMap<String, Int>()
    val last = HashMap<String, Long>()

    fun add(d: QuestDelivery) {
        players.merge(d.player, d.qty, Int::plus)
        d.country?.let { c ->
            countries.merge(c, d.qty, Int::plus)
            last.merge(c, d.at, ::maxOf)
        }
    }
}

object Quests {
    const val PREFIX = "quest:"
    private const val BASE = -(1L shl 40)
    private const val KEEP_MS = 90 * QuestRoll.DAY
    private const val MIN_LOT_PRICE = 5.0
    private const val ROLL_TRIES = 3
    private const val RETRY_MS = 5 * 60_000L
    private const val STALE_MS = 10 * 60_000L

    private val quests = LinkedHashMap<Long, Quest>()
    private val live = LinkedHashMap<Long, Quest>()
    private val deliveries = LinkedHashMap<String, QuestDelivery>()
    private val byQuest = HashMap<Long, MutableList<QuestDelivery>>()
    private val totals = HashMap<Long, Totals>()
    private var clock = QuestClock()
    private var attached = false

    var random: Random = Random.Default
    internal var dishes: () -> Collection<Dish> = { Dishes.all() }
    internal var eligible: (String) -> Boolean = Blacklist::sellable
    internal var stackOf: (String) -> Int = { Blacklist.prototype(it)?.maxStackSize ?: 1 }
    internal var staff: (String) -> Boolean = { Staff.active("p:$it") }

    fun owns(owner: String) = owner.startsWith(PREFIX)

    fun isOrder(orderId: Long) = orderId <= BASE

    fun orderId(id: Long) = BASE - id

    fun idOf(orderId: Long) = BASE - orderId

    private fun order(q: Quest) = Order(orderId(q.id), "$PREFIX${q.id}", q.lotPrice, q.left, q.createdAt, lot = q.lot)

    private fun data() = QuestData(quests.values, deliveries.values, clock)

    private fun stat(key: String, obj: String = "", n: Long = 1) = Stats.add("quest.$key", "", obj, n)

    private fun gap(): Long {
        val s = Config.s.quests
        val min = s.minGapMinutes * 60_000L
        return random.nextLong(min, maxOf(min, s.maxGapMinutes * 60_000L) + 1)
    }

    fun load(t: Long = now()) {
        attached = false
        val d = Db.read { QuestTables.read(this) }
        quests.clear()
        live.clear()
        deliveries.clear()
        byQuest.clear()
        totals.clear()
        d.quests.forEach { quests[it.id] = it; if (it.open) live[it.id] = it }
        d.deliveries.forEach(::index)
        clock = d.clock
        QuestTables.all.forEach { it.prime(data()) }
        if (clock.next < 0) {
            clock.last.fill(t)
            clock.next = t + gap()
        }
        attached = true
    }

    fun unload() {
        attached = false
        quests.clear()
        live.clear()
        deliveries.clear()
        byQuest.clear()
        totals.clear()
    }

    internal fun changes(): List<(Session.() -> Unit)?> = if (attached) QuestTables.all.map { it.sync(data()) } else emptyList()

    private fun index(d: QuestDelivery) {
        deliveries[d.key] = d
        byQuest.getOrPut(d.quest) { mutableListOf() } += d
        totals.getOrPut(d.quest) { Totals() }.add(d)
    }

    fun delivered(key: String) = key in deliveries

    fun get(id: Long): Quest? = quests[id]

    fun list(): List<Quest> = quests.values.sortedWith(compareByDescending<Quest> { it.open }.thenByDescending { it.createdAt })

    fun open(): List<Quest> = live.values.toList()

    fun deliveriesOf(id: Long): List<QuestDelivery> = byQuest[id].orEmpty()

    fun mine(id: Long, player: String): Int = totals[id]?.players?.get(player) ?: 0

    fun countryQty(id: Long, country: String): Int = totals[id]?.countries?.get(country) ?: 0

    fun leaders(id: Long): List<Pair<String, Int>> = totals[id]?.let { t ->
        t.countries.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { t.last.getValue(it.key) }).map { it.key to it.value }
    }.orEmpty()

    fun clockState() = QuestClock(clock.next, clock.last.copyOf())

    private fun hidden(viewer: String) = viewer.isNotEmpty() && staff(viewer)

    fun bids(item: String, viewer: String = "", t: Long = now()): List<Order> =
        if (live.isEmpty() || hidden(viewer)) emptyList() else live.values.filter { it.item == item && it.left > 0 && it.expiresAt > t && !undercut(it) }.map(::order)

    fun itemOf(orderId: Long, viewer: String = ""): String? =
        live[idOf(orderId)]?.takeIf { !hidden(viewer) && it.left > 0 && it.expiresAt > now() && !undercut(it) }?.item

    private fun undercut(q: Quest) = Matching.stockAsk(q.item)?.let { it.toLong() * q.lot <= q.lotPrice.toLong() * Config.s.lotOf(q.item) } == true

    fun fill(item: String, seller: String, f: FillLinePlan, key: String, t: Long): Int {
        if (key in deliveries) return 0
        val q = live[idOf(f.orderId)] ?: return late(seller, f, key, t)
        val first = byQuest[q.id].isNullOrEmpty()
        val n = minOf(f.qty, q.left)
        index(QuestDelivery(q.id, seller, Trade.country(seller), n, f.gross, t, key))
        q.filled += n
        Market.dirty = true
        runCatching {
            val rarity = Rarity.names[q.rarity]
            stat("deliveries", rarity)
            stat("qty", item, n.toLong())
            stat("paid", rarity, f.gross)
            stat("waived", rarity, Matching.tax(f.gross))
            Stats.add("quest.player", "p:$seller", rarity)
            Trade.country(seller)?.let { Stats.add("quest.country", "c:$it", rarity) }
            if (first) Stats.event("quest.first", "", rarity, t - q.createdAt)
        }.onFailure { LOG.error("Quest {} stats failed", q.id, it) }
        if (q.left <= 0) finish(q, QuestState.DONE, t)
        return n
    }

    private fun late(seller: String, f: FillLinePlan, key: String, t: Long): Int {
        val q = quests[idOf(f.orderId)] ?: return 0.also { LOG.warn("Quest fill {} for pruned quest {} was paid without credit", key, idOf(f.orderId)) }
        index(QuestDelivery(q.id, seller, Trade.country(seller), f.qty, f.gross, t, key))
        Market.dirty = true
        LOG.warn("Quest fill {} for closed quest {} was recorded late", key, q.id)
        return f.qty
    }

    private fun finish(q: Quest, state: QuestState, t: Long) {
        live.remove(q.id)
        q.state = state
        q.endedAt = t
        val board = if (state == QuestState.DONE) leaders(q.id) else emptyList()
        q.winner = board.firstOrNull()?.first
        Market.dirty = true
        runCatching {
            val rarity = Rarity.names[q.rarity]
            stat(state.name.lowercase(), rarity)
            Stats.event("quest.fill_pct", "", rarity, q.fillPct)
            if (state == QuestState.DONE) Stats.event("quest.full", "", rarity, t - q.createdAt)
            q.winner?.let { w ->
                q.boosts.forEach { b -> if (ClaimsApi.boost(w, b.kind, b.pct, b.hours * QuestRoll.HOUR)) stat("boost", b.kind, b.pct.toLong()) else LOG.warn("Quest {} boost {} for {} was not granted", q.id, b.kind, w) }
            }
            if (state != QuestState.EXPIRED || t - q.expiresAt < STALE_MS) QuestAnnounce.finished(q, board)
        }.onFailure { LOG.error("Quest {} end handling failed", q.id, it) }
    }

    fun tick(t: Long = now()) {
        if (!attached) return
        live.values.filter { it.expiresAt <= t }.forEach { finish(it, QuestState.EXPIRED, t) }
        prune(t)
        val s = Config.s.quests
        if (!s.enabled || t < clock.next || live.size >= s.maxActive) return
        val made = runCatching { spawn(t) }.onFailure { LOG.error("Quest spawn failed", it) }.getOrNull()
        clock.next = t + if (made != null) gap() else RETRY_MS
        Market.dirty = true
    }

    private fun prune(t: Long) {
        val old = quests.values.filter { !it.open && (it.endedAt ?: it.createdAt) < t - KEEP_MS }.map { it.id }
        old.forEach { id ->
            quests.remove(id)
            byQuest.remove(id)?.forEach { deliveries.remove(it.key) }
            totals.remove(id)
        }
        if (old.isNotEmpty()) Market.dirty = true
    }

    private fun candidates(): List<Dish> {
        val open = live.values.mapTo(HashSet()) { it.item }
        return dishes().filter {
            it.quest && it.item !in open && Variants.of(it.item) == null && eligible(it.item) &&
                TradeStatus.bids(it.item) && TradeStatus.instantSell(it.item) && Stocks.supplyLeft(it.item) == 0 && (Stocks.good(it.item)?.supply ?: 0) == 0
        }
    }

    private class Rolled(val rarity: Int, val asked: Int, val dish: Dish, val info: PickInfo)

    private fun roll(t: Long, forced: Int?): Rolled? {
        val s = Config.s.quests
        val all = candidates()
        val last = quests.values.groupBy { it.item }.mapValues { (_, l) -> l.maxOf { it.endedAt ?: it.createdAt } }
        val asked = forced ?: QuestRoll.rarity(clock, t, s, random)
        var r = asked
        while (true) {
            val pool = all.filter { it.tier >= s.minTier[r] }
            if (pool.isNotEmpty()) {
                val factors = QuestRoll.factors(pool, t, s, Market.data.traded::get, last::get, { Matching.demand(it).coerceAtLeast(0).toLong() }, { Matching.available(it).coerceAtLeast(0).toLong() })
                val pick = QuestRoll.pick(factors, random) ?: return null
                return Rolled(r, asked, pool.first { it.item == pick.item }, QuestRoll.info(factors, pick))
            }
            if (r == 0) return null
            r--
        }
    }

    private class Plan(val course: Double, val offset: Double, val lotPrice: Int, val lot: Int, val amount: Int, val boosts: List<QuestBoost>)

    private fun plan(item: String, tier: Int, r: Int, spec: QuestSpec?): Plan? {
        val s = Config.s.quests
        val lot = Config.s.lotOf(item).coerceAtLeast(1)
        val course = Stocks.course(item).takeIf { it > 0.0 } ?: ((dishes().firstOrNull { it.item == item }?.price ?: 0.0) * lot)
        if (course <= 0.0 || spec == null && course * (1 - s.offsetMax) < MIN_LOT_PRICE) return null
        val demand = Matching.demand(item).coerceAtLeast(0).toLong()
        val avail = Matching.available(item).coerceAtLeast(0).toLong()
        val offset = spec?.offset ?: QuestRoll.offset(s, demand, avail, QuestRoll.ref(s, tier), random)
        val lotPrice = spec?.lotPrice ?: Math.round(course * (1 + offset)).coerceIn(1L, Config.s.maxPrice.toLong()).toInt()
        val amount = spec?.let { (it.amount - it.amount % lot).coerceAtLeast(lot) } ?: QuestRoll.amount(s.amounts[r], tier, lot, stackOf(item), random)
        if (spec == null && (Stocks.ask(item)?.let { it <= lotPrice } == true || Stocks.bid(item, "")?.let { it >= lotPrice } == true)) return null
        val real = lotPrice / course - 1
        return Plan(course, real, lotPrice, lot, amount, spec?.boosts ?: QuestRoll.boosts(r, real < 0, s, random))
    }

    private fun room(): Long {
        val floor = Config.s.quests.bankFloor
        val committed = live.values.sumOf { it.left.toLong() / it.lot * it.lotPrice }
        return runCatching { Math.subtractExact(Math.subtractExact(ServerBank.balance, floor), committed) }
            .getOrElse { if (ServerBank.balance > 0) Long.MAX_VALUE else Long.MIN_VALUE }
    }

    private fun fit(p: Plan): Int {
        val lots = minOf((p.amount / p.lot).toLong(), room() / p.lotPrice)
        return if (lots < 1) 0 else (lots * p.lot).toInt()
    }

    private fun create(item: String, tier: Int, r: Int, p: Plan, amount: Int, hours: Int, pick: PickInfo?, t: Long, announce: Boolean): Quest {
        val info = pick?.let { Db.json.encodeToString(it) } ?: "{}"
        val q = Quest(Market.nextId(), item, r, p.lotPrice, p.lot, amount, 0, p.course, p.offset, t, t + hours * QuestRoll.HOUR, QuestState.OPEN, p.boosts, pick = info)
        quests[q.id] = q
        live[q.id] = q
        Market.dirty = true
        stat("spawned", Rarity.names[r])
        stat("sign", if (p.offset < 0) "minus" else "plus")
        stat("tier", "t$tier")
        stat("mod", item.substringBefore(':'))
        if (announce) QuestAnnounce.spawned(q)
        return q
    }

    private fun spawn(t: Long): Quest? {
        val s = Config.s.quests
        val asked = QuestRoll.rarity(clock, t, s, random)
        val (rolled, p) = (1..ROLL_TRIES).firstNotNullOfOrNull { roll(t, asked)?.let { r -> plan(r.dish.item, r.dish.tier, r.rarity, null)?.let { r to it } } }
            ?: return null.also { stat("skipped", "pool") }
        val dish = rolled.dish
        val amount = fit(p)
        if (amount < 1) return null.also { stat("skipped", "bank") }
        listOf(rolled.asked, rolled.rarity).filter { it >= 2 }.forEach { clock.last[it - 2] = t }
        return create(dish.item, dish.tier, rolled.rarity, p, amount, s.hours[rolled.rarity], rolled.info, t, true)
    }

    fun spawn(spec: QuestSpec, announce: Boolean = true, t: Long = now()): Quest? {
        val r = spec.rarity.coerceIn(0, Rarity.MAX)
        if (spec.amount < 1 || spec.hours < 1 || !eligible(spec.item) || live.values.any { it.item == spec.item }) return null
        if (spec.lotPrice != null && !Config.s.validPrice(spec.lotPrice)) return null
        if (spec.boosts != null && !validBoosts(spec.boosts)) return null
        val tier = dishes().firstOrNull { it.item == spec.item }?.tier ?: 1
        val p = plan(spec.item, tier, r, spec) ?: return null
        return create(spec.item, tier, r, p, p.amount, spec.hours, null, t, announce)
    }

    fun preview(rarity: Int? = null, t: Long = now()): QuestPreview? {
        val rolled = roll(t, rarity?.coerceIn(0, Rarity.MAX)) ?: return null
        val p = plan(rolled.dish.item, rolled.dish.tier, rolled.rarity, null) ?: return null
        return QuestPreview(rolled.rarity, rolled.info, p.offset, p.lotPrice, p.amount, p.boosts)
    }

    private fun validBoosts(list: List<QuestBoost>) = list.all { it.kind in Boost.KINDS && it.pct in 1..Boost.MAX_PCT && it.hours in 1..Boost.MAX_HOURS }

    fun cancel(id: Long): Boolean {
        val q = live[id] ?: return false
        finish(q, QuestState.CANCELLED, now())
        return true
    }

    fun end(id: Long): Boolean {
        val q = live[id] ?: return false
        q.expiresAt = now()
        finish(q, QuestState.EXPIRED, q.expiresAt)
        return true
    }

    fun edit(id: Long, lotPrice: Int? = null, amount: Int? = null, expiresAt: Long? = null, boosts: List<QuestBoost>? = null): Boolean {
        val q = live[id] ?: return false
        val a = amount?.let { it - it % q.lot }
        if (lotPrice != null && !Config.s.validPrice(lotPrice)) return false
        if (a != null && (a < q.filled || a < q.lot)) return false
        if (expiresAt != null && (expiresAt <= now() || expiresAt > now() + QuestSettings.MAX_HOURS * QuestRoll.HOUR)) return false
        if (boosts != null && !validBoosts(boosts)) return false
        lotPrice?.let { q.lotPrice = it }
        a?.let { q.amount = it }
        expiresAt?.let { q.expiresAt = it }
        boosts?.let { q.boosts = it }
        Market.dirty = true
        if (q.left <= 0) finish(q, QuestState.DONE, now())
        return true
    }

    fun resetClock(rarity: Int) {
        if (rarity in 2..4) clock.last[rarity - 2] = now() else clock.next = now()
        Market.dirty = true
    }
}
