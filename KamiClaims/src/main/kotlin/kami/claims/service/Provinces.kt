package kami.claims.service

import kami.claims.Config
import kami.claims.Country
import kami.claims.ProvinceOffer
import kami.claims.Realm
import kami.claims.TaxMode
import kami.claims.now
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.research.Levels
import kami.claims.research.Loans
import kami.claims.research.Progress
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.claims.service.Words.v
import kami.libs.text.Phrase
import kotlin.math.max

object Provinces {
    private val s get() = Config.s

    val delegatedRights = listOf("land", "capital", "tax", "laws", "jobs", "housing").map { "kami_claims.province.right.delegated.$it" }

    val keptRights = listOf("treasury", "members", "name").map { "kami_claims.province.right.kept.$it" }

    fun tribute(text: String, mode: TaxMode): Double {
        val v = text.toDoubleOrNull() ?: throw Fail("kami_claims.error.number")
        return if (mode == TaxMode.PERCENT) (v / 100).coerceIn(s.provinceTaxRateBounds[0], s.provinceTaxRateBounds[1]) else max(0.0, v)
    }

    fun cooldown(c: Country) = (c.independenceDeclinedAt + s.independenceCooldownDays * s.dayMillis - now()).coerceAtLeast(0)

    private fun expiry() = now() + s.inviteDays * s.dayMillis

    private fun ownProvince(parent: Country, child: Country) { if (child.parent != parent.id) throw Fail("kami_claims.error.not_province") }

    private fun canHold(parent: Country) {
        if (parent.parent != null) throw Fail("kami_claims.error.province_nested")
        Features.requireRoom(parent, Capacity.PROVINCES, parent.provinces.size)
    }

    fun agreementLines(child: Country, parent: Country, offer: ProvinceOffer): List<Phrase> =
        listOf(
            Phrase.of("kami_claims.confirm.province.title", v(parent.name)),
            Phrase.of("kami_claims.confirm.province.authority", v(child.name)),
            Phrase.of("kami_claims.confirm.province.tribute", Words.tribute(offer.mode, offer.amount)),
            Phrase.of("kami_claims.confirm.province.bound", v(parent.name))
        )

    fun invite(parent: Country, target: Country, mode: TaxMode, amount: Double) {
        canHold(parent)
        if (target.id == parent.id) throw Fail("kami_claims.error.province_self")
        if (target.parent == parent.id) throw Fail("kami_claims.error.already_yours")
        target.provinceInvites[parent.id] = ProvinceOffer(expiry(), mode, amount)
        Mail.officers(target, Phrase.of("kami_claims.mail.province_invite", v(parent.name)))
    }

    fun request(child: Country, target: Country) {
        if (child.parent != null) throw Fail("kami_claims.error.already_province")
        Loans.requireNoLoans(child, "kami_claims.loans.error.province_join")
        if (target.id == child.id) throw Fail("kami_claims.error.province_self")
        if (target.parent != null) throw Fail("kami_claims.error.province_holder", v(target.name))
        target.provinceRequests[child.id] = expiry()
        Mail.officers(target, Phrase.of("kami_claims.mail.province_request", v(child.name)))
    }

    fun offer(child: Country, parent: Country): ProvinceOffer {
        if (child.parent != null) throw Fail("kami_claims.error.already_province")
        Loans.requireNoLoans(child, "kami_claims.loans.error.province_join")
        val offer = child.provinceInvites[parent.id] ?: throw Fail("kami_claims.error.no_province_invite")
        if (offer.until < now()) { child.provinceInvites.remove(parent.id); throw Fail("kami_claims.error.invite_expired") }
        return offer
    }

    fun accept(child: Country, parent: Country) {
        val offer = offer(child, parent)
        canHold(parent)
        finalize(child, parent, offer.mode, offer.amount)
    }

    fun approve(parent: Country, child: Country, mode: TaxMode, amount: Double): ProvinceOffer {
        canHold(parent)
        if ((parent.provinceRequests[child.id] ?: 0) < now()) throw Fail("kami_claims.error.no_province_request")
        if (child.parent != null) throw Fail("kami_claims.error.has_overlord")
        Loans.requireNoLoans(child, "kami_claims.loans.error.province_join")
        parent.provinceRequests.remove(child.id)
        val offer = ProvinceOffer(expiry(), mode, amount, answered = true)
        child.provinceInvites[parent.id] = offer
        Mail.officers(child, Phrase.of("kami_claims.mail.province_approved", v(parent.name), Words.tribute(offer.mode, offer.amount)))
        return offer
    }

