package kami.claims

import kami.claims.war.War
import kami.libs.db.Db
import kami.libs.db.Legacy
import kami.libs.db.Migration
import kami.libs.db.Migrations
import kami.libs.db.SavePoint
import kami.libs.db.Session
import kami.libs.db.Table
import kami.libs.db.Verify
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.IdentityHashMap

object RealmDb {
    const val FILE = "kami_claims.json"

    private class Seq {
        private var ids = IdentityHashMap<Any, Long>()
        private var next = 1L

        fun load(item: Any, id: Long) {
            ids[item] = id
            next = maxOf(next, id + 1)
        }

        fun <T> assign(items: List<T>, of: (T) -> Any): List<Pair<Long, T>> {
            val fresh = IdentityHashMap<Any, Long>()
            return items.map { (ids[of(it)] ?: next++).also { id -> fresh[of(it)] = id } to it }.also { ids = fresh }
        }
    }

    private class Seqs {
        val ledger = Seq()
        val history = Seq()
        val reserve = Seq()
        val temp = Seq()
    }

    private const val PARENT = "NOT NULL REFERENCES claims_country(id) ON DELETE CASCADE"
    private val COUNTRY_COLS = setOf("name", "treasury", "pending", "members", "ledger", "history")
    private val CLAIM_COLS = setOf("country", "dim", "x", "z", "type", "owner")
    private val letters = ListSerializer(String.serializer())

    private class View(val d: Data, val q: Seqs) {
        val cs get() = d.countries.entries
    }

    private val ddl = listOf(
        "CREATE TABLE claims_country (id TEXT PRIMARY KEY, name TEXT NOT NULL, treasury INTEGER NOT NULL, pending INTEGER NOT NULL, data TEXT NOT NULL)",
        "CREATE TABLE claims_member (country TEXT $PARENT, player TEXT NOT NULL, rank TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY (country, player))",
        "CREATE TABLE claims_ledger (country TEXT $PARENT, seq INTEGER NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, amount INTEGER NOT NULL, balance INTEGER NOT NULL, actor TEXT, note TEXT NOT NULL, PRIMARY KEY (country, seq))",
        "CREATE TABLE claims_history (country TEXT $PARENT, seq INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY (country, seq))",
        "CREATE TABLE claims_claim (dim TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL, country TEXT $PARENT, type TEXT NOT NULL, owner TEXT, data TEXT NOT NULL, PRIMARY KEY (dim, x, z))",
        "CREATE TABLE claims_reserve (seq INTEGER PRIMARY KEY, dim TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL, country TEXT NOT NULL, expires INTEGER NOT NULL)",
        "CREATE TABLE claims_temp (seq INTEGER PRIMARY KEY, dim TEXT NOT NULL, pos INTEGER NOT NULL, start INTEGER NOT NULL, due INTEGER NOT NULL, fluid TEXT NOT NULL)",
        "CREATE TABLE claims_war (id TEXT PRIMARY KEY, data TEXT NOT NULL)",
        "CREATE TABLE claims_letter (player TEXT PRIMARY KEY, data TEXT NOT NULL)",
        "CREATE TABLE claims_state (id INTEGER PRIMARY KEY, day INTEGER NOT NULL, schema INTEGER NOT NULL, war_clock INTEGER NOT NULL)"
    )

