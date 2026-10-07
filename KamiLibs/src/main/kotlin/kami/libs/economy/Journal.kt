package kami.libs.economy

import kami.libs.admin.Page
import kami.libs.admin.Where
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import kotlin.math.abs

object Journal {
    @Serializable
    class Entry(val id: Long, val at: Long, val party: String, val delta: Long, val flow: Flow, val counterparty: String? = null, val note: String = "", val ref: String = "")

    class Filter(
        val party: Party? = null,
        val flows: Set<Flow> = emptySet(),
        val min: Long? = null,
        val max: Long? = null,
        val from: Long? = null,
        val to: Long? = null,
        val text: String? = null
    )

    fun leg(party: Party, delta: Long, flow: Flow, counterparty: Party? = null, note: String = "", ref: String = "") {
        if (delta == 0L) return
        Db.write { journalLeg(System.currentTimeMillis(), party, delta, flow, counterparty, note, ref) }
        countMoney(party, delta, flow)
    }

    fun query(filter: Filter, page: Int, size: Int = 50): Page<Entry> {
        val w = Where()
        filter.party?.let { w.add("party = ?", it.key) }
        if (filter.flows.isNotEmpty()) w.add("flow IN (${filter.flows.joinToString { "?" }})", *filter.flows.map { it.name }.toTypedArray())
        filter.min?.let { w.add("ABS(delta) >= ?", it) }
        filter.max?.let { w.add("ABS(delta) <= ?", it) }
        filter.from?.let { w.add("at >= ?", it) }
        filter.to?.let { w.add("at < ?", it) }
        filter.text?.let { "%${Where.like(it)}%" }?.let { w.add("(note LIKE ? ESCAPE '!' OR counterparty LIKE ? ESCAPE '!' OR ref LIKE ? ESCAPE '!')", it, it, it) }
        return w.page("libs_journal", "id, at, party, delta, flow, counterparty, note, ref", "id DESC", page, size) {
            Entry(it.long("id"), it.long("at"), it.string("party"), it.long("delta"), Flow.named(it.string("flow")), it.stringOrNull("counterparty"), it.string("note"), it.string("ref"))
        }
    }
}

internal fun Session.journalLeg(at: Long, party: Party, delta: Long, flow: Flow, counterparty: Party?, note: String, ref: String) =
    exec("INSERT INTO libs_journal (at, party, delta, flow, counterparty, note, ref) VALUES (?, ?, ?, ?, ?, ?, ?)", at, party.key, delta, flow.name, counterparty?.key, note, ref)

internal fun countMoney(party: Party, delta: Long, flow: Flow) {
    val f = flow.name.lowercase()
    Stats.add("money.$f", party.key, if (delta > 0) "in" else "out", abs(delta))
    Stats.add("money.tx", party.key, f)
}
