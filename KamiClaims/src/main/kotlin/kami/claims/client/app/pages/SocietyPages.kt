package kami.claims.client.app.pages

import kami.libs.ui.core.Memo
import kami.libs.text.SearchQuery
import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.gov.CITIZEN
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.client.app.rankLine
import kami.claims.client.app.tint
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClientLocks
import kami.claims.research.Capacity
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.libs.ui.widget.MasterDetail
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Info
import kami.claims.net.Mem
import java.util.concurrent.TimeUnit
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.restricted
import kami.libs.ui.core.Stack
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*
import kotlin.math.max

class CitizensPage(app: ClaimsApp) : ClaimsPage(app) {
    private val memberColumns = Memo()
    private val citizenTabs = Memo()
    override val title get() = tr("kami_claims.nav.citizens")
    override val help get() = listOf(
        Callout("citizens:tabs", tr("kami_claims.citizens.help.tabs"), tr("kami_claims.citizens.help.tabs.desc")),
        Callout("citizens:table", tr("kami_libs.common.members"), tr("kami_claims.citizens.help.table.desc")),
        Callout("citizens:profile", tr("kami_libs.common.profile"), tr("kami_claims.citizens.help.profile.desc"))
    )
    private var tab = 0
    private var details = false
    private val layout = MasterDetail(0.42, 170, 240)
    private val table = TableState<Mem>()
    private val search = TextState()
    private val invite = TextState()
    private val plotLimits = HashMap<String, NumberState>()