    fun deny(parent: Country, child: Country) {
        if (parent.provinceRequests.remove(child.id) == null) throw Fail("kami_claims.error.no_province_request")
    }

    fun finalize(child: Country, parent: Country, mode: TaxMode, amount: Double) {
        child.provinces.forEach { pid -> Realm.country(pid)?.let { it.parent = parent.id }; parent.provinces += pid }
        child.provinces.clear()
        child.parent = parent.id
        child.taxMode = mode
        child.taxAmount = amount
        child.provinceDebt = 0
        child.independenceRequested = false
        child.provinceInvites.clear()
        child.provinceRequests.clear()
        parent.provinces += child.id
        Progress.report(parent, "province", child.id, 1, "province:${child.id}")
        Realm.syncAllies()
        Mail.broadcast(child, Phrase.of("kami_claims.mail.province_joined", v(child.name), v(parent.name), Words.tribute(mode, amount)))
        Mail.broadcast(parent, Phrase.of("kami_claims.mail.province_added", v(child.name)))
    }

    fun release(parent: Country, child: Country) {
        ownProvince(parent, child)
        child.parent = null
        child.provinceDebt = 0
        child.independenceRequested = false
        parent.provinces.remove(child.id)
        Realm.syncAllies()
        Mail.broadcast(child, Phrase.of("kami_claims.mail.independent", v(child.name)), Tone.OK)
        Mail.broadcast(parent, Phrase.of("kami_claims.mail.province_released", v(child.name)))
    }

    fun forgive(parent: Country, child: Country) {
        ownProvince(parent, child)
        child.provinceDebt = 0
    }

    fun askIndependence(child: Country) {
        val parent = child.parent?.let { Realm.country(it) } ?: throw Fail("kami_claims.error.not_a_province")
        if (child.independenceRequested) throw Fail("kami_claims.error.already_asked", v(parent.name))
        val wait = cooldown(child)
        if (wait > 0) throw Fail(Phrase.of("kami_claims.error.independence_cooldown", v(parent.name), v(Phrase.of("kami_libs.unit.hour.short", (wait / 3_600_000 + 1).toString()))), "COOLDOWN")
        child.independenceRequested = true
        Mail.officers(parent, Phrase.of("kami_claims.mail.independence_request", v(child.name)))
    }

    fun withdrawIndependence(child: Country) {
        if (!child.independenceRequested) throw Fail("kami_claims.error.no_independence_request")
        child.independenceRequested = false
    }

    fun decline(parent: Country, child: Country) {
        ownProvince(parent, child)
        if (!child.independenceRequested) throw Fail("kami_claims.error.no_independence_ask", v(child.name))
        child.independenceRequested = false
        child.independenceDeclinedAt = now()
        Mail.officers(child, Phrase.of("kami_claims.mail.independence_declined", v(parent.name)), Tone.WARN)
    }

    fun setTribute(parent: Country, child: Country, mode: TaxMode, amount: Double) {
        ownProvince(parent, child)
        child.taxMode = mode
        child.taxAmount = amount
        Mail.officers(child, Phrase.of("kami_claims.mail.tribute_changed", v(parent.name), Words.tribute(mode, amount)))
    }

    fun checkGive(parent: Country, child: Country, newParent: Country) {
        ownProvince(parent, child)
        if (newParent.id == child.id) throw Fail("kami_claims.error.province_self")
        if (newParent.id == parent.id) throw Fail("kami_claims.error.already_yours_named", v(child.name))
        if (newParent.parent != null) throw Fail("kami_claims.error.province_holder", v(newParent.name))
        canHold(newParent)
    }

    fun give(parent: Country, child: Country, newParent: Country) {
        checkGive(parent, child, newParent)
        parent.provinces.remove(child.id)
        child.parent = newParent.id
        child.independenceRequested = false
        newParent.provinces += child.id
        Progress.report(newParent, "province", child.id, 1, "province:${child.id}")
        Realm.syncAllies()
        Mail.broadcast(child, Phrase.of("kami_claims.mail.province_given", v(child.name), v(newParent.name)))
        Mail.broadcast(newParent, Phrase.of("kami_claims.mail.province_received", v(child.name)))
    }
}
