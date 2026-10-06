package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.net.ResearchSync
import kami.claims.service.Announce
import kami.claims.service.Diplomacy
import kami.claims.service.Fail
import kami.claims.service.Words.money
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import kotlin.math.abs

object WarDebt {
    private val garnished = setOf(LedgerKind.DEPOSIT, LedgerKind.PLOT_TAX, LedgerKind.TRIBUTE_IN, LedgerKind.TARIFF)

    private fun power(side: WarSide, id: String) = if (id == side.main) side.power else side.powers[id] ?: side.power

    private fun weighted(war: War, side: WarSide): Map<String, Double> {
        val opp = war.opponent(side).let { o -> (o.powers.values + o.power).max() }
        return side.bill.filter { it.value > 0 }.mapValues { (id, spur) -> spur * Power.multiplier(power(side, id), opp) }
    }

    fun bills(war: War): List<Country> {
        val w = war.sides.map { weighted(war, it) }
        val (ta, td) = w.map { it.values.sum() }
        if (ta == td) return emptyList()
        val i = if (ta > td) 0 else 1
        val side = war.sides[i]
        val creditor = Realm.country(war.opponent(side).main)?.takeIf { it.active } ?: return emptyList()
        val net = (abs(ta - td) * war.billPct.coerceIn(0, 100) / 100).toLong()
        if (net <= 0) return emptyList()
        val main = Realm.country(side.main)?.takeIf { it.active }
        val shares = w[i].mapNotNull { (id, x) -> Realm.country(id)?.takeIf { it.active }?.let { it to x } }.sortedBy { it.first.id == side.main }
        if (shares.isEmpty()) return emptyList()
        val total = shares.sumOf { it.second }
        val debtors = mutableListOf<Country>()
        var left = net
        var paid = 0L
        var debt = 0L
        shares.forEachIndexed { n, (payer, x) ->
            val share = if (n == shares.lastIndex) left else (net * (x / total)).toLong().coerceIn(0, left)
            left -= share
            if (share <= 0) return@forEachIndexed
            val got = transfer(payer, creditor, share, LedgerKind.WAR_BILL)
            val cover = main?.takeIf { it !== payer }?.let { transfer(it, creditor, share - got, LedgerKind.WAR_BILL) } ?: 0L
            val debtor = main ?: payer
            val owed = owe(debtor, creditor.id, share - got - cover)
            paid += got + cover
            debt += owed
            if (owed > 0 && debtor !in debtors) debtors += debtor
            Mail.officers(payer, Phrase.of("kami_claims.mail.war.bill.paid", v(creditor.name), money(share), money(got + cover), money(owed)), Tone.BAD)
        }
        Mail.officers(creditor, Phrase.of("kami_claims.mail.war.bill.received", v(Wars.name(side.main)), money(paid + debt), money(paid), money(debt)), Tone.OK)
        return debtors
    }

    fun bind(debtor: Country) {
        if (debtor.warOverlord != null || !debtor.active) return
        val creditor = debtor.warDebts.entries.sortedByDescending { it.value }
            .firstNotNullOfOrNull { (id, debt) -> Realm.country(id)?.takeIf { debt > 0 && it.active && !Diplomacy.sameFamily(debtor, it) } } ?: return
        debtor.warOverlord = creditor.id
        Reputation.change(debtor, "deal", -Config.s.war.repDealBroken)
        Wars.mail(listOf(debtor.id, creditor.id), Phrase.of("kami_claims.mail.war.province.bound", v(debtor.name), v(creditor.name)), Tone.WARN)
        Announce.fire("warProvince", debtor, mapOf("province" to debtor.name, "parent" to creditor.name, "other" to creditor.name))
        refresh(debtor, creditor)
        Realm.changed()
    }

