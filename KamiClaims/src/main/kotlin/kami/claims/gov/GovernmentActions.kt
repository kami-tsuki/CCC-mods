package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.Names
import kami.claims.now
import kami.claims.research.Loans
import kami.claims.research.Tokens
import kami.claims.service.Fail
import kami.claims.service.Service
import kami.claims.service.Words.days
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.claims.war.Wars
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer

object GovernmentActions {
    const val FEE = 100_000L
    const val COOLDOWN_DAYS = 7L
    private const val MIN_COUNCIL_MEMBERS = 2

    fun act(p: ServerPlayer, name: String, args: List<String>): Phrase? = when (name) {
        "gov_apply" -> apply(p, args)
        "withdraw_request" -> request(p, args)
        "withdraw_approve" -> Council.approve(p, args.getOrNull(0) ?: throw Fail("kami_claims.error.missing_argument"))
        "heir_set" -> setHeir(p, args.getOrNull(0).orEmpty())
        "vote_president" -> vote(p, args.getOrNull(0) ?: throw Fail("kami_claims.error.missing_argument"))
        else -> null
    }

    private fun setHeir(p: ServerPlayer, target: String): Phrase {
        val c = Service.home(p)
        val actor = p.stringUUID
        if (!Permissions.isPresident(c, actor)) throw Fail("kami_claims.error.gov_president")
        if (c.government.levers.leadership !in HEIR_LEADERSHIPS) throw Fail("kami_claims.error.heir_mode")
        if (target == actor) throw Fail("kami_claims.error.heir_self")
        if (target.isNotEmpty()) {
            val member = c.members[target] ?: throw Fail("kami_claims.error.not_member")
            if (!c.government.heirEligible(member)) throw Fail("kami_claims.error.heir_age")
        }
        c.government.heir = target.ifEmpty { null }
        Audit.record(c, actor, "heir_set", target)
        return if (target.isEmpty()) Phrase.of("kami_claims.done.heir_cleared") else Phrase.of("kami_claims.done.heir_set", v(Names.of(p.server, target)))
    }

    private fun vote(p: ServerPlayer, candidate: String): Phrase {
        val c = Service.home(p)
        val actor = p.stringUUID
        if (actor !in c.members) throw Fail("kami_claims.error.not_member")
        if (c.government.levers.leadership != Leadership.ELECTED) throw Fail("kami_claims.error.vote_mode")
        if (candidate !in c.members) throw Fail("kami_claims.error.not_member")
        c.government.votes[actor] = candidate
        return Phrase.of("kami_claims.done.voted", v(Names.of(p.server, candidate)))
    }

    private fun request(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.WITHDRAW)
        val amount = a.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: throw Fail("kami_claims.error.number")
        return Council.request(c, p.stringUUID, amount.toLong())
    }

    private fun apply(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.DETAILS)
        val actor = p.stringUUID
        if (!Permissions.isPresident(c, actor)) throw Fail("kami_claims.error.gov_president")
        val preset = govChoice<PresetId>(a, 0)
        val levers = Levers.parse(a, 1)
        val reset = RESET_RANKS in a.drop(9)
        val government = c.government
        if (preset == government.preset && levers == government.levers && !reset) return Phrase.of("kami_claims.done.gov_unchanged")
        requireChangeable(c, levers, reset)
        if (c.parent != null && levers.provinces != government.levers.provinces) throw Fail("kami_claims.error.gov_province_locked")
        Tokens.spend(c, Tokens.SETTINGS, FEE, actor)
        government.switch(c, preset, levers, reset)
        government.changedAt = now()
        government.changes++
        Audit.record(c, actor, "gov_apply", preset.key, "$levers reset=$reset")
        return Phrase.of("kami_claims.done.gov_applied", v(preset.key))
    }

    internal fun requireStructure(c: Country, levers: Levers, reset: Boolean) {
        if (levers.ranks == RankStructure.FLAT && c.ranks.size > FLAT_RANK_CAP && !reset) throw Fail("kami_claims.error.gov_flat_ranks", num(FLAT_RANK_CAP))
        if (levers.treasury == TreasuryControl.COUNCIL && c.members.size < MIN_COUNCIL_MEMBERS) throw Fail("kami_claims.error.gov_council_members")
    }

    internal fun requireChangeable(c: Country, levers: Levers, reset: Boolean) {
        requireStructure(c, levers, reset)
        if (Wars.involved(c)) throw Fail("kami_claims.error.gov_war")
        if (Loans.inDefault(c) || c.provinceDebt > 0) throw Fail("kami_claims.error.gov_default")
        val wait = c.government.changedAt + COOLDOWN_DAYS * Config.s.dayMillis - now()
        if (c.government.changedAt > 0 && wait > 0) throw Fail("kami_claims.error.gov_cooldown", days((wait + Config.s.dayMillis - 1) / Config.s.dayMillis))
    }
}
