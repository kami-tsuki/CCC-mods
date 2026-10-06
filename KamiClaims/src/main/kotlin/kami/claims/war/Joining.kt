package kami.claims.war

import kami.claims.Country
import kami.claims.Realm
import kami.claims.TradePolicy
import kami.claims.now
import kami.claims.service.Announce
import kami.claims.service.Diplomacy
import kami.claims.service.Fail
import kami.claims.service.Provinces
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import kotlin.math.max

object Joining {
    private val open = setOf(WarPhase.DECLARATION, WarPhase.PREPARATION, WarPhase.CEASEFIRE)

    fun snapshot(war: War) {
        val mains = war.sides.map { it.main }
        fun add(ids: List<String>, sides: List<String>) = ids.filter { it !in mains }.forEach { war.eligible.getOrPut(it) { mutableSetOf() } += sides }
        war.sides.forEach { side ->
            val main = Realm.country(side.main) ?: return@forEach
            val allies = main.alliances.mapNotNull(Realm::country)
            add(allies.map { it.id } + allies.flatMap { it.provinces }, listOf(side.main))
            add(main.provinces.toList(), mains)
        }
    }

    fun war(c: Country, main: Country): War {
        val hits = Realm.data.wars.filter { it.active && it.sides.any { s -> s.main == main.id } && main.id in it.eligible[c.id].orEmpty() }
        val fresh = hits.filter { it.phase in open && it.side(c.id) == null }
        return fresh.singleOrNull() ?: hits.singleOrNull() ?: throw Fail(if (hits.isEmpty()) "kami_claims.error.war.join.none" else "kami_claims.error.war.which")
    }

    private fun side(war: War, main: Country) = war.sides.firstOrNull { it.main == main.id } ?: throw Fail("kami_claims.error.war.join.none")

    fun opponents(war: War, main: Country) = war.opponent(side(war, main)).countries.mapNotNull(Realm::country)

    private fun related(c: Country, war: War, main: Country) =
        c.id in main.alliances || war.sides.any { it.main == c.parent } || Realm.country(c.parent)?.let { main.id in it.alliances } == true

    fun can(c: Country, war: War, main: Country) = blocker(c, war, main) == null

    fun check(c: Country, war: War, main: Country): List<Pair<Country, Country>> {
        blocker(c, war, main)?.let { throw Fail(it) }
        return pairs(c, war, main)
    }

    private fun pairs(c: Country, war: War, main: Country): List<Pair<Country, Country>> {
        val foes = opponents(war, main)
        return foes.filter { it.id == c.parent }.map { c to it } + foes.filter { it.parent == c.id }.map { it to c }
    }

    private fun blocker(c: Country, war: War, main: Country): Phrase? {
        val t = now()
        val side = war.sides.firstOrNull { it.main == main.id }
        if (side == null || war.phase !in open || war.phaseEnds in 1..t || main.id !in war.eligible[c.id].orEmpty() || !related(c, war, main)) return Phrase.of("kami_claims.error.war.join.none")
        if (!c.active) return Phrase.of("kami_claims.error.war.join.inactive", v(c.name))
        if (war.side(c.id) != null) return Phrase.of("kami_claims.error.war.join.already")
        if (Wars.involved(c)) return Phrase.of("kami_claims.error.war.busy", v(c.name))
        if (c.warCooldownUntil > t) return Phrase.of("kami_claims.error.war.cooldown", v(c.name), Wars.hours(c.warCooldownUntil - t))
        val target = Realm.country(war.opponent(side).main) ?: return Phrase.of("kami_claims.error.war.none")
        val pair = Wars.pairUntil(c, target)
        if (pair > t) return Phrase.of("kami_claims.error.war.pair_cooldown", v(target.name), Wars.hours(pair - t))
        val pairs = pairs(c, war, main)
        pairs.forEach { (child, _) ->
            WarLocks.provinceBlock(child)?.let { return it }
            if (Realm.data.wars.any { it.active && child.id in it.stakes.provinces }) return Phrase.of("kami_claims.error.war.locked.province")
        }
        val rebels = pairs.map { it.first.id }.toSet()
        fun top(x: Country) = if (x.id in rebels) x.id else Diplomacy.top(x).id
        if (opponents(war, main).any { top(it) == top(c) }) return Phrase.of("kami_claims.error.war.family")
        return null
    }

    fun join(c: Country, war: War, main: Country): Phrase {
        val pairs = check(c, war, main)
        val side = side(war, main)
        val enemy = Wars.name(war.opponent(side).main)
        pairs.forEach { (child, parent) -> rebel(war, child, parent) }
        side.joined += c.id
        side.powers[c.id] = max(Power.score(c), side.power)
        if (opponents(war, main).count { sever(war, c, it) } > 0) Realm.syncAllies()
        Wars.mail(war.countries, Phrase.of("kami_claims.mail.war.joined", v(c.name), v(main.name), v(enemy)), Tone.WARN)
        Announce.fire("warJoined", c, mapOf("country" to c.name, "other" to enemy, "side" to main.name))
        Realm.changed()
        return Phrase.of("kami_claims.done.war.joined", v(main.name), v(enemy))
    }

    private fun rebel(war: War, child: Country, parent: Country) {
        war.rebels[child.id] = parent.id
        Spoils.arrears(child, parent)
        Reputation.rebellion(child, parent)
        Provinces.detach(parent, child)
        WarDebt.refresh(child, parent)
        Mail.officers(parent, Phrase.of("kami_claims.mail.war.rebel_joined", v(child.name)), Tone.BAD)
    }

    private fun sever(war: War, c: Country, o: Country): Boolean {
        listOf(c to o, o to c).forEach { (a, b) ->
            val p = Diplomacy.policy(a, b)
            val key = "${a.id}|${b.id}"
            if (key !in war.embargoes && (!p.embargo || Wars.imposed(a.id, b.id))) war.embargoes += key
            Diplomacy.store(a, b, TradePolicy(p.tariffPct, true))
            a.allianceOffers.remove(b.id)
        }
        if (o.id !in c.alliances) return false
        Diplomacy.dropAlliance(c, o)
        return true
    }
}
