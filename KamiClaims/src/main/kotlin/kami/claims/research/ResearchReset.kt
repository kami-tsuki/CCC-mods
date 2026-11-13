package kami.claims.research

import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.net.ResearchSync
import kami.claims.social.Mail
import kami.libs.log.Log
import kami.libs.stats.Stats
import kami.libs.text.Phrase

object ResearchReset {
    const val VERSION = 3

    class Refunded(val total: Long, val kept: Int)

    private val log = Log.of("research")

    fun run(): Int {
        val stale = Realm.data.countries.values.filter { it.active && it.research.treeVersion != VERSION }
        if (stale.isEmpty()) return 0
        if (!Research.treesCurrent) {
            log.error("Research reset to version {} refused: the trees under config/kami/claims/research/trees are older than the bundled ones", VERSION)
            return 0
        }
        return stale.count { reset(it) }
    }

    fun founded(country: Country): Country = country.also { it.research.treeVersion = VERSION }

    private fun reset(country: Country): Boolean {
        val state = country.research
        val had = state.done.isNotEmpty() || state.queue.isNotEmpty() || state.parked.isNotEmpty() || country.buffs.enabled.isNotEmpty()
        var kept = 0
        val settle = { entry: QueueEntry -> refunded(country, entry).also { if (!it) kept++ } }
        val unpaid = state.queue.filterNot(settle)
        state.queue.clear()
        state.parked.removeAll(settle)
        state.parked += unpaid
        if (kept > 0) log.warn("{}: {} paid research entries could not be refunded and stay parked for an admin refund", country.id, kept)
        state.done.clear()
        state.visited.clear()
        country.buffs.enabled.clear()
        country.buffs.billed.clear()
        state.treeVersion = VERSION
        Realm.changed(false)
        ResearchSync.touch(country)
        if (had) {
            log.info("{}: research reset for tree version {}", country.id, VERSION)
            Stats.event("research.reset", "c:${country.id}", VERSION.toString())
            Mail.broadcast(country, Phrase.of("kami_claims.research.mail.reset"))
        }
        return had
    }

    private fun owed(entry: QueueEntry): Long? = if (!entry.paid) 0 else entry.paidCost.takeIf { it >= 0 }

    fun refunded(country: Country, entry: QueueEntry): Boolean {
        val amount = owed(entry)
        if (amount == null) {
            log.warn("{}: paid entry {} has no recorded cost, keeping it", country.id, entry.node)
            return false
        }
        if (amount <= 0) return true
        val got = Treasury.bank(country, LedgerKind.RESEARCH, amount, null, entry.node, capped = false)
        if (got in 1 until amount) entry.paidCost = amount - got
        return got >= amount
    }

    fun purgeParked(country: Country): Int = country.research.parked.size.also {
        country.research.parked.clear()
        Realm.changed(false)
    }

    fun refundParked(country: Country): Refunded {
        var total = 0L
        val paid = country.research.parked.filter { entry ->
            val amount = owed(entry) ?: 0
            val done = refunded(country, entry)
            total += amount - if (done) 0 else entry.paidCost.coerceAtLeast(0)
            done
        }
        country.research.parked.removeAll(paid)
        if (paid.isNotEmpty()) Realm.changed(false)
        return Refunded(total, country.research.parked.size)
    }
}
