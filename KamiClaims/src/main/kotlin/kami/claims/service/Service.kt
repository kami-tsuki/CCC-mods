package kami.claims.service

import kami.libs.util.toUuidOrNull
import kami.claims.net.ResearchSync
import kami.claims.*
import kami.claims.gov.Audit
import kami.claims.gov.CITIZEN
import kami.claims.gov.Council
import kami.claims.gov.GovActions
import kami.claims.gov.Membership
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.gov.Permissions
import kami.claims.gov.PresetId
import kami.claims.gov.Presets
import kami.claims.gov.Standing
import kami.claims.gov.TreasuryControl
import kami.claims.gov.changeRank
import kami.claims.gov.leaderChanged
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.research.Buffs
import kami.claims.research.Loans
import kami.claims.research.Capacity
import kami.claims.research.Deposit
import kami.claims.research.Features
import kami.claims.research.Tokens
import kami.claims.research.Levels
import kami.claims.research.Progress
import kami.claims.research.Queue
import kami.claims.research.ResearchReset
import kami.claims.service.Words.chunks
import kami.claims.service.Words.count
import kami.claims.service.Words.days
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.claims.war.Fame
import kami.claims.war.Reputation
import kami.claims.war.WarActions
import kami.claims.war.WarLocks
import kami.claims.war.Wars
import kami.claims.social.Perms
import kami.claims.world.Effects
import kami.libs.chat.Tone
import kami.libs.economy.CountryParty
import kami.libs.economy.Flow
import kami.libs.economy.Statement
import kami.libs.stats.Stats
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Fail(@Transient val phrase: Phrase, val reason: String = "", @Transient val target: Key? = null) : kami.libs.command.CommandFail(phrase::component) {
    constructor(key: String, vararg args: Any) : this(Phrase.of(key, *args))
}

class NeedsConfirm(@Transient val lines: List<Phrase>) : RuntimeException()

object Service {
    private val s get() = Config.s
    private var acting: String? = null

    fun here(p: ServerPlayer) = Key(p.level().dimension().location().toString(), p.chunkPosition().x, p.chunkPosition().z)
    fun home(p: ServerPlayer): Country {
        viewed(p)?.let { throw Fail("kami_claims.error.read_only", v(it.name)) }
        return Realm.of(p.stringUUID) ?: throw Fail("kami_claims.error.no_country")
    }

    private fun viewed(p: ServerPlayer): Country? =
        acting?.let { Realm.live(it) ?: throw Fail("kami_claims.error.unknown_country") }?.takeIf { it !== Realm.of(p.stringUUID) }

    fun act(p: ServerPlayer, name: String, a: List<String>, asCountry: String = ""): Phrase {
        acting = asCountry.ifBlank { null }
        try {
            return dispatch(p, name, a)
        } finally {
            acting = null
        }
    }

    internal fun need(p: ServerPlayer, perm: Perm): Country {
        permit(p, perm)
        val viewed = viewed(p) ?: return needOwn(p, perm)
        if (!Oversight.granted(viewed, p, perm)) throw Fail("kami_claims.error.read_only", v(viewed.name))
        return viewed
    }

    private fun needOwn(p: ServerPlayer, perm: Perm): Country {
        val c = home(p)
        if (!Permissions.holds(c, p.stringUUID, perm)) throw Fail("kami_claims.error.missing_permission", Words.perm(perm))
        return c
    }

    private fun needPresident(p: ServerPlayer): Country {
        val c = home(p)
        if (Permissions.president(c) != p.stringUUID) throw Fail("kami_claims.error.rank", Words.rank(c, PRESIDENT))
        return c
    }

    private fun needSpender(p: ServerPlayer, perm: Perm): Country {
        val c = need(p, perm)
        if (p.stringUUID in c.members && !Permissions.holds(c, p.stringUUID, Perm.SPEND)) throw Fail("kami_claims.error.missing_permission", Words.perm(Perm.SPEND))
        return c
    }

    private fun permit(p: ServerPlayer, perm: Perm) {
        if (!Perms.permitted(p, perm)) throw Fail("kami_claims.error.permission")
    }

    private fun actorId(c: Country, p: ServerPlayer): String? = p.stringUUID.takeIf { it in c.members }

    private fun arg(a: List<String>, i: Int) = a.getOrNull(i) ?: throw Fail("kami_claims.error.missing_argument")
    private fun num(a: List<String>, i: Int) = arg(a, i).toIntOrNull() ?: throw Fail("kami_claims.error.number")

    private fun long(a: List<String>, i: Int) = arg(a, i).toLongOrNull() ?: throw Fail("kami_claims.error.number")
    private fun <T : Enum<T>> find(values: Array<T>, text: String): T? = values.firstOrNull { it.name.equals(text, true) }
    private fun <T : Enum<T>> parse(values: Array<T>, text: String): T =
        find(values, text) ?: throw Fail("kami_claims.error.choice", v(values.joinToString { it.name.lowercase() }))

    private fun spot(p: ServerPlayer, a: List<String>, i: Int) =
        if (a.size > i + 1) Key(here(p).dim, num(a, i), num(a, i + 1)) else here(p)

    private fun who(p: ServerPlayer, a: List<String>, i: Int): String {
        val text = arg(a, i)
        return text.toUuidOrNull()?.toString()
            ?: Names.id(p.server, text)
            ?: throw Fail("kami_claims.error.unknown_player")
    }

    private fun known(p: ServerPlayer, id: String): String {
        val uuid = uuid(id)
        if (id !in Realm.home && p.server.profileCache?.get(uuid)?.isPresent != true && p.server.playerList.getPlayer(uuid) == null && Realm.data.countries.values.none { id in it.outsiders }) throw Fail("kami_claims.error.unknown_player")
        return id
    }

    private fun confirmed(a: List<String>) = a.lastOrNull() == "confirm"

