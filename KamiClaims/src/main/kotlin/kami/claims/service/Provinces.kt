package kami.claims.service

import kami.claims.Config
import kami.claims.Country
import kami.claims.ProvinceOffer
import kami.claims.Realm
import kami.claims.TaxMode
import kami.claims.now
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.chat.spur
import kotlin.math.max

object Provinces {
    private val s get() = Config.s

    val delegatedRights = listOf(
        "claim, unclaim and change the type of your land",
        "move your capital",
        "set your plot tax and plot lapse timers",
        "change your protection rules and your colour",
        "create and change your jobs"
    )

    val keptRights = listOf("your treasury, they can't withdraw from it", "your members, ranks and invitations", "your name")

    fun tributeText(mode: TaxMode, amount: Double) = if (mode == TaxMode.PERCENT) "${(amount * 100).toInt()}% of your daily plot tax" else "${spur(amount.toLong())} every day"

    fun tribute(text: String, mode: TaxMode): Double {
        val v = text.toDoubleOrNull() ?: throw Fail("Expected a number.")
        return if (mode == TaxMode.PERCENT) (v / 100).coerceIn(s.provinceTaxRateBounds[0], s.provinceTaxRateBounds[1]) else max(0.0, v)
    }

    fun cooldown(c: Country) = (c.independenceDeclinedAt + s.independenceCooldownDays * s.dayMillis - now()).coerceAtLeast(0)

    private fun expiry() = now() + s.inviteDays * s.dayMillis

    private fun ownProvince(parent: Country, child: Country) { if (child.parent != parent.id) throw Fail("That is not your province.") }

    private fun canHold(parent: Country) { if (parent.parent != null) throw Fail("A province cannot have its own provinces.") }

    fun agreementLines(child: Country, parent: Country, offer: ProvinceOffer) =
        listOf("Become a province of {${parent.name}}? {${child.name}} gives up authority over its land.", "Tribute: {${tributeText(offer.mode, offer.amount)}}.") +
            delegatedRights.map { "{${parent.name}} may $it." } + keptRights.map { "You keep $it." } +
            listOf("You can't leave on your own. Only {${parent.name}} can release you or grant independence.")

    fun invite(parent: Country, target: Country, mode: TaxMode, amount: Double) {
        canHold(parent)
        if (target.id == parent.id) throw Fail("A country cannot be its own province.")
        if (target.parent == parent.id) throw Fail("Already your province.")
        target.provinceInvites[parent.id] = ProvinceOffer(expiry(), mode, amount)
        Mail.officers(target, "{${parent.name}} invites you to become their province. Read the terms in the country screen.")
    }

    fun request(child: Country, target: Country) {
        if (child.parent != null) throw Fail("Already a province.")
        if (target.id == child.id) throw Fail("A country cannot be its own province.")
        if (target.parent != null) throw Fail("{${target.name}} is a province itself and cannot hold one.")
        target.provinceRequests[child.id] = expiry()
        Mail.officers(target, "{${child.name}} wants to become your province. Answer in the country screen.")
    }

    fun offer(child: Country, parent: Country): ProvinceOffer {
        if (child.parent != null) throw Fail("Already a province.")
        val offer = child.provinceInvites[parent.id] ?: throw Fail("No invitation from that country.")
        if (offer.until < now()) { child.provinceInvites.remove(parent.id); throw Fail("The invitation expired.") }
        return offer
    }

    fun accept(child: Country, parent: Country) {
        val offer = offer(child, parent)
        canHold(parent)
        finalize(child, parent, offer.mode, offer.amount)
    }

    fun approve(parent: Country, child: Country, mode: TaxMode, amount: Double): ProvinceOffer {
        canHold(parent)
        if ((parent.provinceRequests[child.id] ?: 0) < now()) throw Fail("No valid request from that country.")
        if (child.parent != null) throw Fail("That country already has a parent.")
        parent.provinceRequests.remove(child.id)
        val offer = ProvinceOffer(expiry(), mode, amount, answered = true)
        child.provinceInvites[parent.id] = offer
        Mail.officers(child, "{${parent.name}} accepted your request with a tribute of {${tributeText(offer.mode, offer.amount)}}. Review and sign it in the country screen.")
        return offer
    }

    fun deny(parent: Country, child: Country) {
        if (parent.provinceRequests.remove(child.id) == null) throw Fail("No request from that country.")
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
        Realm.syncFamily(parent.id)
        Mail.broadcast(child, "{${child.name}} is now a province of {${parent.name}}, paying {${tributeText(mode, amount)}}.")
        Mail.broadcast(parent, "{${child.name}} joined as a province.")
    }

    fun release(parent: Country, child: Country) {
        ownProvince(parent, child)
        child.parent = null
        child.provinceDebt = 0
        child.independenceRequested = false
        parent.provinces.remove(child.id)
        Realm.syncFamily(child.id)
        Realm.syncFamily(parent.id)
        Mail.broadcast(child, "{${child.name}} is independent again.", Tone.OK)
        Mail.broadcast(parent, "{${child.name}} is no longer your province.")
    }

    fun forgive(parent: Country, child: Country) {
        ownProvince(parent, child)
        child.provinceDebt = 0
    }

    fun askIndependence(child: Country) {
        val parent = child.parent?.let { Realm.country(it) } ?: throw Fail("Not a province.")
        if (child.independenceRequested) throw Fail("You already asked. {${parent.name}} has to answer first.")
        val wait = cooldown(child)
        if (wait > 0) throw Fail("{${parent.name}} declined recently. You can ask again in {${wait / 3_600_000 + 1}h}.", "COOLDOWN")
        child.independenceRequested = true
        Mail.officers(parent, "{${child.name}} asks for independence. Answer in the country screen.")
    }

    fun withdrawIndependence(child: Country) {
        if (!child.independenceRequested) throw Fail("No independence request to withdraw.")
        child.independenceRequested = false
    }

    fun decline(parent: Country, child: Country) {
        ownProvince(parent, child)
        if (!child.independenceRequested) throw Fail("{${child.name}} did not ask for independence.")
        child.independenceRequested = false
        child.independenceDeclinedAt = now()
        Mail.officers(child, "{${parent.name}} declined your independence request.", Tone.WARN)
    }

    fun setTribute(parent: Country, child: Country, mode: TaxMode, amount: Double) {
        ownProvince(parent, child)
        child.taxMode = mode
        child.taxAmount = amount
        Mail.officers(child, "{${parent.name}} changed your tribute to {${tributeText(mode, amount)}}.")
    }

    fun checkGive(parent: Country, child: Country, newParent: Country) {
        ownProvince(parent, child)
        if (newParent.id == child.id) throw Fail("A country cannot be its own province.")
        if (newParent.id == parent.id) throw Fail("{${child.name}} already belongs to you.")
        if (newParent.parent != null) throw Fail("{${newParent.name}} is a province itself and cannot hold one.")
    }

    fun give(parent: Country, child: Country, newParent: Country) {
        checkGive(parent, child, newParent)
        parent.provinces.remove(child.id)
        child.parent = newParent.id
        child.independenceRequested = false
        newParent.provinces += child.id
        Realm.syncFamily(parent.id)
        Realm.syncFamily(newParent.id)
        Mail.broadcast(child, "{${child.name}} was given to {${newParent.name}}.")
        Mail.broadcast(newParent, "{${child.name}} is now your province.")
    }
}
