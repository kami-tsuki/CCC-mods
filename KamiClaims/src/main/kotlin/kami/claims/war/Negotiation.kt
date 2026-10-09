package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.now
import kami.claims.research.Loans
import kami.claims.service.Announce
import kami.claims.service.Fail
import kami.claims.service.Words.count
import kami.claims.service.Words.days
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase

object Negotiation {
    private val s get() = Config.s.war
    private val day get() = Config.s.dayMillis
    private const val MINUTE = 60_000L
    private const val OFFER_GAP = 10 * MINUTE
    private val extendable = setOf(WarPhase.DECLARATION, WarPhase.PREPARATION, WarPhase.CEASEFIRE)

    fun offer(c: Country, war: War, o: WarOffer): Phrase {
        validate(c, war, o)
        val other = Wars.opponent(war, c)
        war.offer = o
        war.offeredAt[c.id] = now()
        Mail.officers(other, Phrase.of("kami_claims.mail.war.offer", v(c.name), terms(war, o)), Tone.WARN)
        if (war.phase == WarPhase.DECLARATION && c.id == war.defender.main && !war.renegotiated) {
            war.renegotiated = true
            Wars.answered(war, c, "renegotiate", "renegotiate")
        }
        Realm.changed()
        return Phrase.of("kami_claims.done.war.offer", v(other.name))
    }

    internal fun validate(c: Country, war: War, o: WarOffer) {
        main(c, war)
        if (o.from != c.id) throw Fail("kami_claims.error.war.offer.side")
        val wait = (war.offeredAt[c.id] ?: 0L) + OFFER_GAP - now()
        if (wait > 0) throw Fail("kami_claims.error.war.offer.cooldown", count("kami_claims.unit.minute", (wait + MINUTE - 1) / MINUTE))
        check(c, war, o)
    }

    fun accept(c: Country, war: War): Phrase {
        val o = received(c, war)
        val diff = check(c, war, o)
        val attacker = Wars.country(war.attacker.main)
        val from = Wars.opponent(war, c)
        val text = terms(war, o)
        war.offer = null
        agreed(o)?.let {
            WarDebt.escrow(attacker, LedgerKind.WAR_STAKE, -diff, Wars.name(war.defender.main))
            war.escrow = it.money
            war.stakes = it
            if (war.phase != WarPhase.DECLARATION) war.stakedPower = Power.staked(it)
        }
        o.billPct?.let { war.billPct = it }
        if (o.extendDays > 0) {
            war.phaseEnds += o.extendDays * day
            war.extendedDays += o.extendDays
        }
        if (o.ceasefireDays > 0) {
            war.phase = WarPhase.CEASEFIRE
            war.ceasefireStart = now()
            war.phaseEnds = war.ceasefireStart + o.ceasefireDays * day
            war.ceasefires++
            war.extendedDays = 0
            val vars = mapOf("country" to attacker.name, "other" to Wars.name(war.defender.main), "phase" to "ceasefire")
            Announce.fire("warPhase", attacker, vars, mapOf("phase" to Phrase.of("kami_claims.war.phase.ceasefire")))
        }
        Mail.officers(from, Phrase.of("kami_claims.mail.war.offer_accepted", v(c.name), text))
        if (o.peace) Wars.end(war, WarResult.PEACE)
        Realm.changed()
        return Phrase.of("kami_claims.done.war.offer_accepted", v(from.name))
    }

    fun decline(c: Country, war: War): Phrase {
        received(c, war)
        val from = Wars.opponent(war, c)
        war.offer = null
        Mail.officers(from, Phrase.of("kami_claims.mail.war.offer_declined", v(c.name)))
        Realm.changed()
        return Phrase.of("kami_claims.done.war.offer_declined", v(from.name))
    }

    private fun agreed(o: WarOffer) = o.stakes ?: Stakes().takeIf { o.peace }

    internal fun check(c: Country, war: War, o: WarOffer): Long {
        legal(war, o)
        if ((o.stakes?.money ?: 0) > 0) Loans.requireNoDebt(c, "kami_claims.loans.error.disband")
        val attacker = Wars.country(war.attacker.main)
        return if (o.from == c.id && c.id != attacker.id) 0 else fund(attacker, war, o)
    }