    private fun member(c: Country, id: String) = c.members[id] ?: throw Fail("kami_claims.error.not_member")

    private fun citizenRoom(c: Country) {
        Features.requireRoom(c, Capacity.CITIZENS, c.members.size)
    }

    private fun mine(c: Country, k: Key) = Realm.index[k]?.takeIf { it.country == c.id } ?: throw Fail("kami_claims.error.not_your_chunk")

    fun claimError(c: Country, k: Key, type: String): Phrase? {
        val owned = Realm.claims(c.id)
        return Planner.blockReason(c, k, type, owned.map { it.key }.toHashSet(), c.treasury, owned.size)
    }

    fun addClaim(c: Country, k: Key, type: String, capital: Boolean = false) {
        val free = Realm.claimCount(c.id) < Realm.freeAllowed(c)
        if (!free) Treasury.bank(c, LedgerKind.CLAIM, -s.types.getValue(type).price.toLong(), note = "${k.x}, ${k.z}")
        Realm.add(Claim(c.id, k.dim, k.x, k.z, type, capital = capital, free = free))
        ResearchSync.refresh(c)
    }

    private fun dispatch(p: ServerPlayer, name: String, a: List<String>): Phrase {
        val text = if (name.startsWith("war_")) WarActions.act(p, name, a) else when (name) {
            "create" -> create(p, arg(a, 0), a.getOrNull(1)?.let { parse(PresetId.entries.toTypedArray(), it) } ?: PresetId.MIXED)
            "disband" -> disband(p, confirmed(a))
            "leave" -> leave(p, confirmed(a))
            "invite" -> invite(p, who(p, a, 0))
            "accept" -> accept(p, arg(a, 0))
            "join" -> join(p, arg(a, 0))
            "approve" -> approve(p, who(p, a, 0))
            "deny" -> need(p, Perm.INVITE).let { it.requests.remove(who(p, a, 0)); Phrase.of("kami_claims.done.request_denied") }
            "kick" -> kick(p, who(p, a, 0), false)
            "banish" -> kick(p, who(p, a, 0), true)
            "ally" -> ally(p, known(p, who(p, a, 0)))
            "clear" -> clear(p, who(p, a, 0))
            "president" -> president(p, who(p, a, 0), confirmed(a))
            "claim" -> claim(p, arg(a, 0), num(a, 1).coerceIn(0, 8), a.getOrNull(2)?.let { spot(p, a, 2) })
            "unclaim" -> unclaim(p, spot(p, a, 0))
            "type" -> retype(p, arg(a, 0), spot(p, a, 1))
            "capital" -> capital(p, spot(p, a, 0))
            "rename" -> Naming.rename(needPresident(p), arg(a, 0), p.stringUUID)
            "deposit" -> deposit(p, num(a, 0))
            "withdraw" -> withdraw(p, num(a, 0))
            "tax" -> need(p, Perm.TAXES).let { Housing.edit(it, null, Claimant.CITIZEN, true, num(a, 0)); Phrase.of("kami_claims.done.tax", Words.rate(Housing.rent(it), 1)) }
            "rule" -> rule(p, arg(a, 0), arg(a, 1), arg(a, 2))
            "rules" -> arg(a, 0).split(';').filter { it.isNotBlank() }.let { changes ->
                changes.forEach { change -> change.split(':').takeIf { it.size == 3 }?.let { (t, f, v) -> rule(p, t, f, v) } ?: throw Fail("kami_claims.error.rule_format") }
                Phrase.of("kami_claims.done.rules", count("kami_claims.unit.change", changes.size))
            }
            "plot_law" -> plotLaw(need(p, Perm.TAXES), num(a, 0), num(a, 1))
            "plot_offer" -> plotOffer(p, a)
            "plot_limit" -> plotLimit(p, need(p, Perm.PLOTS), arg(a, 0), num(a, 1))
            "flag" -> need(p, Perm.DETAILS).let {
                fun hex(i: Int) = arg(a, i).removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: throw Fail("kami_claims.error.hex", v("ffffff"))
                val color = hex(0)
                val flag = Flag(num(a, 1).coerceIn(0, 31), num(a, 2).coerceIn(0, 63), hex(3))
                val changed = it.color != color || it.flag != flag
                it.color = color
                it.flag = flag
                if (changed) Announce.fire("flagChanged", it, mapOf("country" to it.name))
                Phrase.of("kami_claims.done.flag", v(it.name))
            }
            "claimcells" -> claimRect(p, arg(a, 0), cells(p, a, 1))
            "typecells" -> typeRect(p, arg(a, 0), cells(p, a, 1))
            "unclaimcells" -> unclaimRect(p, cells(p, a, 0))
            "plot_claim" -> plotClaim(p, spot(p, a, 0))
            "plot_remove" -> plotRemove(p, spot(p, a, 0), confirmed(a))
            "plot_release" -> plotRelease(p, spot(p, a, 0))
            "rent_pay" -> rentPay(p, Key(arg(a, 0), num(a, 1), num(a, 2)), arg(a, 3))
            "rent_pay_all" -> rentPayAll(p)
            "plot_trust" -> plotOwner(p, spot(p, a, 2)).let { cl ->
                known(p, who(p, a, 0)).let { id -> if (id == cl.owner) throw Fail("kami_claims.error.plot_owner_self"); if (id !in cl.roles && cl.roles.size >= MAX_ROLES) throw Fail("kami_claims.error.list_full", MAX_ROLES); cl.roles[id] = parse(Role.values(), arg(a, 1)) }
                Phrase.of("kami_claims.done.role_set")
            }
            "plot_untrust" -> plotOwner(p, spot(p, a, 1)).let { it.roles.remove(who(p, a, 0)); Phrase.of("kami_claims.done.role_removed") }
            "province_accept" -> provinceAccept(p, arg(a, 0), confirmed(a))
            "province_invite", "province_request", "province_approve", "province_deny", "province_release", "province_forgive",
            "province_independence", "province_withdraw", "province_decline", "province_tax", "province_manage", "province_access" -> province(p, name, a)
            "province_give" -> provinceGive(p, arg(a, 0), arg(a, 1), confirmed(a))
            "loan_take" -> Loans.take(need(p, Perm.WITHDRAW), arg(a, 0), p.stringUUID)
            "loan_offer_create" -> Loans.createOffer(need(p, Perm.WITHDRAW), long(a, 0), num(a, 1), num(a, 2), arg(a, 3) == "on", num(a, 4), num(a, 5), arg(a, 6) == "on", p.stringUUID)
            "loan_offer_toggle" -> Loans.toggleOffer(need(p, Perm.WITHDRAW), arg(a, 0), arg(a, 1) == "on", p.stringUUID)
            "loan_offer_delete" -> Loans.deleteOffer(need(p, Perm.WITHDRAW), arg(a, 0))
            "loan_borrow" -> Loans.borrow(need(p, Perm.WITHDRAW), arg(a, 0), long(a, 1), p.stringUUID)
            "loan_repay" -> Loans.repay(need(p, Perm.WITHDRAW), arg(a, 0), p.stringUUID)
            "buff_toggle" -> Buffs.toggle(need(p, Perm.RESEARCH), arg(a, 0))
            "research_enqueue", "research_start", "research_pause", "research_remove", "research_move", "research_deposit" -> researchAct(p, name, a)
            "alliance" -> alliance(p, arg(a, 0), country(arg(a, 1)))
            "tariff" -> Diplomacy.setTariff(need(p, Perm.TRADE), country(arg(a, 0)), num(a, 1))
            "embargo" -> Diplomacy.setEmbargo(need(p, Perm.TRADE), country(arg(a, 0)), arg(a, 1) == "on")
            "min_reputation" -> Reputation.setMin(need(p, Perm.TRADE), num(a, 0))
            "fame_rate" -> Fame.rate(p, country(arg(a, 0)), num(a, 1))
            "gift_send" -> Gifts.send(p, uuid(arg(a, 0)), num(a, 1))
            "gift_accept" -> Gifts.accept(p, uuid(arg(a, 0)))
            "gift_decline" -> Gifts.decline(p, uuid(arg(a, 0)))
            "gift_cancel" -> Gifts.cancel(p, uuid(arg(a, 0)))
            else -> GovActions.handle(p, name, a) ?: throw Fail("kami_claims.error.unknown_action")
        }
        Realm.changed(name !in NON_TERRITORY_ACTIONS)
        if (name in AUDITED_ACTIONS) auditAction(p, name, a)
        return text
    }

