package kami.claims.client.app.pages

import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.Rank
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.net.JobLine
import kami.claims.net.Mem
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Tip
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
    override val title get() = tr("kami_claims.nav.citizens")
    override val help get() = listOf(
        Callout("citizens:tabs", tr("kami_claims.citizens.help.tabs"), tr("kami_claims.citizens.help.tabs.desc")),
        Callout("citizens:table", tr("kami_claims.citizens.tab.members"), tr("kami_claims.citizens.help.table.desc")),
        Callout("citizens:profile", tr("kami_claims.citizens.help.profile"), tr("kami_claims.citizens.help.profile.desc"))
    )
    private var tab = 0
    private val table = TableState<Mem>()
    private val search = TextState()
    private val invite = TextState()

    override fun opened(route: Route) { if (route.focus == "requests") tab = 1 }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val tabs = r.top(CONTROL_H)
        ui.anchor("citizens:tabs", tabs)
        val staff = lock("invite")
        ui.subTabs(tabs, listOf(
            TabItem(tr("kami_claims.citizens.tab.members"), Icons.PEOPLE, info.members.size, Severity.NEUTRAL),
            TabItem(tr("kami_claims.citizens.tab.requests"), Icons.INVITE, info.requests.size, Severity.INFO, staff),
            TabItem(tr("kami_claims.citizens.tab.sent"), Icons.SCROLL, info.invitesSent.size, Severity.NEUTRAL, staff)
        ), tab, "cit-tabs")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 6)
        when (tab) {
            0 -> members(ui, body)
            1 -> requests(ui, body)
            else -> sent(ui, body)
        }
    }

    private fun members(ui: Ui, r: Rect) {
        val info = info ?: return
        val profileW = if (app.compact) 0 else (r.w * 0.42).toInt().coerceIn(170, 240)
        val left = r.dropRight(profileW, if (profileW > 0) 8 else 0)
        val bar = Row(left.top(CONTROL_H), 6)
        ui.searchField(bar.take(140), search, tr("kami_claims.citizens.search"), key = "mem-search")
        val inviteLabel = tr("kami_claims.citizens.invite")
        val iw = buttonWidth(inviteLabel, Icons.INVITE)
        val inviteBtn = bar.takeFromRight(iw)
        ui.textField(bar.takeFromRight(120), invite, tr("kami_claims.field.player"), Icons.PERSON, maxLength = 16, key = "invite-name")
        if (ui.button(inviteBtn, inviteLabel, Icons.INVITE, ButtonStyle.PRIMARY, can("invite") && invite.text.length >= 3, lock("invite") ?: tr("kami_claims.field.player.disabled"), pending = pending("invite"), key = "invite-go")) {
            act("invite", invite.text, key = "invite")
            invite.set("")
        }
        val rows = info.members.filter { search.text.isBlank() || it.name.contains(search.text, true) }
        val tableRect = left.dropTop(CONTROL_H + 6)
        ui.anchor("citizens:table", tableRect)
        ui.table(tableRect, listOf(
            Column<Mem>(tr("kami_claims.citizens.col.name"), -1, sort = compareBy { it.name.lowercase() }) { _, c, m ->
                avatar(m.id, c.x, c.y + 1, 12, m.online)
                Draw.text(g, Draw.fit(m.name, c.w - 16), c.x + 16, c.y + 3, Palette.text)
            },
            Column<Mem>(tr("kami_claims.dashboard.you.rank"), 84, sort = compareBy { Vocabulary.rankOrder.indexOf(it.rank) }) { _, c, m ->
                val look = Vocabulary.rank(m.rank)
                val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2
                Draw.text(g, Draw.fit(look.label, c.right - x), x, c.y + 3, look.color)
            },
            Column<Mem>(tr("kami_claims.dashboard.you.job"), 90, sort = compareBy { it.job }) { _, c, m ->
                if (m.job.isEmpty()) Draw.text(g, "-", c.x, c.y + 3, Palette.textMuted)
                else {
                    val quota = info.jobList.firstOrNull { it.name == m.job }?.quota ?: 1
                    Draw.text(g, Draw.fit(Vocabulary.job(m.job), c.w - 34), c.x, c.y + 3, Palette.textSecondary)
                    progress(Rect(c.right - 30, c.y + 5, 30, 4), m.progress.toDouble() / max(1, quota), if (m.progress >= quota) Palette.success else Palette.brass)
                }
            },
            Column<Mem>(tr("kami_claims.citizens.col.seen"), 60, Align.RIGHT, compareBy { -it.seen }) { _, c, m -> Draw.textRight(g, if (m.online) tr("kami_claims.citizens.online") else Format.ago(m.seen), c.right, c.y + 3, if (m.online) Palette.success else Palette.textMuted) }
        ), rows, table, { it.id })
        if (profileW > 0) profile(ui, r.right(profileW), rows.firstOrNull { it.id in table.selected } ?: info.members.firstOrNull { it.id == snap.me })
    }

    private fun profile(ui: Ui, r: Rect, m: Mem?) {
        ui.anchor("citizens:profile", r)
        if (m == null) return
        val info = info ?: return
        Draw.sprite(ui.g, Sprites.PANEL, r)
        val f = Flow(r.inset(8), 4)
        val head = f.take(26)
        header(ui, m, head)
        val rank = Rank.entries.firstOrNull { it.name.equals(m.rank, true) } ?: Rank.CITIZEN
        ui.property(f.take(11), tr("kami_claims.citizens.since"), if (m.since > 0) Format.ago(m.since) else "-")
        ui.property(f.take(11), tr("kami_claims.nav.plots"), Format.number(m.plots))
        val job = info.jobList.firstOrNull { it.name == m.job }
        ui.property(f.take(11), tr("kami_claims.dashboard.you.job"), job?.let { "${Vocabulary.job(it.name)} · ${m.progress}/${it.quota}" } ?: tr("kami_claims.dashboard.you.no_job"))
        val me = m.id == snap.me
        val myRank = ClaimsStore.rank
        f.skip(4)
        ui.section(f.take(14), tr("kami_claims.dashboard.you.rank"))
        val targets = listOf(Rank.CITIZEN, Rank.OFFICER, Rank.CHANCELLOR)
        val rankLock = lock("rank") ?: when {
            me -> tr("kami_claims.citizens.rank.disabled.self")
            rank >= myRank -> tr("kami_claims.citizens.rank.disabled.higher")
            else -> null
        }
        ui.segmented(f.take(CONTROL_H), targets.map { t ->
            Option(t, Vocabulary.rank(t.name).label, Vocabulary.rank(t.name).icon, disabledReason = rankLock ?: if (t >= myRank) tr("kami_claims.citizens.rank.disabled.above") else if (t == Rank.CHANCELLOR && info.members.any { it.rank == "chancellor" && it.id != m.id }) tr("kami_claims.citizens.rank.disabled.chancellor") else null)
        }, rank, key = "rank:${m.id}")?.let { next -> rankDialog(m, rank, next) }
        f.skip(4)
        ui.section(f.take(14), tr("kami_claims.dashboard.you.job"))
        val jobLock = lock("jobs")
        ui.select(f.take(CONTROL_H), listOf(Option("", tr("kami_claims.citizens.no_job"), Icons.CROSS)) + info.jobList.map { j ->
            Option(j.name, Vocabulary.job(j.name), Vocabulary.type(j.type).icon, tr("kami_claims.citizens.job.desc", Vocabulary.type(j.type).label, Format.money(j.pay.toLong()), Format.number(j.quota)))
        }, m.job, enabled = jobLock == null, disabledReason = jobLock, key = "job:${m.id}")?.let { next ->
            if (next.isEmpty()) act("job_unassign", m.id, key = "job") else act("job_assign", m.id, next, key = "job")
        }
        val rest = f.rest
        var y = rest.bottom - CONTROL_H
        val memberLock = lock("members") ?: if (me) tr("kami_claims.citizens.member.disabled.self") else if (rank >= myRank) tr("kami_claims.citizens.member.disabled.higher") else null
        if (me && !info.delegated) {
            val presidentAlone = rank == Rank.PRESIDENT && info.members.size > 1
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.leave", info.name), Icons.BACK, ButtonStyle.DANGER, !presidentAlone, tr("kami_claims.citizens.leave.disabled"), key = "leave")) {
                Dialogs.confirm(app, tr("kami_claims.citizens.leave.confirm.title", info.name), null, Icons.BACK, listOfNotNull(
                    if (info.members.size == 1) Consequence(tr("kami_claims.citizens.leave.last"), Severity.DANGER) else null,
                    Consequence(tr("kami_claims.citizens.leave.loses"), Severity.DANGER),
                    Consequence(tr("kami_claims.citizens.leave.after"))
                ), tr("kami_claims.citizens.leave.action"), "leave", emptyArray(), danger = true, hold = true, typed = if (info.members.size == 1) info.name else null)
            }
            y -= CONTROL_H + 3
            if (rank == Rank.PRESIDENT) {
                if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.disband"), Icons.DANGER, ButtonStyle.DANGER, key = "disband")) {
                    Dialogs.confirm(app, tr("kami_claims.citizens.disband.confirm.title", info.name), null, Icons.DANGER, listOf(
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
        if (ui.button(Rect(rest.x, y, half, CONTROL_H), tr("kami_claims.citizens.kick"), Icons.BACK, ButtonStyle.DANGER, memberLock == null, memberLock, key = "kick:${m.id}")) {
            Dialogs.confirm(app, tr("kami_claims.citizens.kick.confirm.title", m.name), info.name, Icons.BACK, listOf(
                Consequence(tr("kami_claims.citizens.kick.loses", m.name), Severity.DANGER),
                Consequence(tr("kami_claims.citizens.kick.rejoin"))
            ), tr("kami_claims.citizens.kick"), "kick", arrayOf(m.id), danger = true, hold = true)
        }
        if (ui.button(Rect(rest.x + half + 4, y, half, CONTROL_H), tr("kami_claims.citizens.banish"), Icons.BAN, ButtonStyle.DANGER, memberLock == null, memberLock, key = "ban:${m.id}")) {
            Dialogs.confirm(app, tr("kami_claims.citizens.banish.confirm.title", m.name), info.name, Icons.BAN, listOf(
                Consequence(tr("kami_claims.citizens.banish.locked", m.name), Severity.DANGER),
                Consequence(tr("kami_claims.citizens.banish.lift"), Severity.WARNING)
            ), tr("kami_claims.citizens.banish"), "banish", arrayOf(m.id), danger = true, hold = true)
        }
        y -= CONTROL_H + 3
        if (myRank == Rank.PRESIDENT && !info.delegated) {
            if (ui.button(Rect(rest.x, y, rest.w, CONTROL_H), tr("kami_claims.citizens.president"), Icons.CROWN, key = "pres:${m.id}")) {
                Dialogs.confirm(app, tr("kami_claims.citizens.president.confirm.title", m.name), null, Icons.CROWN, listOf(
                    Consequence(tr("kami_claims.citizens.president.rights", m.name), Severity.DANGER),
                    Consequence(tr("kami_claims.citizens.president.you", Vocabulary.rank(if (rank >= Rank.OFFICER) rank.name else Rank.OFFICER.name).label), Severity.WARNING),
                    Consequence(tr("kami_claims.citizens.president.final"), Severity.DANGER)
                ), tr("kami_claims.citizens.president.action"), "president", arrayOf(m.id, "confirm"), danger = true, hold = true, typed = m.name)
            }
        }
    }

    private fun header(ui: Ui, m: Mem, r: Rect) {
        ui.avatar(m.id, r.x, r.y, 24, m.online)
        Draw.text(ui.g, Draw.fit(m.name, r.w - 30), r.x + 30, r.y + 2, TextStyle.HEADING)
        val look = Vocabulary.rank(m.rank)
        Draw.text(ui.g, Draw.fit(look.label + " · " + if (m.online) tr("kami_claims.citizens.online") else Format.ago(m.seen), r.w - 30), r.x + 30, r.y + 13, look.color)
    }

    private fun rankDialog(m: Mem, from: Rank, to: Rank) {
        if (from == to) return
        val caps = snap.caps
        fun rights(r: Rank) = caps.filter { (_, min) -> runCatching { Rank.valueOf(min.uppercase()) }.getOrNull()?.let { r >= it } == true }.keys
        val gained = rights(to) - rights(from)
        val lost = rights(from) - rights(to)
        val up = to > from
        Dialogs.confirm(app, tr(if (up) "kami_claims.citizens.promote.confirm.title" else "kami_claims.citizens.demote.confirm.title", m.name, Vocabulary.rank(to.name).label), null, Vocabulary.rank(to.name).icon,
            gained.map { Consequence(tr("kami_claims.citizens.rank.gains", Vocabulary.caps[it]?.description ?: it), Severity.WARNING) } +
                lost.map { Consequence(tr("kami_claims.citizens.rank.loses", Vocabulary.caps[it]?.description ?: it)) } +
                listOf(Consequence(tr("kami_claims.citizens.rank.notified", m.name))),
            tr(if (up) "kami_claims.citizens.promote" else "kami_claims.citizens.demote"), "rank", arrayOf(m.id, to.name.lowercase()))
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
                val approve = tr("kami_claims.chat.approve")
                val deny = tr("kami_claims.chat.deny")
                val aw = buttonWidth(approve, Icons.CHECK)
                val dw = buttonWidth(deny)
                val textW = row.w - 26 - aw - dw - 16
                Draw.text(ui.g, Draw.fit(m.name, textW, TextStyle.HEADING), row.x + 26, row.y + 5, TextStyle.HEADING)
                val citizenship = snap.players.firstOrNull { it.id == m.id }?.citizenships?.firstOrNull { it.via.isEmpty() }
                Draw.text(ui.g, Draw.fit(citizenship?.let { tr("kami_claims.citizens.member_of", it.country) } ?: tr("kami_claims.topbar.no_country"), textW), row.x + 26, row.y + 15, Palette.textMuted)
                val staff = lock("invite")
                if (ui.button(Rect(row.right - 6 - dw - 4 - aw, row.y + 4, aw, CONTROL_H), approve, Icons.CHECK, ButtonStyle.PRIMARY, staff == null, staff, pending = pending("approve:${m.id}"), key = "approve:${m.id}")) act("approve", m.id, key = "approve:${m.id}")
                if (ui.button(Rect(row.right - 6 - dw, row.y + 4, dw, CONTROL_H), deny, enabled = staff == null, disabledReason = staff, key = "deny:${m.id}")) act("deny", m.id, key = "deny:${m.id}")
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

class JobsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.jobs")
    override val help get() = listOf(
        Callout("jobs:cards", tr("kami_claims.nav.jobs"), tr("kami_claims.jobs.help.cards.desc")),
        Callout("jobs:payroll", tr("kami_claims.jobs.budget"), tr("kami_claims.jobs.help.budget.desc"))
    )
    private val edits = HashMap<String, Triple<NumberState, NumberState, NumberState>>()

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val payroll = r.bottom(40)
        ui.anchor("jobs:payroll", payroll)
        val cap = info.income * snap.jobShare / 100
        val pb = ui.card(payroll, tr("kami_claims.jobs.budget"), Icons.SCALES, if (info.jobs > cap) Severity.WARNING else null, trailing = tr("kami_claims.jobs.budget.used", Format.number(info.jobs), Format.perDay(Format.money(cap))))
        ui.progress(Rect(pb.x, pb.y + 4, pb.w, 6), info.jobs.toDouble() / max(1, cap), if (info.jobs > cap) Palette.danger else Palette.brass)
        val cards = r.dropBottom(40, 8)
        ui.anchor("jobs:cards", cards)
        if (info.jobList.isEmpty()) {
            ui.emptyState(cards, tr("kami_claims.jobs.empty.title"), tr("kami_claims.jobs.empty.desc"), Illustrations.JOBS)
            return
        }
        val lockReason = lock("jobs")
        val columns = if (cards.w > 520) 2 else 1
        val h = 108
        ui.scroll("jobs", cards, ((info.jobList.size + columns - 1) / columns) * (h + 6)) { area ->
            area.grid(columns, h, info.jobList.size, 6).forEachIndexed { i, cell -> jobCard(ui, cell, info.jobList[i], lockReason) }
        }
    }

    private fun jobCard(ui: Ui, r: Rect, j: JobLine, lockReason: String?) {
        val info = info ?: return
        val look = Vocabulary.type(j.type)
        val body = ui.card(r, Vocabulary.job(j.name), look.icon, trailing = look.label, key = "job:${j.name}")
        val state = edits.getOrPut(j.name) { Triple(NumberState(j.pay.toLong()), NumberState(j.quota.toLong()), NumberState(j.period.toLong())) }
        val dirty = state.first.value != j.pay.toLong() || state.second.value != j.quota.toLong() || state.third.value != j.period.toLong()
        if (!dirty) { state.first.sync(j.pay.toLong()); state.second.sync(j.quota.toLong()); state.third.sync(j.period.toLong()) }
        val cols = body.top(30).columns(3, 6)
        listOf(Triple(tr("kami_claims.jobs.field.pay"), state.first, 0L to (limits?.maxJobPay ?: 100).toLong()), Triple(tr("kami_claims.jobs.field.quota"), state.second, 0L to 100_000L), Triple(tr("kami_claims.jobs.field.days"), state.third, 1L to 30L)).forEachIndexed { i, (label, st, range) ->
            ui.fieldLabel(cols[i].top(9), label)
            ui.numberField(Rect(cols[i].x, cols[i].y + 10, cols[i].w, CONTROL_H), st, range.first, range.second, enabled = lockReason == null, key = "${j.name}:$i")
        }
        val workers = info.members.filter { it.job == j.name }
        val wy = body.y + 34
        Draw.text(ui.g, if (workers.isEmpty()) tr("kami_claims.jobs.no_workers") else trn("kami_claims.unit.worker", workers.size), body.x, wy, if (workers.isEmpty()) Palette.textMuted else Palette.textSecondary)
        workers.take(3).forEachIndexed { i, m ->
            val y = wy + 11 + i * 10
            Draw.text(ui.g, Draw.fit(m.name, 70), body.x, y, Palette.text)
            ui.progress(Rect(body.x + 74, y + 2, body.w - 130, 4), m.progress.toDouble() / max(1, j.quota), if (m.progress >= j.quota) Palette.success else Palette.brass)
            Draw.textRight(ui.g, "${m.progress}/${j.quota}", body.right, y, Palette.textMuted)
        }
        val counted = j.actions.joinToString(", ") { tr("kami_claims.action.$it") } + if (j.blocks.isNotEmpty()) " (${j.blocks.take(3).joinToString(", ")})" else ""
        ui.tooltip("job-help:${j.name}", body.top(30), Tip(tr("kami_claims.jobs.tooltip.title", Vocabulary.job(j.name)), listOf(
            tr("kami_claims.jobs.tooltip.counted", counted, look.label) to Palette.textSecondary,
            tr("kami_claims.jobs.tooltip.paid", Format.money(j.pay.toLong()), Format.number(j.quota), trn("kami_claims.unit.day", j.period)) to Palette.textSecondary
        )))
        if (dirty) {
            val saveLabel = tr("kami_claims.common.save")
            val undoLabel = tr("kami_claims.common.undo")
            val saveW = buttonWidth(saveLabel, Icons.SAVE)
            val save = Rect(body.right - saveW, body.bottom - 16, saveW, 16)
            if (ui.button(save, saveLabel, Icons.SAVE, ButtonStyle.PRIMARY, pending = pending("job_set:${j.name}"), key = "save:${j.name}")) {
                act("job_edit", j.name, state.first.value.toString(), state.second.value.toString(), state.third.value.toString(), key = "job_set:${j.name}")
            }
            if (ui.button(Rect(save.x - buttonWidth(undoLabel) - 4, save.y, buttonWidth(undoLabel), 16), undoLabel, key = "undo:${j.name}")) edits.remove(j.name)
            Draw.fill(ui.g, Rect(r.right - 4, r.y + 2, 2, 2), Palette.brass)
        }
    }
}

class RanksPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.ranks")
    override val help get() = listOf(Callout("ranks:matrix", tr("kami_claims.ranks.help.matrix"), tr("kami_claims.ranks.help.matrix.desc")))

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        ui.anchor("ranks:matrix", r)
        val ranks = Vocabulary.rankOrder
        val labelW = (r.w * 0.45).toInt()
        val colW = (r.w - labelW) / ranks.size
        val head = r.top(34)
        ranks.forEachIndexed { i, rank ->
            val c = Rect(head.x + labelW + i * colW, head.y, colW, head.h)
            val look = Vocabulary.rank(rank)
            if (rank == info.rank) Draw.fill(ui.g, Rect(c.x, r.y, c.w, r.h - 12), Palette.alpha(Palette.brass, 0x18))
            Draw.icon(ui.g, look.icon, c.centerX - 8, c.y)
            Draw.text(ui.g, look.label, c.centerX - Draw.width(look.label) / 2, c.y + 17, look.color)
            val count = info.members.count { it.rank == rank }
            Draw.text(ui.g, Format.number(count), c.centerX - Draw.width(Format.number(count)) / 2, c.y + 26, Palette.textMuted)
            ui.tooltip("rank:$rank", c, look.description)
        }
        Vocabulary.caps.entries.forEachIndexed { row, (cap, look) ->
            val y = r.y + 38 + row * MATRIX_ROW_H
            val line = Rect(r.x, y, r.w, MATRIX_ROW_H)
            if (row % 2 == 0) Draw.fill(ui.g, line, Palette.alpha(0xFFFFFF, 0x06))
            val labelX = r.x + 2 + Draw.leadIcon(ui.g, look.icon, r.x + 2, line.centerY) + 2
            Draw.text(ui.g, Draw.fit(look.label, r.x + labelW - labelX - 4), labelX, y + 4, Palette.text)
            ui.tooltip("cap:$cap", line.left(labelW), Tip.text(look.description, look.label))
            val min = snap.caps[cap]?.let { runCatching { Rank.valueOf(it.uppercase()) }.getOrNull() } ?: Rank.PRESIDENT
            ranks.forEachIndexed { i, rank ->
                val ok = Rank.valueOf(rank.uppercase()) >= min
                Draw.icon(ui.g, if (ok) Icons.CHECK else Icons.REMOVE, Rect(r.x + labelW + i * colW, y, colW, MATRIX_ROW_H))
            }
        }
        val footerX = r.x + 2 + Draw.leadIcon(ui.g, Icons.LOCK, r.x + 2, r.bottom - 5) + 2
        Draw.text(ui.g, tr("kami_claims.ranks.footer", Vocabulary.rank(info.rank).label), footerX, r.bottom - 9, Palette.textMuted)
    }
}

private const val MATRIX_ROW_H = 16
