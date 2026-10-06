package kami.claims.war

import kami.claims.Claim
import kami.claims.Config
import kami.claims.Country
import kami.claims.KamiClaims
import kami.claims.Key
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.TaxMode
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.research.Levels
import kami.claims.service.Diplomacy
import kami.claims.service.Provinces
import kami.claims.service.Upkeep
import kami.claims.service.Words
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import kotlin.math.floor

object Spoils {
    private val decisive = setOf(WarResult.ATTACKER, WarResult.DEFENDER, WarResult.PEACE)

    private class Gain(var chunks: Int = 0, var money: Long = 0, var provinces: Int = 0, var debt: Long = 0, val debtors: MutableSet<Country> = mutableSetOf())

    fun apply(war: War) {
        if (war.settled) return
        war.settled = true
        val defeat = war.result == WarResult.DEFENDER
        val gain = Gain()
        val a = Realm.country(war.attacker.main)?.takeIf { it.active }
        val d = Realm.country(war.defender.main)?.takeIf { it.active }
        val pair = if (a != null && d != null && war.result in decisive) (if (defeat) d to a else a to d) else null
        val touched = pair?.let { (winner, loser) ->
            val family = listOfNotNull(Realm.country(loser.parent)) + loser.provinces.mapNotNull(Realm::country)
            (listOf(winner, loser, Diplomacy.top(winner)) + family).distinct()
        }.orEmpty()
        pair?.let { (winner, loser) ->
            safe(war, "territory") { if (war.kind == WarKind.REBELLION) rebellion(war, winner, loser, defeat) else territory(war, winner, loser, defeat, gain) }
            if (!defeat) safe(war, "money") { charge(war, loser, winner, war.stakes.money, gain) }
        }
        safe(war, "escrow") {
            val escrowTo = if (defeat) d ?: a else a ?: d
            escrowTo?.let { WarDebt.escrow(it, LedgerKind.WAR_STAKE, war.escrow, Wars.name(if (it === a) war.defender.main else war.attacker.main)) }
            if (defeat && pair != null) gain.money += war.escrow
            war.escrow = 0
        }
        if (war.result == WarResult.ATTACKER && war.surrendered) safe(war, "fee") { a?.let { WarDebt.escrow(it, LedgerKind.WAR_FEE, war.fee, Wars.name(war.defender.main)) } }
        if (war.result != WarResult.WITHDRAWN && !war.silent) safe(war, "bill") { gain.debtors += WarDebt.bills(war) }
        val returned = mutableListOf<Country>()
        safe(war, "rebels") { returned += rebels(war) }
        safe(war, "bind") { gain.debtors.forEach(WarDebt::bind) }
        safe(war, "refresh") { (touched + returned).distinct().forEach { WarDebt.refresh(it) } }
        pair?.let { (winner, loser) ->
            tell(winner, "kami_claims.mail.war.outcome.won", loser, gain, Tone.OK)
            tell(loser, "kami_claims.mail.war.outcome.lost", winner, gain, Tone.BAD)
            if (gain.debt > 0) {
                Wars.mail(listOf(winner.id, loser.id), Phrase.of("kami_claims.mail.war.outcome.debt", v(loser.name), Words.money(gain.debt), v(winner.name)), Tone.WARN)
            }
        }
        Realm.changed()
    }

    private fun safe(war: War, what: String, block: () -> Unit) {
        runCatching(block).onFailure { KamiClaims.LOG.error("War ${war.id} settlement step $what failed", it) }
    }

    private fun rebels(war: War): List<Country> {
        val won = when (war.result) { WarResult.ATTACKER -> war.attacker; WarResult.DEFENDER -> war.defender; else -> return emptyList() }
        val back = mutableListOf<Country>()
        war.rebels.forEach { (rid, oid) ->
            val rebel = Realm.country(rid)?.takeIf { it.active && it.parent == null } ?: return@forEach
            val lord = Realm.country(oid)?.takeIf { it.active } ?: return@forEach
            if (war.side(oid) !== won || Wars.all(rebel).any { it !== war }) return@forEach
            Provinces.finalize(rebel, lord, rebel.taxMode, rebel.taxAmount)
            Mail.officers(rebel, Phrase.of("kami_claims.mail.war.rebel.returned", v(lord.name)), Tone.WARN)
            Mail.officers(lord, Phrase.of("kami_claims.mail.war.rebel.back", v(rebel.name)), Tone.OK)
            back += listOf(rebel, lord)
        }
        return back
    }

    private fun rebellion(war: War, winner: Country, loser: Country, defeat: Boolean) {
        if (!defeat && !(war.result == WarResult.PEACE && war.stakes.vassal) && winner.parent == loser.id) Provinces.detach(loser, winner)
    }

