package kami.claims

import kami.claims.gov.Assignment
import kami.claims.gov.Contract
import kami.claims.gov.JobScope
import kami.claims.gov.Labour
import kami.claims.gov.Land
import kami.claims.gov.Leadership
import kami.claims.gov.Levers
import kami.claims.gov.Membership
import kami.claims.gov.PayModel
import kami.claims.gov.Perm
import kami.claims.gov.PresetId
import kami.claims.gov.Presets
import kami.claims.gov.ProvinceRule
import kami.claims.gov.RankStructure
import kami.claims.gov.Sector
import kami.claims.gov.Standing
import kami.claims.gov.TaskKind
import kami.claims.gov.TaxStyle
import kami.claims.gov.TreasuryControl
import kami.claims.war.War
import kami.claims.world.PlacedLog
import kami.libs.config.Configs
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            v.cs.flatMap { (id, c) -> c.members.map { (p, m) -> listOf(id, p, m.rank, split(Member.serializer(), m, setOf("rank"))) } }
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

    private val placed = Table<View>("claims_placed", listOf("dim", "pos"), listOf("placer", "seq")) { PlacedLog.rows() }

    private var seqs: Seqs? = null

    private val schema = Migration("claims", 1, "schema", legacy = listOf(FILE), ddl = ddl) { world ->
        val d = Legacy.read(world, FILE, JsonObject.serializer())?.let(::legacyData) ?: Data(schema = Data.CURRENT)
        Realm.reset(d)
        val v = View(d, Seqs())
        tables.forEach { it.insert(this, v) }
        return@Migration { verify(this, d) }
    }

    private val placedBlocks = Migration("claims", 2, "placed", ddl = listOf(
        "CREATE TABLE claims_placed (dim TEXT NOT NULL, pos INTEGER NOT NULL, placer TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (dim, pos))"
    ))

    private val news = Migration("claims", 3, "news", ddl = listOf(
        "CREATE TABLE news_article (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, country_id TEXT, country_name TEXT NOT NULL, involved TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL, body TEXT NOT NULL, authors TEXT NOT NULL, refs TEXT NOT NULL, template_key TEXT NOT NULL, template_args TEXT NOT NULL, created INTEGER NOT NULL, dedupe TEXT, deleted_by TEXT, deleted_at INTEGER, deleted_reason TEXT, corrected_note TEXT, discord TEXT NOT NULL)",
        "CREATE INDEX news_article_created ON news_article (created)",
        "CREATE INDEX news_article_country ON news_article (country_id, created)",
        "CREATE UNIQUE INDEX news_article_dedupe ON news_article (dedupe) WHERE dedupe IS NOT NULL",
        "CREATE TABLE news_draft (id INTEGER PRIMARY KEY, country_id TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL, body TEXT NOT NULL, contributors TEXT NOT NULL, lock_holder TEXT, lock_until INTEGER NOT NULL, lock_since INTEGER NOT NULL, updated INTEGER NOT NULL)",
        "CREATE INDEX news_draft_country ON news_draft (country_id)",
        "CREATE TABLE news_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
    ))

    fun register() = Migrations.register("claims", listOf(schema, placedBlocks, news), after = listOf("libs"))

    fun load(): Data {
        val q = Seqs()
        val d = Db.read { read(this, q).also { readPlaced(this) } }
        seqs = q
        View(d, q).let { v -> (tables + placed).forEach { it.prime(v) } }
        SavePoint.register("claims.realm") { sync(Realm.data) }
        return d
    }

    fun unload() {
        seqs = null
        PlacedLog.reset()
        kami.claims.press.NewsStore.reset()
    }

    fun sync(d: Data): (Session.() -> Unit)? {
        val q = seqs ?: return null
        val v = View(d, q)
        return SavePoint.join((tables + placed).map { it.sync(v) })
    }

    private fun <T> split(s: KSerializer<T>, v: T, drop: Set<String>) =
        JsonObject(Db.encode(s, v).jsonObject - drop).toString()

    private fun <T> join(s: KSerializer<T>, data: String, vararg cols: Pair<String, JsonElement>, prepare: (JsonObject) -> JsonObject = { it }): T =
        Db.json.decodeFromJsonElement(s, JsonObject(prepare(Db.json.parseToJsonElement(data).jsonObject) + cols))

    private val knownPerms = Perm.entries.map { it.name }.toSet()
    private val knownTasks = TaskKind.entries.map { it.name }.toSet()
    private val knownStandings = Standing.entries.map { it.name }.toSet()
    private val knownClaimants = Claimant.entries.map { it.name }.toSet()
    private val knownJobChoices = mapOf(
        "scope" to JobScope.entries.map { it.name }.toSet(),
        "payModel" to PayModel.entries.map { it.name }.toSet(),
        "assignment" to Assignment.entries.map { it.name }.toSet()
    )

    private inline fun <reified T : Enum<T>> names() = enumValues<T>().map { it.name }.toSet()

    private val leverChoices = mapOf(
        "labour" to names<Labour>(), "land" to names<Land>(), "taxes" to names<TaxStyle>(), "treasury" to names<TreasuryControl>(),
        "leadership" to names<Leadership>(), "ranks" to names<RankStructure>(), "provinces" to names<ProvinceRule>(), "membership" to names<Membership>()
    )

    private fun knownOnly(list: JsonElement, known: Set<String>) = JsonArray(list.jsonArray.filter { it.jsonPrimitive.content in known })

    private fun JsonElement.mapObject(change: (String, JsonElement) -> JsonElement) = JsonObject(jsonObject.mapValues { (key, value) -> change(key, value) })

    private fun withoutRemovedConstants(country: JsonObject): JsonObject = country.mapObject { key, value ->
        when (key) {
            "overlordPerms" -> knownOnly(value, knownPerms)
            "outsiders" -> JsonObject(value.jsonObject.filterValues { it.jsonPrimitive.content in knownStandings })
            "groups" -> JsonObject(value.jsonObject.filterKeys { it in knownClaimants }).mapObject { _, perms -> knownOnly(perms, knownPerms) }
            "ranks" -> JsonArray(value.jsonArray.map { rank -> rank.mapObject { field, x -> if (field == "perms") knownOnly(x, knownPerms) else x } })
            "jobDefs" -> value.mapObject { _, job -> withoutRemovedJobConstants(job.jsonObject) }
            "government" -> sanitisedGovernment(value.jsonObject)
            "contracts" -> JsonArray(value.jsonArray.filter { decodable(Contract.serializer(), it) })
            "sectors" -> JsonObject(value.jsonObject.filterValues { decodable(Sector.serializer(), it) })
            else -> value
        }
    }.let { JsonObject(it.filter { (key, value) -> lenient[key]?.invoke(value) != false }) }

    private val lenient = mapOf<String, (JsonElement) -> Boolean>(
        "payBudget" to { decodable(DaySpend.serializer(), it) }, "withdrawn" to { decodable(DaySpend.serializer(), it) },
        "creditLedger" to { decodable(CreditLedger.serializer(), it) }, "withdrawals" to { decodable(ListSerializer(WithdrawalRequest.serializer()), it) }
    )

    private fun <T> decodable(serializer: KSerializer<T>, element: JsonElement) = runCatching { Db.json.decodeFromJsonElement(serializer, element) }.isSuccess

    private fun sanitisedGovernment(government: JsonObject): JsonObject {
        val preset = PresetId.entries.firstOrNull { it.name == government["preset"]?.jsonPrimitive?.content } ?: PresetId.MIXED
        val stored = government["levers"]?.jsonObject.orEmpty()
        val levers = Db.encode(Levers.serializer(), Presets.levers(preset)).jsonObject.mapValues { (field, default) ->
            stored[field]?.takeIf { it.jsonPrimitive.content in leverChoices.getValue(field) } ?: default
        }
        return JsonObject(government + mapOf("preset" to JsonPrimitive(preset.name), "levers" to JsonObject(levers)))
    }

    private fun legacyData(raw: JsonObject): Data = Configs.json().decodeFromJsonElement(
        Data.serializer(),
        raw.mapObject { key, value -> if (key == "countries") value.mapObject { _, country -> withoutRemovedConstants(country.jsonObject) } else value }
    )

    private fun withoutRemovedJobConstants(job: JsonObject) = JsonObject(
        job.filter { (field, x) -> knownJobChoices[field]?.contains(x.jsonPrimitive.content) != false }
            .mapValues { (field, x) -> if (field == "tasks") JsonArray(x.jsonArray.filter { it.jsonObject["kind"]?.jsonPrimitive?.content in knownTasks }) else x }
    )

    private fun ledgerKind(name: String) = LedgerKind.entries.firstOrNull { it.name == name } ?: LedgerKind.ADJUST

    private fun readPlaced(s: Session) {
        PlacedLog.reset()
        s.query("SELECT dim, pos, placer, seq FROM claims_placed ORDER BY seq") { r -> PlacedLog.restore(r.string("dim"), r.long("pos"), r.string("placer"), r.long("seq")) }
    }

    private fun read(s: Session, q: Seqs): Data {
        val countries = s.query("SELECT id, name, treasury, pending, data FROM claims_country ORDER BY rowid") { r ->
            r.string("id") to join(Country.serializer(), r.string("data"),
                "name" to JsonPrimitive(r.string("name")), "treasury" to JsonPrimitive(r.long("treasury")), "pending" to JsonPrimitive(r.long("pending")), prepare = ::withoutRemovedConstants)
        }.toMap(LinkedHashMap())
        s.query("SELECT country, player, rank, data FROM claims_member ORDER BY rowid") { r ->
            countries.getValue(r.string("country")).members[r.string("player")] = join(Member.serializer(), r.string("data"), "rank" to JsonPrimitive(r.string("rank")))
        }
        s.query("SELECT country, seq, at, kind, amount, balance, actor, note FROM claims_ledger ORDER BY seq") { r ->
            val e = LedgerEntry(r.long("at"), ledgerKind(r.string("kind")), r.long("amount"), r.long("balance"), r.stringOrNull("actor"), r.string("note"))
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
