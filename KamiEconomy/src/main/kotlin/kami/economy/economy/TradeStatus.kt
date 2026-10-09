package kami.economy.economy

import kami.economy.Market
import kami.economy.now
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.db.Table
import kami.libs.mc.ItemSpec
import kami.libs.text.Phrase

object TradeStatus {
    private val table = Table<Map<String, ItemStatus>>("eco_item_status", listOf("item"), listOf("flags", "reason", "\"by\"", "at")) { m ->
        m.map { (k, s) -> listOf(k, TradeFlag.csv(s.flags), s.reason, s.by, s.at) }
    }
    private val entries = LinkedHashMap<String, ItemStatus>()
    private var effective: Map<String, ItemStatus>? = null
    private var attached = false

    var rev = 0L
        private set

    fun load() {
        entries.clear()
        Db.read { query("SELECT * FROM eco_item_status ORDER BY rowid") { it.string("item") to ItemStatus(TradeFlag.parse(it.string("flags")), it.string("reason"), it.string("by"), it.long("at")) } }
            .forEach { (k, v) -> entries[k] = v }
        table.prime(entries)
        effective = null
        attached = true
    }

    fun unload() {
        attached = false
    }

    internal fun changes(): (Session.() -> Unit)? = if (attached) table.sync(entries) else null

    fun reindex() {
        effective = null
        rev++
    }

    fun all(): Map<String, ItemStatus> = entries

    fun rev(item: String): String = entries[item]?.at?.toString() ?: "0"

    fun set(item: String, flags: Set<TradeFlag>, reason: String, by: String): ItemStatus? {
        val before = entries[item]
        if (flags.isEmpty()) entries.remove(item) else entries[item] = ItemStatus(flags, reason, by, now())
        rev++
        effective = null
        Market.dirty = true
        return before
    }

    private fun effective(): Map<String, ItemStatus> = effective ?: HashMap<String, ItemStatus>().also { map ->
        fun add(k: String, s: ItemStatus) {
            val old = map[k]
            map[k] = if (old == null) s else ItemStatus(old.flags + s.flags, s.reason, s.by, s.at)
        }
        entries.filterKeys { it.startsWith("#") }.forEach { (tag, s) -> ItemSpec.stacks(tag).forEach { add(ItemSpec.id(it), s) } }
        entries.filterKeys { !it.startsWith("#") }.forEach { (k, s) -> add(k, s) }
        effective = map
    }

    fun of(item: String): ItemStatus? = effective().let { m ->
        val own = m[item]
        val base = m[ItemSpec.base(item)]
        if (own == null || base == null) own ?: base else ItemStatus(own.flags + base.flags, own.reason, own.by, own.at)
    }

    fun flags(item: String): Set<TradeFlag> = of(item)?.flags.orEmpty()

    fun denial(action: String, item: String): Phrase? =
        of(item)?.takeIf { TradeFlag.blocks(action, it.flags) }?.let { Phrase.of("kami_economy.status.frozen", it.reason) }

    private fun open(item: String, a: TradeFlag, b: TradeFlag) = flags(item).let { a !in it && b !in it }

    fun asks(item: String) = open(item, TradeFlag.SELL, TradeFlag.ORDER)

    fun bids(item: String) = open(item, TradeFlag.BUY, TradeFlag.ORDER)

    fun instantBuy(item: String) = open(item, TradeFlag.BUY, TradeFlag.INSTANT)

    fun instantSell(item: String) = open(item, TradeFlag.SELL, TradeFlag.INSTANT)

    fun auctions(item: String) = TradeFlag.AUCTION !in flags(item)
}