    private fun fund(attacker: Country, war: War, o: WarOffer): Long {
        val diff = (agreed(o)?.money ?: war.escrow) - war.escrow
        if (diff > 0 && attacker.treasury < diff) throw Fail("kami_claims.error.war.offer.funds", money(diff))
        return diff
    }

    private fun main(c: Country, war: War) {
        if (c.id != war.attacker.main && c.id != war.defender.main) throw Fail("kami_claims.error.war.offer.side")
    }

    private fun received(c: Country, war: War): WarOffer {
        main(c, war)
        val o = war.offer ?: throw Fail("kami_claims.error.war.offer.none")
        if (o.from == c.id) throw Fail("kami_claims.error.war.offer.own")
        return o
    }

    private fun legal(war: War, o: WarOffer) {
        val p = war.phase
        if (!war.active || war.phaseEnds in 1..now()) throw Fail("kami_claims.error.war.none")
        if (o.stakes == null && !o.peace && o.ceasefireDays == 0 && o.extendDays == 0 && o.billPct == null) throw Fail("kami_claims.error.war.offer.empty")
        if (o.peace && (o.ceasefireDays != 0 || o.extendDays != 0)) throw Fail("kami_claims.error.war.offer.conflict")
        val peaceLate = o.peace && p == WarPhase.SETTLEMENT
        val pauseLate = o.ceasefireDays != 0 && p != WarPhase.WAR
        val extendLate = o.extendDays != 0 && p !in extendable
        val billEarly = o.billPct != null && p != WarPhase.SETTLEMENT
        if (peaceLate || pauseLate || extendLate || billEarly) throw Fail("kami_claims.error.war.offer.phase")
        o.billPct?.let { if (it !in 0..war.billPct) throw Fail("kami_claims.error.war.offer.bill", num(war.billPct)) }
        if (o.ceasefireDays != 0) {
            if (o.ceasefireDays !in 1..s.ceasefireMaxDays) throw Fail("kami_claims.error.war.offer.ceasefire", days(s.ceasefireMaxDays))
            if (war.ceasefires >= s.ceasefireMax) throw Fail("kami_claims.error.war.offer.ceasefire_max", num(s.ceasefireMax))
        }
        val cap = if (p == WarPhase.CEASEFIRE) ((war.ceasefireStart + s.ceasefireMaxDays * day - war.phaseEnds) / day).toInt() else Int.MAX_VALUE
        val left = minOf(s.extendMaxDays - war.extendedDays, cap)
        if (o.extendDays != 0 && o.extendDays !in 1..left) throw Fail("kami_claims.error.war.offer.extend", days(left.coerceAtLeast(0)))
        o.stakes?.let { n ->
            val cur = war.stakes
            val raised = n.money > cur.money || !cur.chunks.containsAll(n.chunks) || !cur.provinces.containsAll(n.provinces) || n.vassal && !cur.vassal
            if (p == WarPhase.SETTLEMENT && raised) throw Fail("kami_claims.error.war.offer.reduce")
            StakeRules.check(Wars.country(war.attacker.main), Wars.country(war.defender.main), war.kind, n, offer = true)
        }
    }

    internal fun terms(war: War, o: WarOffer) = listOfNotNull(
        o.stakes?.let { Phrase.of("kami_claims.war.term.stakes", money(StakeRules.value(it, Wars.country(war.defender.main)))) },
        Phrase.of("kami_claims.war.term.peace").takeIf { o.peace },
        Phrase.of("kami_claims.war.term.ceasefire", days(o.ceasefireDays)).takeIf { o.ceasefireDays > 0 },
        Phrase.of("kami_claims.war.term.extend", days(o.extendDays)).takeIf { o.extendDays > 0 },
        o.billPct?.let { Phrase.of("kami_claims.war.term.bill", num(it)) }
    ).reduce { a, b -> Phrase.of("kami_libs.format.list", a, b) }
}