    private val tables = listOf<Table<View>>(
        Table("claims_country", listOf("id"), listOf("name", "treasury", "pending", "data")) { v ->
            v.cs.map { (id, c) -> listOf(id, c.name, c.treasury, c.pending, split(Country.serializer(), c, COUNTRY_COLS)) }
        },
        Table("claims_member", listOf("country", "player"), listOf("rank", "data")) { v ->
            v.cs.flatMap { (id, c) -> c.members.map { (p, m) -> listOf(id, p, m.rank.name, split(Member.serializer(), m, setOf("rank"))) } }
        },
        Table("claims_ledger", listOf("country", "seq"), listOf("at", "kind", "amount", "balance", "actor", "note")) { v ->
            v.q.ledger.assign(v.cs.flatMap { (id, c) -> c.ledger.map { id to it } }) { it.second }
                .map { (n, p) -> val (id, e) = p; listOf(id, n, e.at, e.kind.name, e.amount, e.balance, e.actor, e.note) }
        },
        Table("claims_history", listOf("country", "seq"), listOf("data")) { v ->
            v.q.history.assign(v.cs.flatMap { (id, c) -> c.history.map { id to it } }) { it.second }
                .map { (n, p) -> listOf(p.first, n, Db.json.encodeToString(DayStat.serializer(), p.second)) }
        },
        Table("claims_claim", listOf("dim", "x", "z"), listOf("country", "type", "owner", "data")) { v ->
            v.d.claims.filter { it.country in v.d.countries }.map { listOf(it.dim, it.x, it.z, it.country, it.type, it.owner, split(Claim.serializer(), it, CLAIM_COLS)) }
        },
        Table("claims_reserve", listOf("seq"), listOf("dim", "x", "z", "country", "expires")) { v ->
            v.q.reserve.assign(v.d.reserves) { it }.map { (n, r) -> listOf(n, r.dim, r.x, r.z, r.country, r.until) }
        },
        Table("claims_temp", listOf("seq"), listOf("dim", "pos", "start", "due", "fluid")) { v ->
            v.q.temp.assign(v.d.temp) { it }.map { (n, t) -> listOf(n, t.dim, t.pos, t.start, t.due, t.fluid) }
        },
        Table("claims_war", listOf("id"), listOf("data")) { v -> v.d.wars.map { listOf(it.id, Db.json.encodeToString(War.serializer(), it)) } },
        Table("claims_letter", listOf("player"), listOf("data")) { v -> v.d.letters.entries.map { (p, l) -> listOf(p, Db.json.encodeToString(letters, l)) } },
        Table("claims_state", listOf("id"), listOf("day", "schema", "war_clock")) { v -> listOf(listOf(0, v.d.day, v.d.schema, v.d.warClock)) }
    )

    private var seqs: Seqs? = null

    private val schema = Migration("claims", 1, "schema", legacy = listOf(FILE), ddl = ddl) { world ->
        val d = Legacy.read(world, FILE, Data.serializer()) ?: Data(schema = Data.CURRENT)
        Realm.reset(d)
        val v = View(d, Seqs())
        tables.forEach { it.insert(this, v) }
        return@Migration { verify(this, d) }
    }

    fun register() = Migrations.register("claims", listOf(schema), after = listOf("libs"))

    fun load(): Data {
        val q = Seqs()
        val d = Db.read { read(this, q) }
        seqs = q
        View(d, q).let { v -> tables.forEach { it.prime(v) } }
        SavePoint.register("claims.realm") { sync(Realm.data) }
        return d
    }

    fun unload() {
        seqs = null
    }

    fun sync(d: Data): (Session.() -> Unit)? {
        val q = seqs ?: return null
        val v = View(d, q)
        return SavePoint.join(tables.map { it.sync(v) })
    }

    private fun <T> split(s: KSerializer<T>, v: T, drop: Set<String>) =
        JsonObject(Db.encode(s, v).jsonObject - drop).toString()

    private fun <T> join(s: KSerializer<T>, data: String, vararg cols: Pair<String, JsonElement>): T =
        Db.json.decodeFromJsonElement(s, JsonObject(Db.json.parseToJsonElement(data).jsonObject + cols))

