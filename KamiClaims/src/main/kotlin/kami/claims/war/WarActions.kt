package kami.claims.war

import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.Config
import kami.claims.gov.Perm
import kami.claims.now
import kami.claims.service.Fail
import kami.claims.service.NeedsConfirm
import kami.claims.service.Service
import kami.claims.service.Words.chunks
import kami.claims.service.Words.num
import kami.claims.service.Words.days
import kami.claims.service.Words.money
import kami.claims.service.Words.v
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer

object WarActions {
    private const val MAX_RECT = 256L

    fun act(p: ServerPlayer, name: String, a: List<String>): Phrase {
        val c = Service.need(p, Perm.WAR)
        val confirmed = a.lastOrNull() == "confirm"
        val args = if (confirmed) a.dropLast(1) else a
        return when (name) {
            "war_declare" -> declare(p, c, args, confirmed)
            "war_offer" -> offer(p, c, args, confirmed)
            "war_accept" -> accept(c, args, confirmed)
            "war_decline" -> Negotiation.decline(c, between(c, partner(args)))
            "war_pick" -> pick(p, c, args)
            "war_fight" -> Wars.fight(c, other(args))
            "war_agree" -> resolved(name, c, Wars.agreeable(c, other(args)), confirmed) { Wars.agree(c, it) }
            "war_withdraw" -> resolved(name, c, Wars.withdrawable(c, other(args)), confirmed) { Wars.withdraw(c, it) }
            "war_capitulate" -> resolved(name, c, Wars.surrenderable(c, other(args)), confirmed) { Wars.capitulate(c, it) }
            "war_join" -> join(c, partner(args), confirmed)
            "war_safe" -> safe(p, c, args)
            "war_repay" -> WarDebt.pay(c, partner(args), args.getOrNull(1)?.toLongOrNull() ?: throw Fail("kami_claims.error.number"))
            else -> throw Fail("kami_claims.error.unknown_action")
        }
    }

    private fun safe(p: ServerPlayer, c: Country, a: List<String>): Phrase {
        val here = Key(p.level().dimension().location().toString(), p.chunkPosition().x, p.chunkPosition().z)
        return when (a.firstOrNull()?.lowercase()) {
            "add" -> SafeChunks.set(c, here)
            "remove" -> SafeChunks.unset(c, here)
            "list" -> SafeChunks.status(c)
            else -> throw Fail("kami_claims.error.missing_argument")
        }
    }

    private fun join(c: Country, main: Country, confirmed: Boolean): Phrase {
        val war = Joining.war(c, main)
        val rebel = Joining.check(c, war, main).firstOrNull { it.first === c }?.second
        if (!confirmed) {
            val opponents = Joining.opponents(war, main)
            val allies = opponents.filter { it.id in c.alliances }.joinToString(", ") { it.name }
            throw NeedsConfirm(listOfNotNull(
                Phrase.of("kami_claims.confirm.war_join.title", v(main.name), v(Wars.opponent(war, main).name)),
                Phrase.of("kami_claims.confirm.war_join.effect", v(opponents.joinToString(", ") { it.name })),
                Phrase.of("kami_claims.confirm.war_join.alliance", v(allies)).takeIf { allies.isNotEmpty() },
                rebel?.let { Phrase.of("kami_claims.confirm.war_join.rebellion", v(it.name)) }
            ))
        }
        return Joining.join(c, war, main)
    }

    private fun other(a: List<String>): Country? = a.firstOrNull()?.let { Realm.live(it) ?: throw Fail("kami_claims.error.unknown_country") }

    private class Terms {
        var kind = WarKind.REGULAR
        var cash: Long? = null
        var vassal = false
        var sure = false
        var ceasefire = 0
        var extend = 0
        var peace = false
        var none = false
        var bill: Int? = null
        val provinces = linkedSetOf<String>()
        val land = linkedSetOf<ChunkRef>()
        val staked get() = cash != null || vassal || provinces.isNotEmpty() || land.isNotEmpty()
        val negotiated get() = ceasefire > 0 || extend > 0 || peace || none || bill != null
    }