    private const val AUDIT_TEXT = 60
    private const val AUDIT_DEPOSIT_FLOOR = 10_000

    private val AUDITED_ACTIONS = setOf(
        "invite", "approve", "kick", "banish", "ally", "clear", "president", "claim", "unclaim", "type", "capital", "rename", "withdraw",
        "tax", "rule", "rules", "plot_law", "plot_limit", "claimcells", "typecells", "unclaimcells",
        "province_accept", "province_invite", "province_approve", "province_release", "province_forgive", "province_tax", "province_give",
        "war_declare", "war_offer", "war_accept", "war_agree", "war_withdraw", "war_capitulate", "war_join"
    )

    private fun auditAction(p: ServerPlayer, name: String, a: List<String>) {
        val c = viewed(p) ?: Realm.of(p.stringUUID) ?: return
        val args = a.filter { it != "confirm" }
        Audit.record(c, p.stringUUID, name, args.firstOrNull().orEmpty().take(AUDIT_TEXT), args.drop(1).joinToString(" ").take(AUDIT_TEXT))
    }

    private val NON_TERRITORY_ACTIONS = setOf(
        "buff_toggle", "research_enqueue", "research_start", "research_pause", "research_remove", "research_move", "research_deposit",
        "gift_send", "gift_accept", "gift_decline", "gift_cancel",
        "rank_create", "rank_edit", "rank_plots", "rank_move", "group_perms",
        "job_define", "job_tier", "job_toggle", "job_add", "job_take", "contract_offer", "contract_accept",
        "withdraw_request", "withdraw_approve", "heir_set", "vote_president", "deposit", "withdraw"
    )

    private fun uuid(raw: String): UUID = raw.toUuidOrNull() ?: throw Fail("kami_claims.error.unknown_player")

    private fun create(p: ServerPlayer, n: String, preset: PresetId): Phrase {
        if (!Perms.has(p, Perms.FOUND)) throw Fail("kami_claims.error.permission")
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        Naming.check(n)
        val independent = Realm.data.countries.values.count { it.active && it.parent == null }
        if (s.maxCountries > 0 && independent >= s.maxCountries) throw Fail("kami_claims.error.country_limit", Words.num(s.maxCountries))
        val c = ResearchReset.founded(Country(n))
        Presets.found(c, preset)
        claimError(c, here(p), s.defaultType)?.let { throw Fail(it) }
        Realm.data.countries[c.id] = c
        Realm.join(c, p.stringUUID, PRESIDENT)
        Stats.event("country.found", "c:${c.id}", "p:${p.stringUUID}")
        addClaim(c, here(p), s.defaultType, true)
        Effects.founded(p, c)
        return Phrase.of("kami_libs.format.join", Phrase.of("kami_claims.done.founded", v(n), chunks(Realm.freeAllowed(c))), Phrase.of("kami_claims.notice.release_lock"))
    }

    private const val MAX_ROLES = 64
    private const val MAX_OUTSIDERS = 128