    private fun territory(war: War, winner: Country, loser: Country, defeat: Boolean, gain: Gain) {
        val room = (Levels.capacity(winner, Capacity.CHUNKS) - Levels.used(winner, Capacity.CHUNKS)).coerceAtLeast(0)
        val owned = if (defeat) pick(war, winner, loser, war.stakes.chunks.size) else war.stakes.chunks.mapNotNull { owned(it.key, loser) }
        val chunks = owned.take(room)
        chunks.forEach { move(it, winner) }
        gain.chunks = chunks.size
        val missing = if (defeat) war.stakes.chunks.drop(owned.size).sumOf { price(it.key) } else 0L
        charge(war, loser, winner, owned.drop(room).sumOf { price(it.key) } + missing, gain)
        provinces(war, loser, defeat).forEach { Provinces.transfer(loser, it, Diplomacy.top(winner)); gain.provinces++ }
        if (war.stakes.vassal || (defeat && war.stakes.provinces.isNotEmpty() && loser.parent != null)) vassal(loser, winner)
    }

    private fun provinces(war: War, loser: Country, defeat: Boolean): List<Country> {
        val pool = loser.provinces.mapNotNull(Realm::country).filterNot(Wars::rebel)
        if (!defeat) return pool.filter { it.id in war.stakes.provinces }
        var need = war.stakedPower
        if (need <= 0) return emptyList()
        val scored = pool.map { it to Power.score(it) }.sortedByDescending { it.second }
        scored.lastOrNull { it.second >= need }?.let { return listOf(it.first) }
        val out = mutableListOf<Country>()
        for ((p, score) in scored) {
            if (need <= 0) break
            out += p
            need -= score
        }
        return out
    }

    private fun vassal(loser: Country, winner: Country) {
        val holder = Diplomacy.top(winner)
        if (holder.id == loser.id || Wars.rebel(loser)) return
        loser.provinces.mapNotNull(Realm::country).forEach { Provinces.detach(loser, it) }
        Realm.country(loser.parent)?.let { old ->
            arrears(loser, old)
            Provinces.detach(old, loser)
        }
        Provinces.finalize(loser, holder, TaxMode.FLAT, 0.0)
    }

    internal fun arrears(child: Country, parent: Country) = WarDebt.owe(child, parent.id, WarDebt.times(child.provinceDebt.toLong(), tribute(child)))

    private fun tribute(c: Country) = when (c.taxMode) {
        TaxMode.PERCENT -> floor(Upkeep.summary(c).income * c.taxAmount).toLong()
        TaxMode.FLAT -> c.taxAmount.toLong()
    }.coerceAtLeast(0)

    private fun pick(war: War, winner: Country, loser: Country, want: Int): List<Claim> {
        val pool = Realm.claims(loser.id).filter { !it.capital }.associateByTo(LinkedHashMap()) { it.key }
        val picked = mutableListOf<Claim>()
        val frontier = ArrayDeque<Claim>()
        fun take(c: Claim) {
            picked += c
            Realm.neighbors(c.key).forEach { k -> pool.remove(k)?.let(frontier::addLast) }
        }
        war.picks.forEach { r -> if (picked.size < want) pool.remove(r.key)?.let(::take) }
        val seeds = ArrayDeque(pool.values.sortedBy { c -> Realm.neighbors(c.key).none { Realm.index[it]?.country == winner.id } })
        while (picked.size < want) take(frontier.removeFirstOrNull() ?: generateSequence { seeds.removeFirstOrNull() }.firstOrNull { pool.remove(it.key) != null } ?: break)
        return picked
    }

    private fun move(cl: Claim, to: Country) {
        cl.owner?.let { Mail.direct(it, Phrase.of("kami_claims.mail.plot_lost", v("${cl.x}, ${cl.z}")), Tone.BAD) }
        Realm.data.countries[cl.country]?.safeChunks?.remove(ChunkRef(cl.dim, cl.x, cl.z))
        val type = cl.type.takeIf { it in Config.s.types && Features.lockReason(to, Features.claimType(it)) == null } ?: Config.s.defaultType
        Realm.unclaim(cl, false)
        Realm.add(Claim(to.id, cl.dim, cl.x, cl.z, type))
    }

    private fun charge(war: War, from: Country, to: Country, amount: Long, gain: Gain) {
        if (amount <= 0) return
        val paid = WarDebt.transfer(from, to, amount, LedgerKind.WAR_STAKE)
        gain.money += paid
        if (war.silent) return
        val owed = WarDebt.owe(from, to.id, amount - paid)
        gain.debt += owed
        if (owed > 0) gain.debtors += from
    }

    private fun owned(k: Key, c: Country) = Realm.index[k]?.takeIf { it.country == c.id && !it.capital }

    private fun price(k: Key) = (Realm.index[k]?.let(Realm::price) ?: Config.s.types[Config.s.defaultType]?.price ?: 0).toLong()

    private fun tell(c: Country, key: String, other: Country, gain: Gain, tone: Tone) {
        val chunks = Words.count("kami_claims.unit.chunk", gain.chunks)
        val provinces = Words.count("kami_claims.unit.province", gain.provinces)
        Mail.officers(c, Phrase.of(key, v(other.name), chunks, Words.money(gain.money), provinces), tone)
    }
}
