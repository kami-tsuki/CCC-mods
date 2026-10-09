package kami.economy.quest

import kami.economy.LOG
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.db.Table
import kotlinx.serialization.encodeToString

internal class QuestData(val quests: Collection<Quest>, val deliveries: Collection<QuestDelivery>, val clock: QuestClock)

internal object QuestTables {
    val all = listOf(
        Table<QuestData>("eco_quest", listOf("id"), listOf("item", "rarity", "lot_price", "lot", "amount", "filled", "course", "price_offset", "created_at", "expires_at", "state", "boosts", "winner", "ended_at", "pick")) { d ->
            d.quests.map { listOf(it.id, it.item, it.rarity, it.lotPrice, it.lot, it.amount, it.filled, it.course, it.offset, it.createdAt, it.expiresAt, it.state.name, Db.json.encodeToString(it.boosts), it.winner, it.endedAt, it.pick) }
        },
        Table<QuestData>("eco_quest_delivery", listOf("ref"), listOf("quest", "player", "country", "qty", "paid", "at")) { d ->
            d.deliveries.map { listOf(it.key, it.quest, it.player, it.country, it.qty, it.paid, it.at) }
        },
        Table<QuestData>("eco_quest_state", listOf("id"), listOf("next", "last_rare", "last_mythic", "last_legendary")) { d ->
            listOf(listOf(1, d.clock.next, d.clock.last[0], d.clock.last[1], d.clock.last[2]))
        }
    )

    fun read(s: Session): QuestData {
        val quests = s.query("SELECT * FROM eco_quest ORDER BY id") { r ->
            runCatching { Quest(
                r.long("id"), r.string("item"), r.int("rarity"), r.int("lot_price"), r.int("lot"), r.int("amount"), r.int("filled"), r.double("course"), r.double("price_offset"),
                r.long("created_at"), r.long("expires_at"), QuestState.valueOf(r.string("state")), Db.json.decodeFromString(r.string("boosts")), r.stringOrNull("winner"), r.longOrNull("ended_at"), r.string("pick")
            ) }.onFailure { LOG.error("Skipped unreadable quest row", it) }.getOrNull()
        }.filterNotNull()
        val deliveries = s.query("SELECT * FROM eco_quest_delivery ORDER BY at, rowid") { r ->
            QuestDelivery(r.long("quest"), r.string("player"), r.stringOrNull("country"), r.int("qty"), r.long("paid"), r.long("at"), r.string("ref"))
        }
        val clock = s.one("SELECT * FROM eco_quest_state WHERE id = 1") { r ->
            QuestClock(r.long("next"), longArrayOf(r.long("last_rare"), r.long("last_mythic"), r.long("last_legendary")))
        }
        return QuestData(quests, deliveries, clock ?: QuestClock(next = -1))
    }
}