    override fun opened(route: Route) {
        if (route.focus == "requests") tab = 1
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val tabs = r.top(CONTROL_H)
        ui.anchor("citizens:tabs", tabs)
        val staff = lock(Perm.INVITE)
        ui.subTabs(tabs, citizenTabs.of(info.members.size, info.requests.size, info.invitesSent.size, staff, Format.locale) { listOf(
            TabItem(tr("kami_libs.common.members"), Icons.PEOPLE, info.members.size, Severity.NEUTRAL),
            TabItem(tr("kami_claims.citizens.tab.requests"), Icons.INVITE, info.requests.size, Severity.INFO, staff),
            TabItem(tr("kami_claims.citizens.tab.sent"), Icons.SCROLL, info.invitesSent.size, Severity.NEUTRAL, staff)
        ) }, tab, "cit-tabs")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 6)
        when (tab) {
            0 -> members(ui, body)
            1 -> requests(ui, body)
            else -> sent(ui, body)
        }
    }

    private fun members(ui: Ui, r: Rect) {
        val info = info ?: return
        layout.draw(ui, r, app.compact, table.selected, { memberList(ui, it, info) }, { profile(ui, it, info.members.firstOrNull { m -> m.id in table.selected } ?: info.members.firstOrNull { m -> m.id == snap.me }) })
    }

    private fun memberList(ui: Ui, left: Rect, info: Info) {
        var rows: List<Mem> = emptyList()
        val tableRect = ui.filterBar(left, "citizens:filters") { barRect ->
            val bar = Row(barRect, 6)
            ui.searchField(bar.take(140), search, tr("kami_claims.citizens.search"), key = "mem-search")
            val inviteLabel = tr("kami_libs.common.invite")
            if (app.compact) {
                if (ui.edgeButton(bar, inviteLabel, Icons.INVITE, ButtonStyle.PRIMARY, can(Perm.INVITE), lock(Perm.INVITE), pending = pending("invite"), key = "invite-open")) Dialogs.invite(app, invite)
            } else {
                val invited = ui.edgeButton(bar, inviteLabel, Icons.INVITE, ButtonStyle.PRIMARY, can(Perm.INVITE) && invite.text.length >= 3, lock(Perm.INVITE) ?: tr("kami_claims.field.player.disabled"), pending = pending("invite"), key = "invite-go")
                ui.playerField(bar.takeFromRight(120), invite, offline = { ClaimsStore.snap?.names.orEmpty() }, query = { ClaimsStore.quiet("names", it) }, key = "invite-name")
                if (invited) {
                    act("invite", invite.text, key = "invite")
                    invite.set("")
                }
            }
            rows = ui.filtered("citizens-filtered", listOf(search.text, info.members)) { SearchQuery.parse(search.text).let { q -> info.members.filter { q.matches(it.name) } } }
        }
        ui.anchor("citizens:table", tableRect)
        val peopleHidden = info.restricted(Perm.VIEW_PEOPLE)
        ui.table(tableRect, memberColumns.of(Format.locale, peopleHidden, info.restricted(Perm.VIEW_JOBS)) { listOf(
            Column<Mem>(tr("kami_libs.common.name"), -1, sort = textOrder { it.name }) { _, c, m ->
                avatar(m.id, c.x, c.y + 1, 12, m.online)
                fitText(c.x + 16, c.y + 3, c.w - 16, m.name, if (m.id == ClaimsStore.info?.successionMark()) Palette.brass else Palette.text)
            },
            Column<Mem>(tr("kami_libs.common.rank"), 96, sort = compareBy { ClaimsStore.info?.rankLine(it.rank)?.position ?: Int.MAX_VALUE }) { _, c, m ->
                val line = ClaimsStore.info?.rankLine(m.rank)
                val color = line?.tint ?: Palette.textMuted
                Draw.fill(g, Rect(c.x, c.centerY - 3, 6, 6), color)
                fitText(c.x + 10, c.y + 3, c.w - 10, line?.name ?: "-", color)
            },
            Column<Mem>(tr("kami_libs.common.job"), 100, sort = compareBy { it.jobs.size }) { _, c, m ->
                if (peopleHidden || m.jobs.isEmpty()) Draw.text(g, "-", c.x, c.y + 3, Palette.textMuted)
                else fitText(c.x, c.y + 3, c.w, m.jobs.joinToString(", ") { jobName(it) }, Palette.textSecondary)
            },
            Column<Mem>(tr("kami_claims.citizens.col.seen"), 60, Align.RIGHT, compareBy { -it.seen }) { _, c, m -> if (peopleHidden) Draw.textRight(g, "-", c.right, c.y + 3, Palette.textMuted) else Draw.textRight(g, if (m.online) tr("kami_libs.common.online") else Format.ago(m.seen), c.right, c.y + 3, if (m.online) Palette.success else Palette.textMuted) }
        ) }, rows, table, { it.id })
    }

    private fun profile(ui: Ui, r: Rect, m: Mem?) {
        ui.anchor("citizens:profile", r)
        if (m == null) return
        val info = info ?: return
        val f = ui.sidePanel(r, 4, 8)
        val peopleHidden = info.restricted(Perm.VIEW_PEOPLE)
        val head = f.take(26)
        header(ui, m, head)
        ui.property(f.take(11), tr("kami_claims.nav.plots"), if (peopleHidden) "-" else if (m.plotLimit > 0) tr("kami_libs.format.ratio", m.plotsHeld, m.plotLimit) else Format.number(m.plotsHeld))
        val me = m.id == snap.me
        val president = m.rank == PRESIDENT
        val below = ClaimsStore.outranks(m.rank)
        f.skip(2)
        val targets = info.ranks.filter { it.id != m.rank && ClaimsStore.outranks(it.id) }
        val rankLock = lock(Perm.ASSIGN_RANKS) ?: when {
            me -> tr("kami_claims.citizens.rank.disabled.self")
            !below -> hierarchyLock(m, "kami_claims.citizens.rank.disabled.higher")
            targets.isEmpty() -> tr("kami_claims.citizens.rank.disabled.none")
            else -> null
        }
        ui.select(f.take(CONTROL_H), targets.map { Option(it.id, it.name, null, null, it.tint) }, null, info.rankLine(m.rank)?.name.orEmpty(), rankLock == null, rankLock, key = "rank:${m.id}")
            ?.let { id -> info.rankLine(id)?.let { RankDialogs.assign(app, info, m, it) } }
        f.skip(2)
        succession(ui, f, m, info)
        if (peopleHidden) restrictedHint(ui, f, Perm.VIEW_PEOPLE) else jobs(ui, f, m, info)
        f.skip(2)
        if (ui.disclosure(f.take(12), tr("kami_libs.common.details"), details, key = "cit-details")) details = !details
        if (details) {
            ui.property(f.take(11), tr("kami_claims.citizens.since"), if (m.since > 0) Format.ago(m.since) else "-")
            if (!peopleHidden) {
                if (!info.restricted(Perm.VIEW_JOBS)) quotas(ui, f, m, info)
                plotLimit(ui, f, m, info)
            }
        }
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        val memberRule = if (me) tr("kami_claims.citizens.member.disabled.self") else if (!below) hierarchyLock(m, "kami_claims.citizens.member.disabled.higher") else null
        val kickLock = lock(Perm.KICK) ?: memberRule
        val banishLock = lock(Perm.BANISH) ?: memberRule
        if (me && !info.delegated) {
            val presidentAlone = president && info.members.size > 1
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.leave", info.name), Icons.BACK, ButtonStyle.DANGER, !presidentAlone, tr("kami_claims.error.transfer_first"), key = "leave")) {
                Dialogs.confirm(app, tr("kami_claims.citizens.leave.confirm.title", info.name), null, Icons.BACK, listOfNotNull(
                    if (info.members.size == 1) Consequence(tr("kami_claims.citizens.leave.last"), Severity.DANGER) else null,
                    Consequence(tr("kami_claims.citizens.leave.loses"), Severity.DANGER)
                ), tr("kami_claims.citizens.leave.action"), "leave", if (info.members.size == 1) arrayOf("confirm") else emptyArray(), danger = true, hold = true, typed = if (info.members.size == 1) info.name else null)
            }
            y -= CONTROL_H + 3
            if (president) {
                if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.disband"), Icons.DANGER, ButtonStyle.DANGER, key = "disband")) {
                    Dialogs.confirm(app, tr("kami_claims.common.disband_x", info.name), null, Icons.DANGER, listOf(
                        Consequence(tr("kami_claims.citizens.disband.land", trn("kami_claims.unit.chunk", info.chunks)), Severity.DANGER),
                        Consequence(tr("kami_claims.citizens.disband.members", trn("kami_claims.unit.member", info.members.size)), Severity.DANGER),
                        Consequence(tr("kami_claims.citizens.disband.treasury", Format.money(info.treasury)), Severity.DANGER),
                        Consequence(tr("kami_claims.citizens.disband.provinces"))
                    ), tr("kami_claims.citizens.disband.action"), "disband", arrayOf("confirm"), danger = true, hold = true, typed = info.name)
                }
            }
            return
        }
        val half = (rest.w - 4) / 2
        if (ui.button(Rect(rest.x, y, half, CONTROL_H), tr("kami_libs.common.remove"), Icons.BACK, ButtonStyle.DANGER, kickLock == null, kickLock, key = "kick:${m.id}")) {
            Dialogs.confirm(app, tr("kami_claims.citizens.kick.confirm.title", m.name), info.name, Icons.BACK, listOf(
                Consequence(tr("kami_claims.citizens.kick.loses", m.name), Severity.DANGER)
            ), tr("kami_libs.common.remove"), "kick", arrayOf(m.id), danger = true, hold = true)
        }
        if (ui.lockedButton(Rect(rest.x + half + 4, y, half, CONTROL_H), tr("kami_claims.citizens.banish"), ClientLocks.feature("banish"), Icons.BAN, ButtonStyle.DANGER, banishLock == null, banishLock, key = "ban:${m.id}")) {
            Dialogs.confirm(app, tr("kami_claims.citizens.banish.confirm.title", m.name), info.name, Icons.BAN, listOf(
                Consequence(tr("kami_claims.citizens.banish.locked", m.name), Severity.DANGER),
                Consequence(tr("kami_claims.citizens.banish.lift"), Severity.WARNING)
            ), tr("kami_claims.citizens.banish"), "banish", arrayOf(m.id), danger = true, hold = true)
        }
        y -= CONTROL_H + 3
        if (ClaimsStore.myRank?.id == PRESIDENT && !info.delegated) {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.president"), Icons.CROWN, key = "pres:${m.id}")) {
                Dialogs.confirm(app, tr("kami_claims.citizens.president.confirm.title", m.name), null, Icons.CROWN, listOf(
                    Consequence(tr("kami_claims.citizens.president.rights", m.name), Severity.DANGER),
                    Consequence(tr("kami_claims.citizens.president.you", info.rankLine(CITIZEN)?.name.orEmpty()), Severity.WARNING),
                    Consequence(tr("kami_claims.citizens.president.final"), Severity.DANGER)
                ), tr("kami_claims.citizens.president.action"), "president", arrayOf(m.id, "confirm"), danger = true, hold = true, typed = m.name)
            }
        }
    }

    private fun succession(ui: Ui, f: Flow, m: Mem, info: Info) {
        val gov = info.government?.takeUnless { info.delegated } ?: return
        when (gov.leadership) {
            "elected" -> {
                val voted = gov.myVote == m.id
                if (voted) ui.property(f.take(11), tr("kami_claims.citizens.vote.yours"), m.name, Palette.brass)
                if (ui.button(f.take(CONTROL_H), tr("kami_claims.citizens.vote"), Icons.CROWN, enabled = !voted, disabledReason = tr("kami_claims.citizens.vote.disabled.voted"), pending = pending("vote_president"), key = "vote:${m.id}")) act("vote_president", m.id)
            }
            "hereditary", "appointed" -> {
                val isHeir = gov.heir == m.id
                if (isHeir) ui.property(f.take(11), tr("kami_claims.citizens.heir"), m.name, Palette.brass)
                val lock = when {
                    ClaimsStore.myRank?.id != PRESIDENT || info.delegated -> tr("kami_claims.gov.lock.president")
                    m.id == snap.me -> tr("kami_claims.citizens.heir.disabled.self")
                    gov.leadership == "hereditary" && !isHeir && tooNewForHeir(m) -> tr("kami_claims.citizens.heir.disabled.age", HEIR_MIN_DAYS)
                    else -> null
                }
                if (ui.button(f.take(CONTROL_H), tr(if (isHeir) "kami_claims.citizens.heir.clear" else "kami_claims.citizens.heir.set"), Icons.CROWN, enabled = lock == null, disabledReason = lock, pending = pending("heir_set"), key = "heir:${m.id}")) act("heir_set", if (isHeir) "" else m.id)
            }
            "seized" -> f.take(Draw.paragraph(ui.g, tr("kami_claims.citizens.seized"), f.rest.x, f.rest.y, f.rest.w))
        }
        f.skip(2)
    }

    private fun tooNewForHeir(m: Mem) = m.since > 0 && System.currentTimeMillis() - m.since < TimeUnit.DAYS.toMillis(HEIR_MIN_DAYS.toLong())

    private fun Info.successionMark() = government?.let { if (it.leadership == "elected") it.myVote else it.heir }.orEmpty()

    private fun hierarchyLock(m: Mem, key: String) = if (m.rank.isEmpty()) ClientLocks.perm(Perm.VIEW_PEOPLE) else tr(key)

    private fun jobName(id: String) = info?.jobSpecs?.firstOrNull { it.id == id }?.name ?: if (info?.restricted(Perm.VIEW_JOBS) == true) tr("kami_claims.citizens.job.hidden") else id

    private fun restrictedHint(ui: Ui, f: Flow, perm: Perm) {
        ui.fitText(f.rest.x, f.rest.y + 2, f.rest.w, ClientLocks.perm(perm) ?: "", Palette.textMuted)
        f.skip(14)
    }

    private fun jobs(ui: Ui, f: Flow, m: Mem, info: Info) {
        ui.section(f.take(14), tr("kami_claims.cap.jobs"), Vocabulary.ratio(m.jobs.size, info.jobSlots))
        val jobLock = lock(Perm.ASSIGN_JOBS)
        if (m.jobs.isEmpty()) {
            Draw.text(ui.g, tr("kami_claims.citizens.no_job"), f.rest.x, f.rest.y + 2, Palette.textMuted)
            f.skip(14)
        } else {
            val chips = ui.filtered("job-chips:${m.id}", listOf(m.jobs, jobLock == null, Format.locale, info.jobSpecs)) {
                m.jobs.map { ChipSpec(jobName(it), Palette.textSecondary, if (jobLock == null) Icons.CROSS else null) }
            }
            val rest = f.rest
            val stack = Stack(rest.x, rest.y, rest.w, 3)
            val clicked = ui.chipFlow(stack, chips, "job-chip:${m.id}")
            f.skip(stack.bottom - rest.y + 3)
            if (clicked != null && jobLock == null) act("job_remove", m.id, m.jobs[clicked], key = "job")
        }
        val full = m.jobs.size >= info.jobSlots
        val options = ui.filtered("job-options:${m.id}", listOf(m.jobs, Format.locale, info.jobSpecs)) {
            info.jobSpecs.filter { it.id !in m.jobs && it.assignment != CONTRACT }.map { j ->
                Option(j.id, j.name, null, tr("kami_claims.citizens.job.desc", Format.money(j.pay.toLong()), Format.number(j.quota)), Palette.opaque(j.color))
            }
        }
        val reason = jobLock ?: if (full) ClientLocks.raise(Capacity.JOB_SLOTS)?.reason ?: tr("kami_claims.citizens.job.full") else null
        ui.select(f.take(CONTROL_H), options, null, tr("kami_claims.citizens.job.add"), reason == null && options.isNotEmpty(), reason, key = "job-add:${m.id}")?.let { next ->
            act("job_add", m.id, next, key = "job")
        }
    }

    private fun quotas(ui: Ui, f: Flow, m: Mem, info: Info) {
        m.jobs.forEach { id ->
            val spec = info.jobSpecs.firstOrNull { it.id == id } ?: return@forEach
            val done = m.progress[id] ?: 0
            val row = f.take(10)
            ui.fitText(row.x, row.y + 1, 64, spec.name, Palette.textSecondary)
            ui.progress(Rect(row.x + 68, row.y + 3, row.w - 68 - 44, 4), done.toDouble() / max(1, spec.quota), if (done >= spec.quota) Palette.success else Palette.brass)
            Draw.textRight(ui.g, "$done/${spec.quota}", row.right, row.y + 1, Palette.textMuted)
        }
    }

    private fun plotLimit(ui: Ui, f: Flow, m: Mem, info: Info) {
        ui.fieldLabel(f.take(9), tr("kami_claims.citizens.plots.limit"))
        val editLock = lock(Perm.PLOTS)
        val state = plotLimits.getOrPut(m.id) { NumberState(m.plotLimit.toLong()) }
        val fallback = info.rankLine(m.rank)?.plots ?: 0
        val r = f.take(CONTROL_H)
        val label = tr("kami_claims.citizens.plots.reset")
        val bw = buttonWidth(label, Icons.UNDO)
        val ceiling = ClientResearch.state.max(Capacity.PLOTS).coerceAtLeast(1).toLong()
        if (!pending("plot_limit:${m.id}")) state.sync(m.plotLimit.toLong())
        ui.numberField(r.left(r.w - bw - 4), state, 0, ceiling, enabled = editLock == null, key = "plot-limit:${m.id}")?.let { act("plot_limit", m.id, it.toString(), key = "plot_limit:${m.id}") }
        if (ui.edgeButton(r, label, Icons.UNDO, enabled = editLock == null && m.plotLimit != fallback, disabledReason = editLock ?: tr("kami_claims.citizens.plots.reset.disabled"), key = "plot-reset:${m.id}")) {
            act("plot_limit", m.id, "-1", key = "plot_limit:${m.id}")
        }
    }

    private fun header(ui: Ui, m: Mem, r: Rect) {
        ui.avatar(m.id, r.x, r.y, 24, m.online)
        ui.fitText(r.x + 30, r.y + 2, r.w - 30, m.name, style = TextStyle.HEADING)
        val rank = info?.rankLine(m.rank)
        ui.fitText(r.x + 30, r.y + 13, r.w - 30, rank?.name.orEmpty() + " · " + when {
            info?.restricted(Perm.VIEW_PEOPLE) == true -> "-"
            m.online -> tr("kami_libs.common.online")
            else -> Format.ago(m.seen)
        }, rank?.tint ?: Palette.textMuted)
    }

    private fun requests(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.requests.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.citizens.requests.empty.title"), tr("kami_claims.citizens.requests.empty.desc"), Illustrations.CITIZENS)
            return
        }
        ui.scroll("requests", r, info.requests.size * 30) { area ->
            info.requests.forEachIndexed { i, m ->
                val row = Rect(area.x, area.y + i * 30, area.w, 26)
                Draw.sprite(ui.g, Sprites.CARD, row)
                ui.attention(row, app.isFocus("requests") && i == 0)
                ui.avatar(m.id, row.x + 5, row.y + 5, 16)
                val approve = tr("kami_libs.common.accept")
                val deny = tr("kami_libs.common.decline")
                val aw = buttonWidth(approve, Icons.CHECK)
                val dw = buttonWidth(deny)
                val textW = row.w - 26 - aw - dw - 16
                ui.fitText(row.x + 26, row.y + 5, textW, m.name, style = TextStyle.HEADING)
                val citizenship = ClaimsStore.players.firstOrNull { it.id == m.id }?.citizenships?.firstOrNull { it.via.isEmpty() }
                ui.fitText(row.x + 26, row.y + 15, textW, citizenship?.let { tr("kami_claims.citizens.member_of", it.country) } ?: tr("kami_libs.common.no_country"), Palette.textMuted)
                val staff = lock(Perm.INVITE)
                val actions = Row(Rect(row.x, row.y + 4, row.w - 6, CONTROL_H))
                if (ui.edgeButton(actions, deny, enabled = staff == null, disabledReason = staff, key = "deny:${m.id}")) act("deny", m.id, key = "deny:${m.id}")
                if (ui.edgeButton(actions, approve, Icons.CHECK, ButtonStyle.PRIMARY, staff == null, staff, pending = pending("approve:${m.id}"), key = "approve:${m.id}")) act("approve", m.id, key = "approve:${m.id}")
            }
        }
    }

    private fun sent(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.invitesSent.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.citizens.sent.empty.title"), tr("kami_claims.citizens.sent.empty.desc"), Illustrations.CITIZENS)
            return
        }
        ui.scroll("invites-sent", r, info.invitesSent.size * 20) { area ->
            info.invitesSent.forEachIndexed { i, m ->
                val row = Rect(area.x, area.y + i * 20, area.w, 18)
                ui.avatar(m.id, row.x, row.y + 3, 12)
                Draw.text(ui.g, m.name, row.x + 16, row.y + 5, Palette.text)
                Draw.textRight(ui.g, tr("kami_claims.citizens.sent.pending"), row.right, row.y + 5, Palette.textMuted)
            }
        }
    }
}

private const val CONTRACT = "contract"
private const val HEIR_MIN_DAYS = 7