    private fun terms(p: ServerPlayer, target: Country, args: List<String>, confirmed: Boolean): Terms {
        val t = Terms().also { it.sure = confirmed }
        val tokens = args.iterator()
        fun next() = if (tokens.hasNext()) tokens.next() else throw Fail("kami_claims.error.missing_argument")
        fun int() = next().toIntOrNull() ?: throw Fail("kami_claims.error.war.stake.token")
        fun day() = next().toIntOrNull()?.takeIf { it > 0 } ?: throw Fail("kami_claims.error.war.offer.days")
        while (tokens.hasNext()) when (tokens.next().lowercase()) {
            "money" -> t.cash = if (t.cash != null) throw Fail("kami_claims.error.war.stake.token") else next().toLongOrNull()?.takeIf { it >= 0 } ?: throw Fail("kami_claims.error.war.stake.money")
            "vassal" -> t.vassal = true
            "province" -> t.provinces += (Realm.country(next()) ?: throw Fail("kami_claims.error.unknown_country")).id
            "chunks" -> { t.land += area(p.level().dimension().location().toString(), int(), int(), int(), int(), target.id); if (t.land.size > MAX_RECT) throw Fail("kami_claims.error.war.stake.rect", num(MAX_RECT)) }
            "rebellion" -> t.kind = WarKind.REBELLION
            "ceasefire" -> t.ceasefire = if (t.ceasefire != 0) throw Fail("kami_claims.error.war.stake.token") else day()
            "extend" -> t.extend = if (t.extend != 0) throw Fail("kami_claims.error.war.stake.token") else day()
            "peace" -> t.peace = true
            "bill" -> t.bill = if (t.bill != null) throw Fail("kami_claims.error.war.stake.token") else int().takeIf { it in 0..100 } ?: throw Fail("kami_claims.error.war.offer.bill", num(100))
            "nostakes" -> t.none = if (t.none) throw Fail("kami_claims.error.war.stake.token") else true
            else -> throw Fail("kami_claims.error.war.stake.token")
        }
        return t
    }

    private fun Terms.stakes() = Stakes(land.toList(), cash ?: 0, vassal, provinces.toList())

    private fun declare(p: ServerPlayer, c: Country, a: List<String>, confirmed: Boolean): Phrase {
        val target = other(a) ?: throw Fail("kami_claims.error.missing_argument")
        val t = terms(p, target, a.drop(1), confirmed)
        if (t.negotiated) throw Fail("kami_claims.error.war.stake.token")
        val stakes = t.stakes()
        val fee = Wars.checkDeclare(c, target, t.kind, stakes)
        if (!t.sure) throw NeedsConfirm(listOfNotNull(
            Phrase.of("kami_claims.confirm.war_declare.title", v(target.name)),
            Phrase.of("kami_claims.confirm.war_declare.effect", days(Config.s.war.declarationDays), days(Config.s.war.preparationDays)),
            Phrase.of("kami_claims.confirm.war_declare.rebellion").takeIf { t.kind == WarKind.REBELLION },
            Phrase.of("kami_claims.confirm.war_declare.money", money(stakes.money)).takeIf { stakes.money > 0 },
            Phrase.of("kami_claims.confirm.war_declare.chunks", chunks(t.land.size)).takeIf { t.land.isNotEmpty() },
            Phrase.of("kami_claims.confirm.war_declare.vassal", v(target.name)).takeIf { t.vassal },
            Phrase.of("kami_claims.confirm.war_declare.provinces", v(t.provinces.joinToString(", ", transform = Wars::name))).takeIf { t.provinces.isNotEmpty() },
            Phrase.of("kami_claims.confirm.war_declare.fee", money(fee), money(StakeRules.value(stakes, target)))
        ))
        return Wars.declare(c, target, t.kind, stakes)
    }

    private fun between(c: Country, other: Country) = Realm.data.wars.firstOrNull { it.active && setOf(it.attacker.main, it.defender.main) == setOf(c.id, other.id) } ?: throw Fail("kami_claims.error.war.none")

    private fun partner(a: List<String>) = other(a) ?: throw Fail("kami_claims.error.missing_argument")