    private fun release(debtor: Country) {
        val creditor = Realm.country(debtor.warOverlord ?: return)
        debtor.warOverlord = null
        Wars.mail(listOfNotNull(debtor.id, creditor?.id), Phrase.of("kami_claims.mail.war.province.released", v(debtor.name), v(creditor?.name ?: debtor.name)), Tone.OK)
        refresh(debtor, creditor)
        bind(debtor)
        Realm.changed()
    }

    internal fun refresh(vararg cs: Country?) = cs.filterNotNull().forEach { Realm.refreshFree(it); ResearchSync.refresh(it) }

    internal fun owe(debtor: Country, creditor: String, debt: Long): Long {
        if (debt <= 0) return 0
        debtor.warDebts.merge(creditor, debt) { x, y -> plus(x, y) }
        return debt
    }

    internal fun plus(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    internal fun times(a: Long, b: Long) = if (a <= 0 || b <= 0) 0L else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b

    fun garnish(c: Country, kind: LedgerKind, amount: Long) {
        if (kind !in garnished || amount <= 0 || c.warDebts.isEmpty()) return
        var pool = (amount * Config.s.war.debtGarnish).toLong().coerceAtMost(c.treasury)
        for ((id, debt) in c.warDebts.entries.sortedByDescending { it.value }) {
            val creditor = Realm.country(id)?.takeIf { it.active }
            if (creditor == null || debt <= 0) { c.warDebts.remove(id); if (c.warOverlord == id) release(c); continue }
            if (pool <= 0) break
            val got = transfer(c, creditor, minOf(pool, debt), LedgerKind.WAR_DEBT)
            pool -= got
            reduce(c, creditor, got)
        }
    }

    fun forgive(creditor: String) {
        Realm.data.countries.values.forEach { it.warDebts.remove(creditor) }
        Realm.data.countries.values.filter { it.warOverlord == creditor }.forEach(::release)
    }

    fun pay(debtor: Country, creditor: Country, amount: Long): Phrase {
        if (amount <= 0) throw Fail("kami_claims.error.number")
        val debt = debtor.warDebts[creditor.id] ?: throw Fail("kami_claims.error.war.debt.none", v(creditor.name))
        val want = minOf(amount, debt)
        if (debtor.treasury < want) throw Fail("kami_claims.error.war.debt.funds", money(want))
        val got = transfer(debtor, creditor, want, LedgerKind.WAR_DEBT)
        reduce(debtor, creditor, got)
        Mail.officers(creditor, Phrase.of("kami_claims.mail.war.debt.repaid", v(debtor.name), money(got)), Tone.OK)
        Realm.changed()
        return Phrase.of("kami_claims.done.war.repaid", money(got), v(creditor.name), money(debtor.warDebts[creditor.id] ?: 0L))
    }

    internal fun transfer(from: Country, to: Country, amount: Long, kind: LedgerKind): Long {
        val want = amount.coerceAtMost(from.treasury)
        if (want <= 0) return 0
        val got = Treasury.move(to, kind, want, note = from.name, capped = kind == LedgerKind.WAR_BILL || kind == LedgerKind.WAR_DEBT)
        escrow(from, kind, -got, to.name)
        return got
    }

    internal fun escrow(c: Country, kind: LedgerKind, amount: Long, note: String) = Treasury.move(c, kind, amount, note = note, capped = false)

    private fun reduce(debtor: Country, creditor: Country, amount: Long) {
        if (amount <= 0) return
        val left = (debtor.warDebts[creditor.id] ?: return) - amount
        if (left > 0) {
            debtor.warDebts[creditor.id] = left
            return
        }
        debtor.warDebts.remove(creditor.id)
        Reputation.change(debtor, "deal", Config.s.war.repDealKept)
        Wars.mail(listOf(debtor.id, creditor.id), Phrase.of("kami_claims.mail.war.debt.cleared", v(debtor.name), v(creditor.name)), Tone.OK)
        if (debtor.warOverlord == creditor.id) release(debtor)
    }
}
