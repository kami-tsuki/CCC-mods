package kami.claims.service

import kami.claims.net.ResearchSync
import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.TradePolicy
import kami.claims.economy.Treasury
import kami.claims.research.Features
import kami.claims.research.Progress
import kami.claims.now
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase

object Diplomacy {
    const val MAX_TARIFF = 50
    const val TARIFF_STEP = 5

    fun top(c: Country) = Realm.country(c.parent) ?: c

    fun sameFamily(a: Country, b: Country) = top(a).id == top(b).id

    fun allied(a: Country, b: Country) = b.id in a.alliances

    fun policy(from: Country, to: Country) = from.tradePolicy[to.id] ?: TradePolicy()

    fun embargoes(from: Country, to: Country) = listOf(from, top(from)).any { it.tradePolicy[to.id]?.embargo == true }

    fun embargoed(a: Country, b: Country) = !sameFamily(a, b) && (embargoes(a, b) || embargoes(b, a))

    fun relation(a: Country, b: Country): String = when {
        sameFamily(a, b) -> "family"
        embargoed(a, b) -> "embargo"
        allied(a, b) -> "allied"
        else -> "neutral"
    }

    fun tariff(buyer: Country, seller: Country) = if (sameFamily(buyer, seller)) 0 else policy(buyer, seller).tariffPct

    fun creditTariff(c: Country, amount: Long): Long {
        if (amount <= 0) return 0
        val applied = Treasury.move(c, LedgerKind.TARIFF, amount)
        Progress.report(c, "taxes", "", applied)
        Realm.changed()
        return applied
    }

    private fun foreign(c: Country, target: Country) {
        if (sameFamily(c, target)) throw Fail("kami_claims.error.trade_family")
    }

    fun propose(c: Country, target: Country): Phrase {
        Features.require(c, Features.ALLIANCES)
        foreign(c, target)
        if (allied(c, target)) throw Fail("kami_claims.error.already_allied", v(target.name))
        if (embargoed(c, target)) throw Fail("kami_claims.error.alliance_embargo")
        if (target.id in c.allianceOffers) return accept(c, target)
        target.allianceOffers[c.id] = now() + Config.s.inviteDays * Config.s.dayMillis
        Mail.officers(target, Phrase.of("kami_claims.mail.alliance_offer", v(c.name)))
        return Phrase.of("kami_claims.done.alliance_offered", v(target.name))
    }

    fun accept(c: Country, from: Country): Phrase {
        Features.require(c, Features.ALLIANCES)
        val until = c.allianceOffers.remove(from.id) ?: throw Fail("kami_claims.error.no_alliance_offer")
        if (until < now()) throw Fail("kami_claims.error.invite_expired")
        if (embargoed(c, from)) throw Fail("kami_claims.error.alliance_embargo")
        c.alliances += from.id
        from.alliances += c.id
        ResearchSync.refresh(c)
        ResearchSync.refresh(from)
        from.allianceOffers.remove(c.id)
        Progress.report(c, "alliance", from.id, 1, "alliance:${from.id}")
        Progress.report(from, "alliance", c.id, 1, "alliance:${c.id}")
        Realm.syncAllies()
        Mail.officers(from, Phrase.of("kami_claims.mail.alliance_accepted", v(c.name)))
        return Phrase.of("kami_claims.done.alliance_formed", v(from.name))
    }

    fun decline(c: Country, from: Country): Phrase {
        c.allianceOffers.remove(from.id) ?: throw Fail("kami_claims.error.no_alliance_offer")
        return Phrase.of("kami_claims.done.alliance_declined", v(from.name))
    }

    fun end(c: Country, other: Country): Phrase {
        if (!allied(c, other)) throw Fail("kami_claims.error.not_allied", v(other.name))
        c.alliances.remove(other.id)
        other.alliances.remove(c.id)
        ResearchSync.refresh(c)
        ResearchSync.refresh(other)
        Realm.syncAllies()
        Mail.officers(other, Phrase.of("kami_claims.mail.alliance_ended", v(c.name)), Tone.WARN)
        return Phrase.of("kami_claims.done.alliance_ended", v(other.name))
    }

    fun setTariff(c: Country, target: Country, pct: Int): Phrase {
        foreign(c, target)
        if (pct !in 0..MAX_TARIFF || pct % TARIFF_STEP != 0) throw Fail("kami_claims.error.tariff_step", v(TARIFF_STEP), v(MAX_TARIFF))
        store(c, target, TradePolicy(pct, policy(c, target).embargo))
        Mail.officers(target, Phrase.of("kami_claims.mail.tariff", v(c.name), v(pct)))
        return Phrase.of("kami_claims.done.tariff", v(target.name), v(pct))
    }

    fun setEmbargo(c: Country, target: Country, on: Boolean): Phrase {
        foreign(c, target)
        if (on) Features.require(c, Features.EMBARGOES)
        if (on && allied(c, target)) throw Fail("kami_claims.error.embargo_ally", v(target.name))
        store(c, target, TradePolicy(policy(c, target).tariffPct, on))
        if (on) target.allianceOffers.remove(c.id)
        Mail.officers(target, Phrase.of(if (on) "kami_claims.mail.embargo_on" else "kami_claims.mail.embargo_off", v(c.name)), if (on) Tone.WARN else Tone.INFO)
        return Phrase.of(if (on) "kami_claims.done.embargo_on" else "kami_claims.done.embargo_off", v(target.name))
    }

    private fun store(c: Country, target: Country, p: TradePolicy) {
        if (p.tariffPct == 0 && !p.embargo) c.tradePolicy.remove(target.id) else c.tradePolicy[target.id] = p
    }
}