    private fun read(s: Session, q: Seqs): Data {
        val countries = s.query("SELECT id, name, treasury, pending, data FROM claims_country ORDER BY rowid") { r ->
            r.string("id") to join(Country.serializer(), r.string("data"),
                "name" to JsonPrimitive(r.string("name")), "treasury" to JsonPrimitive(r.long("treasury")), "pending" to JsonPrimitive(r.long("pending")))
        }.toMap(LinkedHashMap())
        s.query("SELECT country, player, rank, data FROM claims_member ORDER BY rowid") { r ->
            countries.getValue(r.string("country")).members[r.string("player")] = join(Member.serializer(), r.string("data"), "rank" to JsonPrimitive(r.string("rank")))
        }
        s.query("SELECT country, seq, at, kind, amount, balance, actor, note FROM claims_ledger ORDER BY seq") { r ->
            val e = LedgerEntry(r.long("at"), LedgerKind.valueOf(r.string("kind")), r.long("amount"), r.long("balance"), r.stringOrNull("actor"), r.string("note"))
            q.ledger.load(e, r.long("seq"))
            countries.getValue(r.string("country")).ledger += e
        }
        s.query("SELECT country, seq, data FROM claims_history ORDER BY seq") { r ->
            val h = Db.json.decodeFromString(DayStat.serializer(), r.string("data"))
            q.history.load(h, r.long("seq"))
            countries.getValue(r.string("country")).history += h
        }
        val claims = s.query("SELECT dim, x, z, country, type, owner, data FROM claims_claim ORDER BY rowid") { r ->
            join(Claim.serializer(), r.string("data"), "dim" to JsonPrimitive(r.string("dim")), "x" to JsonPrimitive(r.int("x")), "z" to JsonPrimitive(r.int("z")),
                "country" to JsonPrimitive(r.string("country")), "type" to JsonPrimitive(r.string("type")), "owner" to JsonPrimitive(r.stringOrNull("owner")))
        }
        val reserves = s.query("SELECT seq, dim, x, z, country, expires FROM claims_reserve ORDER BY seq") { r ->
            Reserve(r.string("dim"), r.int("x"), r.int("z"), r.string("country"), r.long("expires")).also { q.reserve.load(it, r.long("seq")) }
        }
        val temp = s.query("SELECT seq, dim, pos, start, due, fluid FROM claims_temp ORDER BY seq") { r ->
            TempBlock(r.string("dim"), r.long("pos"), r.long("start"), r.long("due"), r.string("fluid")).also { q.temp.load(it, r.long("seq")) }
        }
        val wars = s.query("SELECT data FROM claims_war ORDER BY rowid") { Db.json.decodeFromString(War.serializer(), it.string("data")) }
        val mail = s.query("SELECT player, data FROM claims_letter ORDER BY rowid") { r ->
            r.string("player") to Db.json.decodeFromString(letters, r.string("data")).toMutableList()
        }.toMap(LinkedHashMap())
        val d = Data(countries, claims.toMutableList(), reserves.toMutableList(), letters = mail, temp = temp.toMutableList(), wars = wars.toMutableList(), schema = Data.CURRENT)
        s.one("SELECT day, schema, war_clock FROM claims_state WHERE id = 0") { r ->
            d.day = r.long("day")
            d.schema = r.int("schema")
            d.warClock = r.long("war_clock")
        }
        return d
    }

    private fun verify(s: Session, expected: Data) {
        Verify.sameJson("claims data", Data.serializer(), expected, read(s, Seqs()))
        val cs = expected.countries.values
        val counts = listOf(
            cs.size.toLong(), cs.sumOf { it.members.size }.toLong(), cs.sumOf { it.ledger.size }.toLong(), cs.sumOf { it.history.size }.toLong(),
            expected.claims.size.toLong(), cs.sumOf { it.treasury + it.pending }, cs.sumOf { c -> c.ledger.sumOf { it.amount } }
        )
        val actual = s.one(
            "SELECT (SELECT COUNT(*) FROM claims_country) AS a, (SELECT COUNT(*) FROM claims_member) AS b, (SELECT COUNT(*) FROM claims_ledger) AS c, " +
                "(SELECT COUNT(*) FROM claims_history) AS d, (SELECT COUNT(*) FROM claims_claim) AS e, " +
                "(SELECT COALESCE(SUM(treasury + pending), 0) FROM claims_country) AS f, (SELECT COALESCE(SUM(amount), 0) FROM claims_ledger) AS g"
        ) { r -> listOf("a", "b", "c", "d", "e", "f", "g").map(r::long) }
        Verify.equal("claims counts and money sums", counts, actual)
    }
}
