package kami.economy.db

import kami.economy.Data
import kami.economy.Market
import kami.economy.economy.History
import kami.economy.economy.HistoryData
import kami.economy.economy.Intent
import kami.economy.economy.Ledger
import kami.economy.economy.TradeStatus
import kami.economy.economy.Watchlist
import kami.economy.quest.Quests
import kami.economy.villager.VillagerCourses
import kami.libs.db.Legacy
import kami.libs.db.Migration
import kami.libs.db.Migrations
import kami.libs.db.SavePoint
import kami.libs.db.Verify
import kotlinx.serialization.builtins.ListSerializer

object EcoDb {
    private const val MARKET = "kami_economy.json"
    private const val HISTORY = "kami_economy_history.json"
    private const val WAL = "kami_economy.wal"

    private val schema = listOf(
        "CREATE TABLE eco_market (id INTEGER PRIMARY KEY, next_order_id INTEGER NOT NULL, day INTEGER NOT NULL, version INTEGER NOT NULL, frozen TEXT NOT NULL)",
        "CREATE TABLE eco_book (item TEXT PRIMARY KEY, last_fill INTEGER NOT NULL, mid_price REAL NOT NULL, recent_fills TEXT NOT NULL)",
        "CREATE TABLE eco_order (item TEXT NOT NULL REFERENCES eco_book(item) ON DELETE CASCADE, side TEXT NOT NULL, id INTEGER NOT NULL, owner TEXT NOT NULL, " +
            "price INTEGER NOT NULL, amount INTEGER NOT NULL, placed_at INTEGER NOT NULL, synthetic INTEGER NOT NULL, lot INTEGER NOT NULL, PRIMARY KEY (item, side, id))",
        "CREATE TABLE eco_delivery (owner TEXT PRIMARY KEY, data TEXT NOT NULL)",
        "CREATE TABLE eco_auction (id INTEGER PRIMARY KEY, pos INTEGER NOT NULL, seller TEXT NOT NULL, stack_data TEXT NOT NULL, label TEXT NOT NULL, start_price INTEGER NOT NULL, " +
            "buy_now_price INTEGER, current_bid INTEGER NOT NULL, current_bidder TEXT NOT NULL, expires_at INTEGER NOT NULL, state TEXT NOT NULL)",
        "CREATE TABLE eco_stock (item TEXT PRIMARY KEY, lots INTEGER NOT NULL, base REAL NOT NULL, observed REAL NOT NULL)",
        "CREATE TABLE eco_sold (player TEXT PRIMARY KEY, data TEXT NOT NULL)",
        "CREATE TABLE eco_traded (item TEXT PRIMARY KEY, sold INTEGER NOT NULL, bought INTEGER NOT NULL)",
        "CREATE TABLE eco_vendor (dim TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, country TEXT NOT NULL, owner TEXT NOT NULL, item TEXT NOT NULL, " +
            "price INTEGER NOT NULL, count INTEGER NOT NULL, sell INTEGER NOT NULL, stock INTEGER NOT NULL, seen INTEGER NOT NULL, PRIMARY KEY (dim, x, y, z))",
        "CREATE TABLE eco_muted (player TEXT PRIMARY KEY, data TEXT NOT NULL)",
        "CREATE TABLE eco_history_item (item TEXT PRIMARY KEY, five_acc TEXT, five_start INTEGER NOT NULL, hour_acc TEXT, hour_start INTEGER NOT NULL, day_acc TEXT, day_start INTEGER NOT NULL)",
        "CREATE TABLE eco_history (item TEXT NOT NULL REFERENCES eco_history_item(item) ON DELETE CASCADE, tier INTEGER NOT NULL, at INTEGER NOT NULL, " +
            "open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume INTEGER NOT NULL, PRIMARY KEY (item, tier, at))",
        "CREATE TABLE eco_intent (id INTEGER PRIMARY KEY, type TEXT NOT NULL, state TEXT NOT NULL, actor TEXT NOT NULL, data TEXT NOT NULL)"
    )

    private val v1 = Migration("eco", 1, "schema", legacy = listOf(MARKET, HISTORY, WAL), ddl = schema) { world ->
        check(Legacy.read(world, MARKET, Data.serializer()) == null) { "$MARKET import is no longer supported; start from a kami.db written by an older version" }
        val history = Legacy.read(world, HISTORY, HistoryData.serializer()) ?: HistoryData()
        val wal = legacyIntents(world.resolve(WAL))
        MarketTables.all.first().insert(this, Data())
        HistoryTables.all.forEach { it.insert(this, history) }
        intentTable.insert(this, wal)
        return@Migration {
            Verify.sameJson("eco history", HistoryData.serializer(), history, HistoryTables.read(this))
            Verify.equal("eco history totals", HistoryTables.totals(history), HistoryTables.totals(this))
            Verify.sameJson("eco intents", ListSerializer(Intent.serializer()), wal.sortedBy { it.id }, intents())
            Verify.equal("eco intent count", wal.size.toLong(), count("SELECT COUNT(*) AS v FROM eco_intent"))
        }
    }