    private fun disbandConfirm(c: Country) = NeedsConfirm(listOf(
        Phrase.of("kami_claims.common.disband_x", v(c.name)),
        Phrase.of("kami_claims.confirm.disband.land", chunks(Realm.claimCount(c.id))),
        Phrase.of("kami_claims.confirm.disband.members", count("kami_claims.unit.member", c.members.size)),
        Phrase.of("kami_claims.confirm.disband.treasury", money(c.treasury))
    ) + Realm.claimsView(c.id).count { it.owner != null }.takeIf { it > 0 }?.let { listOf(Phrase.of("kami_claims.confirm.disband.tenants", count("kami_claims.unit.plot", it))) }.orEmpty())

    private fun disband(p: ServerPlayer, confirmed: Boolean): Phrase {
        val c = needPresident(p)
        WarLocks.disband(c)
        Loans.requireNoLoans(c, "kami_claims.loans.error.disband")
        if (!confirmed) throw disbandConfirm(c)
        Realm.disband(c)
        Effects.chime(p, false)
        return Phrase.of("kami_claims.done.disbanded")
    }

    private fun leave(p: ServerPlayer, confirmed: Boolean): Phrase {
        val c = home(p)
        if (Permissions.president(c) == p.stringUUID && c.members.size > 1) throw Fail("kami_claims.error.transfer_first")
        WarLocks.roster(c, p.stringUUID)
        if (c.members.size == 1) WarLocks.disband(c)
        if (c.members.size == 1) {
            Loans.requireNoLoans(c, "kami_claims.loans.error.disband")
            if (!confirmed) throw disbandConfirm(c)
        }
        Realm.leave(c, p.stringUUID, Leave.LEAVE)
        ResearchSync.refresh(c)
        if (c.members.isEmpty()) Realm.disband(c)
        else Mail.broadcast(c, Phrase.of("kami_claims.mail.member_left", v(p.name.string)), Tone.WARN)
        return Phrase.of("kami_claims.done.left", v(c.name))
    }