    private fun offer(p: ServerPlayer, c: Country, a: List<String>, confirmed: Boolean): Phrase {
        val other = partner(a)
        val war = between(c, other)
        val t = terms(p, Wars.country(war.defender.main), a.drop(1), confirmed)
        if (t.kind != WarKind.REGULAR || t.none && t.staked) throw Fail("kami_claims.error.war.stake.token")
        val stakes = if (t.none || t.staked) t.stakes() else null
        if (stakes == null && !t.negotiated) throw Fail("kami_claims.error.war.offer.empty")
        val o = WarOffer(c.id, stakes, t.ceasefire, t.peace, t.extend, now(), t.bill)
        if (t.sure) return Negotiation.offer(c, war, o)
        Negotiation.validate(c, war, o)
        throw NeedsConfirm(listOf(Phrase.of("kami_claims.confirm.war_offer", v(other.name), Negotiation.terms(war, o))))
    }

    private fun accept(c: Country, a: List<String>, confirmed: Boolean): Phrase {
        val war = between(c, partner(a))
        if (!confirmed) {
            val o = war.offer?.takeIf { it.from != c.id } ?: throw Fail("kami_claims.error.war.offer.none")
            Negotiation.check(c, war, o)
            throw NeedsConfirm(listOf(Phrase.of("kami_claims.confirm.war_accept", v(Wars.opponent(war, c).name), Negotiation.terms(war, o))))
        }
        return Negotiation.accept(c, war)
    }

    private fun pick(p: ServerPlayer, c: Country, a: List<String>): Phrase {
        val other = partner(a)
        val war = between(c, other)
        if (war.phase != WarPhase.SETTLEMENT || war.settled || war.stakes.chunks.isEmpty() || war.result != WarResult.DEFENDER || war.defender.main != c.id) throw Fail("kami_claims.error.war.pick.none")
        val want = war.stakes.chunks.size
        if (a.getOrNull(1)?.lowercase() == "clear") {
            war.picks.clear()
            Realm.changed()
            return Phrase.of("kami_claims.done.war.pick_clear")
        }
        val n = a.drop(1).map { it.toIntOrNull() ?: throw Fail("kami_claims.error.war.stake.token") }
        if (n.size != 4) throw Fail("kami_claims.error.missing_argument")
        val dim = p.level().dimension().location().toString()
        if (dim != war.stakes.chunks.first().dim) throw Fail("kami_claims.error.war.pick.dimension")
        val room = want - war.picks.size
        if (room <= 0) throw Fail("kami_claims.error.war.pick.full", num(want))
        val found = area(dim, n[0], n[1], n[2], n[3], other.id).filter { it !in war.picks }
            .ifEmpty { throw Fail("kami_claims.error.war.pick.empty") }
            .take(room)
        val keys = found.mapTo(LinkedHashSet()) { it.key }
        val picked = war.picks.mapTo(HashSet()) { it.key }
        if (!StakeRules.connected(keys) || keys.none { k -> Realm.neighbors(k).any { it in picked || Realm.index[it]?.country == c.id } }) throw Fail("kami_claims.error.war.pick.connected")
        war.picks += found
        Realm.changed()
        return Phrase.of("kami_claims.done.war.pick", chunks(found.size), num(war.picks.size), num(want))
    }

    private fun area(dim: String, x1: Int, z1: Int, x2: Int, z2: Int, owner: String): List<ChunkRef> {
        val xs = minOf(x1, x2)..maxOf(x1, x2)
        val zs = minOf(z1, z2)..maxOf(z1, z2)
        if ((xs.last.toLong() - xs.first + 1) * (zs.last.toLong() - zs.first + 1) > MAX_RECT) throw Fail("kami_claims.error.war.stake.rect", num(MAX_RECT))
        return xs.flatMap { x -> zs.map { z -> ChunkRef(dim, x, z) } }.filter { r -> Realm.at(dim, r.x, r.z)?.let { it.country == owner && !it.capital } == true }
            .ifEmpty { throw Fail("kami_claims.error.war.stake.rect_empty") }
    }

    private fun resolved(name: String, c: Country, war: War, confirmed: Boolean, run: (War) -> Phrase): Phrase {
        if (!confirmed) throw NeedsConfirm(listOf(Phrase.of("kami_claims.confirm.$name", v(Wars.opponent(war, c).name))))
        return run(war)
    }
}
