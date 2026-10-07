package kami.libs.economy

import kami.libs.LibConfig
import kami.libs.db.Db
import kami.libs.db.Migration
import kami.libs.db.Rows
import kami.libs.db.SavePoint
import kami.libs.db.Session
import kami.libs.db.Writer
import kami.libs.log.Log
import kami.libs.util.Time.DAY_MS
import java.util.UUID
import kotlin.math.abs

enum class CoinSource { TERMINAL, VENDOR, DEPOSITOR, SALEPOINT, BLAZE_BANKER, STOCK_TICKER, CREATIVE_VENDOR, KAMI }

object Coins {
    private const val SPUR = ''
    private const val BEVEL = ''
    private const val SPROCKET = ''
    private const val COG = ''
    private const val CROWN = ''
    private const val SUN = ''

    private val denominations = listOf(4096L to SUN, 512L to CROWN, 64L to COG, 16L to SPROCKET, 8L to BEVEL, 1L to SPUR)

    val values: Map<String, Int> get() = LibConfig.s.coins

    fun glyph(spurValue: Long): Char = denominations.firstOrNull { spurValue == it.first }?.second ?: SPUR

    class Compact(val amount: String, val glyph: Char) {
        val text get() = "$amount$glyph"
    }

    fun compactParts(spurs: Long): Compact {
        val neg = spurs < 0
        val v = abs(spurs)
        val (value, glyph) = denominations.firstOrNull { v >= it.first } ?: (1L to SPUR)
        val amount = v.toDouble() / value
        val text = if (value == 1L || amount >= 100.0) amount.toLong().toString() else "%.1f".format(amount)
        return Compact((if (neg) "-" else "") + text, glyph)
    }

    fun compactParts(spurs: Int): Compact = compactParts(spurs.toLong())

    fun compact(spurs: Long): String = compactParts(spurs).text
    fun compact(spurs: Int): String = compactParts(spurs.toLong()).text

    private data class Key(val source: CoinSource, val party: Party, val at: String)

    private class Tally(var minted: Long = 0, var burned: Long = 0, var moved: Long = 0, var count: Int = 0)

    private val sessions = LinkedHashMap<Key, Tally>()
    private val days = HashMap<Pair<Long, CoinSource>, LongArray>()
    private val supplyRow = Rows<Int>("libs_coin_supply", listOf("id"), listOf("supply", "minted", "burned"))
    private val dayRows = Rows<Pair<Long, CoinSource>>("libs_coin_day", listOf("day", "source"), listOf("minted", "burned"))
    private var mintedTotal = 0L
    private var burnedTotal = 0L

    internal val migration = Migration(
        "libs", 5, "coins", ddl = listOf(
            "CREATE TABLE libs_coin_supply (id INTEGER PRIMARY KEY CHECK (id = 1), supply INTEGER NOT NULL, minted INTEGER NOT NULL, burned INTEGER NOT NULL)",
            "CREATE TABLE libs_coin_day (day INTEGER NOT NULL, source TEXT NOT NULL, minted INTEGER NOT NULL, burned INTEGER NOT NULL, PRIMARY KEY (day, source))"
        )
    )

    val supply: Long
        @Synchronized get() = mintedTotal - burnedTotal

    internal class Snapshot(val minted: Long, val burned: Long, val days: Map<Pair<Long, CoinSource>, LongArray>) {
        val supply get() = minted - burned
    }

    @Synchronized
    internal fun snapshot() = Snapshot(mintedTotal, burnedTotal, days.mapValues { it.value.copyOf() })

    fun minted(source: CoinSource, party: Party?, spurs: Long, at: String = "") = add(source, party, at, spurs, 0, 0)

    fun burned(source: CoinSource, party: Party?, spurs: Long, at: String = "") = add(source, party, at, 0, spurs, 0)

    fun moved(source: CoinSource, party: Party?, spurs: Long, at: String = "") = add(source, party, at, 0, 0, spurs)

    @Synchronized
    fun close(source: CoinSource, party: Party?, at: String = "") {
        val key = Key(source, party ?: return, at)
        sessions.remove(key)?.let { flush(key, it) }
    }

    @Synchronized
    fun logout(player: UUID) = closeAll { it.source == CoinSource.TERMINAL && it.party == PlayerParty(player) }

    @Synchronized
    private fun add(source: CoinSource, party: Party?, at: String, mint: Long, burn: Long, move: Long) {
        if (mint < 0 || burn < 0 || mint + burn == 0L && move == 0L) return
        mintedTotal += mint
        burnedTotal += burn
        if (mint + burn > 0) days.getOrPut(System.currentTimeMillis() / DAY_MS to source) { LongArray(2) }.let { it[0] += mint; it[1] += burn }
        if (party == null) return
        val t = sessions.getOrPut(Key(source, party, at)) { Tally() }
        t.minted += mint
        t.burned += burn
        t.moved += move
        t.count++
    }

    private fun closeAll(which: (Key) -> Boolean) = sessions.keys.filter(which).forEach { key -> sessions.remove(key)?.let { flush(key, it) } }

    private fun flush(key: Key, t: Tally) {
        val note = listOf(key.source.name.lowercase(), key.at, "×${t.count}").filter { it.isNotEmpty() }.joinToString(" ")
        leg(key.party, -t.minted, Flow.COIN_WITHDRAW, note)
        leg(key.party, t.burned, Flow.COIN_DEPOSIT, note)
        leg(key.party, t.moved, Flow.VENDOR, note)
    }

    private fun leg(party: Party, delta: Long, flow: Flow, note: String) {
        if (delta == 0L) return
        if (party is PlayerParty) Statement.record(party.uuid, delta, flow, note) else Journal.leg(party, delta, flow, note = note)
    }

    @Synchronized
    internal fun load() {
        sessions.clear()
        days.clear()
        val row = Db.read { one("SELECT minted, burned FROM libs_coin_supply WHERE id = 1") { it.long("minted") to it.long("burned") } }
        mintedTotal = row?.first ?: 0L
        burnedTotal = row?.second ?: 0L
        var renamed = false
        Db.read {
            query("SELECT day, source, minted, burned FROM libs_coin_day") {
                val name = it.string("source")
                val source = coinSource(name)
                renamed = renamed || source.name != name
                days.merge(it.long("day") to source, longArrayOf(it.long("minted"), it.long("burned"))) { a, b -> longArrayOf(a[0] + b[0], a[1] + b[1]) }
            }
        }
        supplyRow.prime(supplyRows())
        dayRows.prime(dayRows())
        if (renamed) Writer.invalidate()
        SavePoint.register("libs.coins", ::changes)
    }

    private fun coinSource(name: String) = CoinSource.entries.firstOrNull { it.name == name } ?: CoinSource.KAMI.also { Log.of("library").warn("Unknown coin source {} counted as {}", name, it) }

    @Synchronized
    private fun changes(): (Session.() -> Unit)? {
        closeAll { true }
        return SavePoint.join(listOf(supplyRow.diff(supplyRows()), dayRows.diff(dayRows())))
    }

    private fun supplyRows() = mapOf(1 to listOf<Any?>(1, mintedTotal - burnedTotal, mintedTotal, burnedTotal))

    private fun dayRows() = days.mapValues { (k, v) -> listOf<Any?>(k.first, k.second.name, v[0], v[1]) }
}