    private fun invite(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Perm.INVITE)
        if (Realm.of(id) != null) throw Fail("kami_claims.error.target_in_country")
        if (c.outsiders[id] == Standing.BANISHED) throw Fail("kami_claims.error.target_banished")
        if ((c.invites[id] ?: 0) > now()) return Phrase.of("kami_claims.done.invited")
        citizenRoom(c)
        c.invites[id] = now() + s.inviteDays * s.dayMillis
        Effects.invite(p.server, id, c)
        return Phrase.of("kami_claims.done.invited")
    }

    private fun accept(p: ServerPlayer, country: String): Phrase {
        val c = Realm.live(country) ?: throw Fail("kami_claims.error.unknown_country")
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        if (c.outsiders[p.stringUUID] == Standing.BANISHED) throw Fail("kami_claims.error.you_banished", v(c.name))
        if ((c.invites[p.stringUUID] ?: 0) < now()) throw Fail("kami_claims.error.no_invite")
        admit(c, p.stringUUID, p.name.string)
        return Phrase.of("kami_claims.done.welcome", v(c.name))
    }

    private fun admit(c: Country, id: String, name: String) {
        citizenRoom(c)
        Realm.join(c, id, CITIZEN)
        Progress.citizenJoined(c, id)
        Mail.broadcast(c, Phrase.of("kami_claims.mail.joined", v(name)), Tone.OK)
    }

    private fun join(p: ServerPlayer, country: String): Phrase {
        val c = Realm.live(country) ?: throw Fail("kami_claims.error.unknown_country")
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        if (c.outsiders[p.stringUUID] == Standing.BANISHED) throw Fail("kami_claims.error.you_banished", v(c.name))
        val membership = c.government.levers.membership
        if (membership == Membership.INVITE) throw Fail("kami_claims.error.invite_only")
        if (membership == Membership.OPEN) {
            admit(c, p.stringUUID, p.name.string)
            return Phrase.of("kami_claims.done.welcome", v(c.name))
        }
        if ((c.requests[p.stringUUID] ?: 0) > now()) return Phrase.of("kami_claims.done.request_sent")
        c.requests[p.stringUUID] = now() + s.inviteDays * s.dayMillis
        Mail.officers(c, Phrase.of("kami_claims.mail.join_request", v(p.name.string)))
        return Phrase.of("kami_claims.done.request_sent")
    }

    private fun approve(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Perm.INVITE)
        if (c.requests[id] == null) throw Fail("kami_claims.error.no_request")
        if (Realm.of(id) != null) throw Fail("kami_claims.error.target_in_country")
        if (c.outsiders[id] == Standing.BANISHED) throw Fail("kami_claims.error.target_banished")
        admit(c, id, Names.of(p.server, id))
        c.requests.remove(id)
        return Phrase.of("kami_claims.done.request_approved")
    }

    private fun kick(p: ServerPlayer, id: String, banish: Boolean): Phrase {
        val c = need(p, if (banish) Perm.BANISH else Perm.KICK)
        if (banish) Features.require(c, Features.BANISH)
        val member = c.members[id]
        if (banish && member == null) known(p, id)
        if (banish && c.outsiders[id] != Standing.BANISHED && c.outsiders.values.count { it == Standing.BANISHED } >= MAX_OUTSIDERS) throw Fail("kami_claims.error.banish_full", MAX_OUTSIDERS)
        member?.let {
            if (!Permissions.outranks(c, p.stringUUID, id)) throw Fail("kami_claims.error.rank_below")
            WarLocks.roster(c, id)
            Realm.leave(c, id, if (banish) Leave.BAN else Leave.KICK)
            ResearchSync.refresh(c)
            Mail.direct(id, Phrase.of(if (banish) "kami_claims.mail.banished" else "kami_claims.mail.removed", v(c.name)), Tone.BAD)
            Mail.broadcast(c, Phrase.of(if (banish) "kami_claims.mail.member_banished" else "kami_claims.mail.member_kicked", v(Names.of(p.server, id)), v(p.name.string)), Tone.BAD)
        }
        if (banish) {
            c.outsiders.remove(id)
            c.outsiders[id] = Standing.BANISHED
            if (member == null) Housing.departed(c, id, Leave.BAN)
        }
        return Phrase.of(if (banish) "kami_claims.done.banished" else "kami_claims.done.removed")
    }

    private fun ally(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Perm.ALLIES)
        if (c.members.containsKey(id)) throw Fail("kami_claims.error.member_ally")
        if (c.outsiders[id] == Standing.BANISHED) need(p, Perm.PARDON)
        if (id !in c.outsiders && c.outsiders.values.count { it != Standing.BANISHED } >= MAX_OUTSIDERS) throw Fail("kami_claims.error.list_full", MAX_OUTSIDERS)
        c.outsiders[id] = Standing.ALLIED
        return Phrase.of("kami_claims.done.allied")
    }

    private fun clear(p: ServerPlayer, id: String): Phrase {
        val c = need(p, if (home(p).outsiders[id] == Standing.BANISHED) Perm.PARDON else Perm.ALLIES)
        c.outsiders.remove(id)
        return Phrase.of("kami_claims.done.relation_cleared")
    }

    private fun president(p: ServerPlayer, id: String, confirmed: Boolean): Phrase {
        val c = needPresident(p)
        val m = member(c, id)
        val mine = c.members.getValue(p.stringUUID)
        if (m === mine) throw Fail("kami_claims.error.already_president")
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.confirm.president.title", v(Names.of(p.server, id)), v(c.name)),
            Phrase.of("kami_claims.confirm.president.rights"),
            Phrase.of("kami_claims.confirm.president.you", Words.rank(c, CITIZEN))
        ))
        Housing.repriced(c) {
            mine.changeRank(CITIZEN)
            m.changeRank(PRESIDENT)
        }
        c.leaderChanged()
        Mail.broadcast(c, Phrase.of("kami_claims.mail.president"))
        Announce.fire("leaderHandover", c, mapOf("country" to c.name, "old" to p.name.string, "leader" to Names.of(p.server, id)))
        return Phrase.of("kami_claims.done.president")
    }

    private fun claimCells(c: Country, type: String, cells: List<Key>): Int {
        WarLocks.claims(c)
        val plan = Planner.claim(c, type, cells)
        plan.ready.forEach { addClaim(c, it.key, type) }
        return plan.ready.size
    }

    private fun claimReport(c: Country, type: String, count: Int, before: Long, first: Boolean): Phrase {
        if (count == 0) return Phrase.of("kami_claims.error.nothing_claimed")
        val report = Phrase.of("kami_claims.done.claimed", chunks(count), Words.type(type), money(before - c.treasury), money(c.treasury))
        return if (first) Phrase.of("kami_libs.format.join", report, Phrase.of("kami_claims.notice.release_lock")) else report
    }

    private fun ensureUnclaimUnlocked(cl: Claim) {
        val reason = Planner.unclaimLock(cl) ?: return
        if (!cl.unclaimWarned) {
            cl.unclaimWarned = true
            Realm.changed()
        }
        throw Fail(reason)
    }

    private fun claim(p: ServerPlayer, type: String, radius: Int, at: Key?): Phrase {
        val c = needSpender(p, Perm.CLAIM)
        s.types[type] ?: throw Fail("kami_claims.error.unknown_type")
        val origin = at ?: here(p)
        val cells = (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> Key(origin.dim, origin.x + dx, origin.z + dz) } }
            .sortedBy { max(abs(it.x - origin.x), abs(it.z - origin.z)) }
        if (radius == 0) claimError(c, origin, type)?.let { throw Fail(it) }
        val before = c.treasury
        val beforeClaims = Realm.claimCount(c.id)
        val count = claimCells(c, type, cells)
        if (count > 0) Effects.chime(p, true)
        return claimReport(c, type, count, before, beforeClaims == 0)
    }

    private class Rect(val cells: List<Key>)

    private fun rect(p: ServerPlayer, a: List<String>, i: Int): Rect {
        val dim = here(p).dim
        val x1 = min(num(a, i), num(a, i + 2))
        val x2 = max(num(a, i), num(a, i + 2))
        val z1 = min(num(a, i + 1), num(a, i + 3))
        val z2 = max(num(a, i + 1), num(a, i + 3))
        if ((x2.toLong() - x1 + 1) * (z2.toLong() - z1 + 1) > s.maxRect) throw Fail("kami_claims.error.too_big", chunks(s.maxRect))
        return Rect((x1..x2).flatMap { x -> (z1..z2).map { z -> Key(dim, x, z) } })
    }

    private fun cells(p: ServerPlayer, a: List<String>, i: Int): Rect {
        if (arg(a, i) != "cells") return rect(p, a, i)
        val dim = here(p).dim
        val keys = arg(a, i + 1).split(',').mapNotNull { pair ->
            val parts = pair.split(':')
            if (parts.size != 2) return@mapNotNull null
            Key(dim, parts[0].toIntOrNull() ?: return@mapNotNull null, parts[1].toIntOrNull() ?: return@mapNotNull null)
        }.distinct()
        if (keys.size > s.maxRect) throw Fail("kami_claims.error.too_big", chunks(s.maxRect))
        return Rect(keys)
    }

    private fun claimRect(p: ServerPlayer, type: String, r: Rect): Phrase {
        val c = needSpender(p, Perm.CLAIM)
        s.types[type] ?: throw Fail("kami_claims.error.unknown_type")
        val before = c.treasury
        val beforeClaims = Realm.claimCount(c.id)
        val count = claimCells(c, type, r.cells)
        if (count > 0) Effects.chime(p, true)
        return claimReport(c, type, count, before, beforeClaims == 0)
    }

    private fun unclaimRect(p: ServerPlayer, r: Rect): Phrase {
        val c = need(p, Perm.UNCLAIM)
        WarLocks.claims(c)
        val plan = Planner.unclaim(c, r.cells)
        plan.ready.forEach { cell -> Realm.index[cell.key]?.let { Realm.unclaim(it, false) } }
        ResearchSync.refresh(c)
        val blocked = plan.blocked
        val reason = blocked.firstOrNull()?.reason ?: Phrase.literal("")
        if (plan.ready.isEmpty() && blocked.isNotEmpty()) throw Fail(Phrase.of("kami_claims.error.nothing_released", reason), "BLOCKED", blocked.first().key)
        if (blocked.isEmpty()) return Phrase.of("kami_claims.done.released", chunks(plan.ready.size))
        return Phrase.of("kami_claims.done.released_kept", chunks(plan.ready.size), chunks(blocked.size), reason)
    }

    private fun typeRect(p: ServerPlayer, type: String, r: Rect): Phrase {
        val c = need(p, Perm.CHUNK_TYPE)
        WarLocks.retype(c)
        if (s.types[type] == null) throw Fail("kami_claims.error.unknown_type")
        val set = r.cells.toHashSet()
        val hit = Realm.claims(c.id).filter { it.key in set }
        hit.filter { it.type != type }.forEach(::requireFree)
        if (hit.any { it.type != type }) Features.require(c, Features.claimType(type))
        hit.forEach { applyType(it, type) }
        return Phrase.of("kami_claims.done.retyped", chunks(hit.size), Words.type(type))
    }

    private fun requireFree(cl: Claim) {
        if (cl.owner != null) throw Fail("kami_claims.error.plot_tenanted")
    }

    private fun applyType(cl: Claim, type: String) {
        if (cl.type != type) Work.retyped(Realm.data.countries.getValue(cl.country), cl)
        cl.type = type
    }

    private fun unclaim(p: ServerPlayer, k: Key): Phrase {
        val c = need(p, Perm.UNCLAIM)
        WarLocks.claims(c)
        val cl = mine(c, k)
        if (cl.capital) throw Fail("kami_claims.error.capital")
        requireFree(cl)
        ensureUnclaimUnlocked(cl)
        if (!Realm.removable(cl)) throw Fail("kami_claims.error.split")
        Realm.unclaim(cl, false)
        ResearchSync.refresh(c)
        return Phrase.of("kami_claims.done.chunk_released")
    }

    private fun retype(p: ServerPlayer, type: String, k: Key): Phrase {
        val c = need(p, Perm.CHUNK_TYPE)
        WarLocks.retype(c)
        val cl = mine(c, k)
        if (s.types[type] == null) throw Fail("kami_claims.error.unknown_type")
        if (cl.type != type) {
            requireFree(cl)
            Features.require(c, Features.claimType(type))
        }
        applyType(cl, type)
        return Phrase.of("kami_claims.done.chunk_retyped", Words.type(type), Words.rate(Realm.price(cl), Realm.period(cl)))
    }

    private fun capital(p: ServerPlayer, k: Key): Phrase {
        val c = needSpender(p, Perm.CAPITAL)
        WarLocks.capital(c)
        val cl = mine(c, k)
        if (cl.capital) throw Fail("kami_claims.error.already_capital")
        if (now() - c.moved < s.capitalCooldownDays * s.dayMillis) throw Fail("kami_claims.error.capital_cooldown")
        Tokens.spend(c, Tokens.CAPITAL_MOVE, s.capitalMoveCost, p.stringUUID)
        Realm.claims(c.id).forEach { it.capital = false }
        cl.capital = true
        c.moved = now()
        return Phrase.of("kami_claims.done.capital")
    }

    private fun deposit(p: ServerPlayer, n: Int): Phrase {
        val c = home(p)
        if (!Permissions.holds(c, p.stringUUID, Perm.DEPOSIT) || !Perms.permitted(p, Perm.DEPOSIT)) throw Fail("kami_claims.error.permission")
        if (n < 1) throw Fail(Phrase.of("kami_claims.error.amount"), "AMOUNT")
        if (n > Treasury.room(c)) throw Fail(Phrase.of("kami_claims.error.treasury_full", money(Treasury.room(c))), "AMOUNT")
        if (!Bank.take(p.uuid, n, Flow.TREASURY, CountryParty(c.id).key, "kami_libs.note.deposit")) throw Fail(Phrase.of("kami_claims.error.funds", money(n)), "FUNDS")
        Treasury.move(c, LedgerKind.DEPOSIT, n.toLong(), p.stringUUID, Statement.note("kami_libs.note.deposit"))
        c.pending += n
        if (n > AUDIT_DEPOSIT_FLOOR) Audit.record(c, p.stringUUID, "deposit", n.toString())
        return Phrase.of("kami_claims.done.deposited", money(n), money(c.treasury))
    }

    private fun withdraw(p: ServerPlayer, n: Int): Phrase {
        val c = need(p, Perm.WITHDRAW)
        if (c.government.levers.treasury == TreasuryControl.BUDGET) throw Fail("kami_claims.error.budget_bound")
        Loans.requireNoDebt(c, "kami_claims.loans.error.disband")
        if (Wars.involved(c) || c.warDebts.isNotEmpty()) throw Fail("kami_claims.error.withdraw_war")
        if (n < 1) throw Fail(Phrase.of("kami_claims.error.amount"), "AMOUNT")
        if (c.treasury < n) throw Fail(Phrase.of("kami_claims.error.treasury_short", money(c.treasury)), "FUNDS")
        Council.gate(c, p.stringUUID, n.toLong())?.let { return it }
        if (!Bank.give(p.uuid, n, Flow.TREASURY, CountryParty(c.id).key, "kami_libs.note.withdraw")) throw Fail("kami_claims.error.payout")
        Treasury.move(c, LedgerKind.WITHDRAW, -n.toLong(), p.stringUUID, Statement.note("kami_libs.note.withdraw"))
        return Phrase.of("kami_claims.done.withdrew", money(n))
    }

    private fun rule(p: ServerPlayer, type: String, field: String, value: String): Phrase {
        val c = need(p, Perm.CHUNK_RULES)
        if (s.types[type] == null) throw Fail("kami_claims.error.unknown_type")
        val flag = { value.toBooleanStrictOrNull() ?: throw Fail("kami_claims.error.bool") }
        if (Action.entries.any { it.name.equals(field, true) && ruleLocked(type, it) }) throw Fail("kami_claims.error.rule_locked")
        when (field) {
            "fire" -> c.fire[type] = flag()
            else -> c.rules.getOrPut(type) { mutableMapOf() }[parse(Action.values(), field)] = parse(Access.values(), value)
        }
        return Phrase.of("kami_claims.done.rule")
    }

    private fun plotClaim(p: ServerPlayer, k: Key): Phrase {
        permit(p, Perm.PLOTS)
        val cl = Realm.index[k] ?: throw Fail("kami_claims.error.plot_none")
        val c = Realm.live(cl.country) ?: throw Fail("kami_claims.error.unknown_country")
        Housing.claim(c, cl, p.stringUUID)
        if (p.stringUUID !in c.members) Mail.officers(c, Phrase.of("kami_claims.mail.plot_rented", v(p.name.string), v("${cl.x}, ${cl.z}")))
        return Phrase.of("kami_claims.done.plot_claimed", Words.rate(Housing.rate(c, cl), 1))
    }

    private fun plotOwner(p: ServerPlayer, k: Key): Claim {
        val cl = Realm.index[k] ?: throw Fail("kami_claims.error.plot_none")
        if (cl.owner == null) throw Fail("kami_claims.error.plot_free")
        if (cl.owner != p.stringUUID && cl.roles[p.stringUUID] != Role.OWNER) throw Fail("kami_claims.error.plot_owner_only")
        return cl
    }

    private fun plotRelease(p: ServerPlayer, k: Key): Phrase {
        val cl = plotOwner(p, k)
        val c = Realm.data.countries.getValue(cl.country)
        val tenant = cl.owner!!
        Housing.release(c, cl)
        if (tenant !in c.members) Mail.officers(c, Phrase.of("kami_claims.mail.plot_returned", v(Names.of(p.server, tenant)), v("${cl.x}, ${cl.z}")))
        return Phrase.of("kami_claims.done.plot_released")
    }

    private fun rentPay(p: ServerPlayer, k: Key, amount: String): Phrase {
        val cl = Realm.index[k]?.takeIf { it.plot && it.owner == p.stringUUID } ?: throw Fail("kami_claims.error.plot_owner_only")
        val c = Realm.data.countries.getValue(cl.country)
        val n = if (amount == "all") cl.rentDebt else amount.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE } ?: throw Fail(Phrase.of("kami_claims.error.amount"), "AMOUNT")
        if (n <= 0 || !c.active) throw Fail("kami_claims.error.rent_nothing")
        val paid = Housing.payRent(c, cl, p.stringUUID, n)
        if (paid <= 0) throw if (Housing.treasuryBlocked(c, cl)) Fail("kami_claims.error.rent_treasury_full") else if (Bank.funds(p.uuid) < n) Fail(Phrase.of("kami_claims.error.funds", money(n)), "FUNDS") else Fail("kami_claims.error.rent_nothing")
        return Phrase.of("kami_claims.done.rent_paid", money(paid), v("${cl.x}, ${cl.z}"))
    }

    private fun rentPayAll(p: ServerPlayer): Phrase {
        val me = p.stringUUID
        var total = 0L
        var count = 0
        var blocked = false
        Realm.data.claims.filter { it.plot && it.owner == me && it.rentDebt > 0 }.forEach { cl ->
            val c = Realm.data.countries[cl.country] ?: return@forEach
            if (Housing.treasuryBlocked(c, cl)) blocked = true
            val paid = Housing.payRent(c, cl, me, min(cl.rentDebt, Bank.funds(p.uuid)))
            if (paid > 0) {
                total += paid
                count++
            }
        }
        if (count == 0) throw Fail(if (blocked) "kami_claims.error.rent_treasury_full" else "kami_claims.error.rent_nothing")
        return Phrase.of("kami_claims.done.rent_paid_all", money(total), num(count))
    }

    private fun plotRemove(p: ServerPlayer, k: Key, confirmed: Boolean): Phrase {
        val c = need(p, Perm.HOUSING)
        val cl = mine(c, k)
        val actor = actorId(c, p)
        val tenant = Housing.checkRemove(c, cl, actor)
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.plots.untrust.title", v(Names.of(p.server, tenant))),
            Phrase.of("kami_claims.confirm.plot_remove.body", days(c.moveOutDays))
        ))
        Housing.moveOut(c, cl, "removed")
        return Phrase.of("kami_claims.done.plot_removed")
    }

    private fun plotLaw(c: Country, debt: Int, out: Int): Phrase {
        c.rentDebtLimit = max(0, debt).toLong()
        c.moveOutDays = out.coerceIn(1, 30)
        return Phrase.of("kami_claims.done.plot_law", money(c.rentDebtLimit), days(c.moveOutDays))
    }

    private fun plotOffer(p: ServerPlayer, a: List<String>): Phrase {
        val c = need(p, Perm.HOUSING)
        if (arg(a, 0) == "reset") {
            Housing.reset(c, mine(c, spot(p, a, 1)))
            return Phrase.of("kami_claims.done.plot_offer")
        }
        val cat = parse(Claimant.values(), arg(a, 0))
        val on = when (arg(a, 1)) {
            "on" -> true
            "off" -> false
            else -> throw Fail("kami_claims.error.bool")
        }
        val target = if (a.getOrNull(3) == "default") null else mine(c, spot(p, a, 3))
        Housing.edit(c, target, cat, on, num(a, 2).takeIf { it >= 0 })
        return Phrase.of("kami_claims.done.plot_offer")
    }

    private fun plotLimit(p: ServerPlayer, c: Country, target: String, n: Int): Phrase {
        val value = n.takeIf { it >= 0 }?.coerceAtMost(Levels.capacity(c, Capacity.PLOTS))
        val rank = Permissions.rank(c, target.lowercase())
        val group = find(Claimant.values(), target)?.takeIf { it != Claimant.CITIZEN }
        when {
            rank != null -> rank.plots = value ?: 0
            group != null -> c.guestPlots[group] = value ?: s.guestPlots[group] ?: 0
            else -> who(p, listOf(target), 0).let { id -> if (value == null) c.playerPlots.remove(id) else c.playerPlots[id] = value }
        }
        return Phrase.of("kami_claims.done.plot_limit")
    }

    private fun country(name: String) = Realm.live(name) ?: throw Fail("kami_claims.error.unknown_country")

    private fun researchAct(p: ServerPlayer, name: String, a: List<String>): Phrase {
        if (name == "research_deposit") {
            val key = Queue.key(home(p), arg(a, 0))
            val moved = Deposit.take(p, key, num(a, 1))
            return Phrase.of("kami_claims.research.done.deposit", num(moved), Queue.node(home(p), key).label().asValue())
        }
        val c = need(p, Perm.RESEARCH)
        val key = Queue.key(c, arg(a, 0))
        when (name) {
            "research_enqueue" -> Queue.enqueue(c, key, p.stringUUID)
            "research_start" -> Queue.start(c, key, p.stringUUID)
            "research_pause" -> Queue.pause(c, key)
            "research_remove" -> Queue.remove(c, key)
            else -> Queue.move(c, key, num(a, 1))
        }
        return Phrase.of("kami_claims.research.done.${name.removePrefix("research_")}", Queue.node(c, key).label().asValue())
    }

    private fun alliance(p: ServerPlayer, step: String, other: Country): Phrase {
        val c = need(p, Perm.DIPLOMACY)
        return when (step) {
            "propose" -> Diplomacy.propose(c, other)
            "accept" -> Diplomacy.accept(c, other)
            "decline" -> Diplomacy.decline(c, other)
            "end" -> Diplomacy.end(c, other)
            else -> throw Fail("kami_claims.error.choice", v("propose, accept, decline, end"))
        }
    }

    private fun provinceAccept(p: ServerPlayer, name: String, confirmed: Boolean): Phrase {
        val child = need(p, Perm.PROVINCES)
        val parent = country(name)
        val offer = Provinces.offer(child, parent)
        if (!confirmed) throw NeedsConfirm(Provinces.agreementLines(child, parent, offer))
        Provinces.accept(child, parent)
        return Phrase.of("kami_claims.done.province_joined", v(child.name), v(parent.name))
    }

    private fun provinceGive(p: ServerPlayer, name: String, newParentName: String, confirmed: Boolean): Phrase {
        val parent = need(p, Perm.PROVINCES)
        val child = country(name)
        val newParent = country(newParentName)
        Provinces.checkGive(parent, child, newParent)
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.confirm.give.title", v(child.name), v(newParent.name)),
            Phrase.of("kami_claims.confirm.give.body", v(newParent.name), Words.tribute(child.taxMode, child.taxAmount), v(child.name))
        ))
        Provinces.give(parent, child, newParent)
        return Phrase.of("kami_claims.done.province_given", v(child.name), v(newParent.name))
    }

    private fun province(p: ServerPlayer, name: String, a: List<String>): Phrase {
        val c = need(p, Perm.PROVINCES)
        val t by lazy { country(arg(a, 0)) }
        val m by lazy { parse(TaxMode.values(), arg(a, 1)) }
        val amount by lazy { Provinces.tribute(arg(a, 2), m, c) }
        fun done(key: String) = Phrase.of("kami_claims.done.$key", v(t.name))
        return when (name) {
            "province_invite" -> Provinces.invite(c, t, m, amount).let { done("province_invited") }
            "province_request" -> Provinces.request(c, t).let { done("province_requested") }
            "province_approve" -> Provinces.approve(c, t, m, amount).let { done("province_approved") }
            "province_deny" -> Provinces.deny(c, t).let { done("province_denied") }
            "province_release" -> Provinces.release(c, t).let { done("province_released") }
            "province_forgive" -> Provinces.forgive(c, t).let { done("province_forgiven") }
            "province_decline" -> Provinces.decline(c, t).let { done("independence_declined") }
            "province_tax" -> Provinces.setTribute(c, t, m, amount).let { done("tribute_changed") }
            "province_manage" -> (arg(a, 0) == "on").let { on -> a.getOrNull(1)?.let { Provinces.setDirectManage(c, country(it), on) } ?: Provinces.setManage(c, on); Phrase.of("kami_claims.done.overlord_manage.${if (on) "on" else "off"}") }
            "province_access" -> Provinces.setAccess(c, parse(Perm.entries.toTypedArray(), arg(a, 0)), arg(a, 1) == "on").let { Phrase.of("kami_claims.done.overlord_access") }
            "province_independence" -> Provinces.askIndependence(c).let { Phrase.of("kami_claims.done.independence_requested") }
            "province_withdraw" -> Provinces.withdrawIndependence(c).let { Phrase.of("kami_claims.done.independence_withdrawn") }
            else -> throw Fail("kami_claims.error.unknown_province_action")
        }
    }
}
