package kami.economy.db

import kami.economy.Book
import kami.economy.Data
import kami.economy.Delivery
import kami.economy.Order
import kami.economy.Stock
import kami.economy.Traded
import kami.economy.Vendor
import kami.economy.economy.Auction
import kami.economy.economy.AuctionState
import kami.libs.db.Db
import kami.libs.db.Row
import kami.libs.db.Session
import kami.libs.db.Table
import kotlinx.serialization.encodeToString

internal object MarketTables {
    private const val SELL = "sell"
    private const val BUY = "buy"

    private fun order(item: String, side: String, o: Order) = listOf(item, side, o.id, o.owner, o.price, o.amount, o.placedAt, o.synthetic, o.lot)

    val all = listOf(
        Table<Data>("eco_market", listOf("id"), listOf("next_order_id", "day", "version", "frozen")) {
            listOf(listOf(1, it.nextOrderId, it.day, it.version, Db.json.encodeToString(it.frozen)))
        },
        Table<Data>("eco_book", listOf("item"), listOf("last_fill", "mid_price", "recent_fills")) { d ->
            d.books.map { (k, b) -> listOf(k, b.lastFill, b.midPrice, Db.json.encodeToString(b.recentFills)) }
        },
        Table<Data>("eco_order", listOf("item", "side", "id"), listOf("owner", "price", "amount", "placed_at", "synthetic", "lot")) { d ->
            d.books.flatMap { (k, b) -> b.sells.map { order(k, SELL, it) } + b.buys.map { order(k, BUY, it) } }
        },
        Table<Data>("eco_delivery", listOf("owner"), listOf("data")) { d ->
            d.pendingDelivery.map { (k, v) -> listOf(k, Db.json.encodeToString(v)) }
        },
        Table<Data>("eco_auction", listOf("id"), listOf("pos", "seller", "stack_data", "label", "start_price", "buy_now_price", "current_bid", "current_bidder", "expires_at", "state")) { d ->
            d.auctions.mapIndexed { i, a -> listOf(a.id, i, a.seller, a.stackData, a.label, a.startPrice, a.buyNowPrice, a.currentBid, a.currentBidder, a.expiresAt, a.state.name) }
        },
        Table<Data>("eco_stock", listOf("item"), listOf("lots", "base", "observed")) { d ->
            d.stocks.map { (k, s) -> listOf(k, s.lots, s.base, s.observed) }
        },
        Table<Data>("eco_sold", listOf("player"), listOf("data")) { d ->
            d.sold.map { (k, v) -> listOf(k, Db.json.encodeToString(v)) }
        },
        Table<Data>("eco_traded", listOf("item"), listOf("sold", "bought")) { d ->
            d.traded.map { (k, t) -> listOf(k, t.sold, t.bought) }
        },
        Table<Data>("eco_vendor", listOf("dim", "x", "y", "z"), listOf("country", "owner", "item", "price", "count", "sell", "stock", "seen")) { d ->
            d.vendors.map { listOf(it.dim, it.x, it.y, it.z, it.country, it.owner, it.item, it.price, it.count, it.sell, it.stock, it.seen) }
        },
        Table<Data>("eco_muted", listOf("player"), listOf("data")) { d ->
            d.muted.map { (k, v) -> listOf(k, Db.json.encodeToString(v)) }
        }
    )

    private fun <V> Session.map(table: String, key: String, value: (Row) -> V): MutableMap<String, V> {
        val sql = "SELECT * FROM $table ORDER BY rowid"
        return query(sql) { it.string(key) to value(it) }.toMap(LinkedHashMap())
    }

    fun read(s: Session): Data {
        val orders = s.query("SELECT * FROM eco_order ORDER BY rowid") { r ->
            (r.string("item") to r.string("side")) to Order(r.long("id"), r.string("owner"), r.int("price"), r.int("amount"), r.long("placed_at"), r.bool("synthetic"), r.int("lot"))
        }.groupBy({ it.first }, { it.second })
        val books = s.map("eco_book", "item") { r ->
            val item = r.string("item")
            Book(item, orders[item to SELL].orEmpty().toMutableList(), orders[item to BUY].orEmpty().toMutableList(), r.int("last_fill"), r.double("mid_price"), Db.json.decodeFromString(r.string("recent_fills")))
        }
        val auctions = s.query("SELECT * FROM eco_auction ORDER BY pos") { r ->
            Auction(
                r.long("id"), r.string("seller"), r.string("stack_data"), r.string("label"), r.int("start_price"), r.intOrNull("buy_now_price"),
                r.int("current_bid"), r.string("current_bidder"), r.long("expires_at"), AuctionState.valueOf(r.string("state"))
            )
        }.toMutableList()
        val vendors = s.query("SELECT * FROM eco_vendor ORDER BY rowid") { r ->
            Vendor(r.string("dim"), r.int("x"), r.int("y"), r.int("z"), r.string("country"), r.string("owner"), r.string("item"), r.int("price"), r.int("count"), r.bool("sell"), r.int("stock"), r.long("seen"))
        }.toMutableList()
        val delivery = s.map("eco_delivery", "owner") { Db.json.decodeFromString<MutableList<Delivery>>(it.string("data")) }
        val stocks = s.map("eco_stock", "item") { Stock(it.int("lots"), it.double("base"), it.double("observed")) }
        val sold = s.map("eco_sold", "player") { Db.json.decodeFromString<MutableMap<String, Int>>(it.string("data")) }
        val traded = s.map("eco_traded", "item") { Traded(it.long("sold"), it.long("bought")) }
        val muted = s.map("eco_muted", "player") { Db.json.decodeFromString<MutableSet<String>>(it.string("data")) }
        return checkNotNull(s.one("SELECT * FROM eco_market WHERE id = 1") { r ->
            Data(books, r.long("next_order_id"), delivery, auctions, Db.json.decodeFromString(r.string("frozen")), stocks, sold, traded, r.long("day"), vendors, muted, r.int("version"))
        }) { "eco_market row is missing" }
    }

    fun totals(d: Data): List<Long> {
        val orders = d.books.values.flatMap { it.sells + it.buys }
        return listOf(
            d.books.size.toLong(), orders.size.toLong(), orders.sumOf { it.price.toLong() * it.amount },
            d.auctions.size.toLong(), d.auctions.sumOf { it.currentBid.toLong() }, d.vendors.size.toLong(), d.stocks.size.toLong()
        )
    }

    fun totals(s: Session): List<Long> = listOf(
        s.count("SELECT COUNT(*) AS v FROM eco_book"), s.count("SELECT COUNT(*) AS v FROM eco_order"), s.count("SELECT SUM(price * amount) AS v FROM eco_order"),
        s.count("SELECT COUNT(*) AS v FROM eco_auction"), s.count("SELECT SUM(current_bid) AS v FROM eco_auction"),
        s.count("SELECT COUNT(*) AS v FROM eco_vendor"), s.count("SELECT COUNT(*) AS v FROM eco_stock")
    )
}