    private val v2 = Migration("eco", 2, "item_status", ddl = listOf(
        "CREATE TABLE eco_item_status (item TEXT PRIMARY KEY, flags TEXT NOT NULL, reason TEXT NOT NULL, \"by\" TEXT NOT NULL, at INTEGER NOT NULL)"
    ))

    private val v3 = Migration("eco", 3, "kept_coins", ddl = listOf(
        "CREATE TABLE eco_kept_coins (uuid TEXT NOT NULL, item TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (uuid, item))"
    ))

    private val v4 = Migration("eco", 4, "watchlist", ddl = listOf(
        "CREATE TABLE eco_watch (player TEXT NOT NULL, item TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (player, item))"
    ))

    private val v5 = Migration("eco", 5, "market_rework", ddl = listOf(
        "CREATE TABLE eco_server (item TEXT PRIMARY KEY, supply INTEGER NOT NULL, demand INTEGER NOT NULL)",
        "INSERT INTO eco_server (item, supply, demand) SELECT item, lots, -2 FROM eco_stock",
        "INSERT OR IGNORE INTO eco_book (item, last_fill, mid_price, recent_fills) SELECT item, 0, base, '[]' FROM eco_stock WHERE base > 0",
        "UPDATE eco_book SET mid_price = (SELECT base FROM eco_stock s WHERE s.item = eco_book.item) WHERE item IN (SELECT item FROM eco_stock WHERE base > 0)",
        "DROP TABLE eco_stock",
        "DELETE FROM eco_sold",
        "ALTER TABLE eco_order ADD COLUMN peg INTEGER",
        "ALTER TABLE eco_order ADD COLUMN peg_limit INTEGER",
        "ALTER TABLE eco_auction ADD COLUMN bids TEXT NOT NULL DEFAULT '[]'"
    ))

    private val v6 = Migration("eco", 6, "quests", ddl = listOf(
        "CREATE TABLE eco_quest (id INTEGER PRIMARY KEY, item TEXT NOT NULL, rarity INTEGER NOT NULL, lot_price INTEGER NOT NULL, lot INTEGER NOT NULL, amount INTEGER NOT NULL, " +
            "filled INTEGER NOT NULL, course REAL NOT NULL, price_offset REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, state TEXT NOT NULL, boosts TEXT NOT NULL, " +
            "winner TEXT, ended_at INTEGER, pick TEXT NOT NULL)",
        "CREATE TABLE eco_quest_delivery (ref TEXT PRIMARY KEY, quest INTEGER NOT NULL, player TEXT NOT NULL, country TEXT, qty INTEGER NOT NULL, paid INTEGER NOT NULL, at INTEGER NOT NULL)",
        "CREATE TABLE eco_quest_state (id INTEGER PRIMARY KEY, next INTEGER NOT NULL, last_rare INTEGER NOT NULL, last_mythic INTEGER NOT NULL, last_legendary INTEGER NOT NULL)",
        "INSERT INTO eco_quest_state (id, next, last_rare, last_mythic, last_legendary) VALUES (1, -1, 0, 0, 0)",
        "ALTER TABLE eco_traded ADD COLUMN last INTEGER NOT NULL DEFAULT 0"
    ))

    private val v7 = Migration("eco", 7, "villagers", ddl = listOf(
        "CREATE TABLE eco_villager (spec TEXT PRIMARY KEY, course REAL NOT NULL, floor REAL NOT NULL, state TEXT NOT NULL, fills INTEGER NOT NULL, last INTEGER NOT NULL, samples TEXT NOT NULL)"
    ))

    private val v8 = Migration("eco", 8, "villager_lots", ddl = listOf(
        "ALTER TABLE eco_villager ADD COLUMN lot INTEGER NOT NULL DEFAULT 1"
    ))

    private val v9 = Migration("eco", 9, "course_moved", ddl = listOf(
        "ALTER TABLE eco_book ADD COLUMN moved INTEGER NOT NULL DEFAULT 0"
    ))

    fun register() {
        Migrations.register("eco", listOf(v1, v2, v3, v4, v5, v6, v7, v8, v9), after = listOf("libs"))
        SavePoint.register("economy") { SavePoint.join(Market.changes() + History.changes() + Quests.changes() + listOf(Ledger.changes(), TradeStatus.changes(), Watchlist.changes(), VillagerCourses.changes())) }
    }
}
