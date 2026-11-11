package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.gov.JobRules
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.net.ContractLine
import kami.claims.net.Info
import kami.claims.net.JobSpecLine
import kami.libs.ui.app.formDialog
import kami.libs.ui.core.Dim
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.TABLE_ROW_H
import kami.libs.ui.widget.avatar
import kami.libs.ui.widget.button
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.progress
import kami.libs.ui.widget.section
import kami.libs.ui.widget.select
import net.minecraft.client.Minecraft

internal class JobPeople(private val page: ClaimsPage) {
    private var pick: String? = null
    private var pickFor = ""
    private var grantee: String? = null
    private val grantX = NumberState(0)
    private val grantZ = NumberState(0)
    private var grantSeeded = false

    fun holders(ui: Ui, s: Stack, info: Info, job: JobSpecLine) {
        val me = JobsView.me()
        ui.section(s, tr("kami_claims.common.workers"), Format.number(job.holders.size))
        if (job.holders.isEmpty()) ui.fitText(s.x, s.take(TABLE_ROW_H).y + 3, s.w, tr("kami_claims.jobs.holders.empty"), Palette.textMuted)
        job.holders.forEach { holder(ui, s.take(TABLE_ROW_H), info, job, it) }
        if (JobsView.can(info, Perm.ASSIGN_JOBS) && job.active && job.assignment != "contract") addRow(ui, s, info, job)
        if (!info.delegated) selfRow(ui, s, job, me)
    }

    private fun holder(ui: Ui, r: Rect, info: Info, job: JobSpecLine, id: String) {
        val row = Row(r, 4)
        ui.avatar(id, row.take(AVATAR + 2).x, r.y + 1, AVATAR, null)
        if (JobsView.can(info, Perm.ASSIGN_JOBS) && ui.iconButton(row.takeFromRight(SMALL_H), Icons.REMOVE, tr("kami_libs.common.remove"), key = "holder:${job.id}:$id:remove")) page.act("job_remove", id, job.id, key = "job_remove:$id")
        val done = info.members.firstOrNull { it.id == id }?.progress?.get(job.id) ?: 0
        val progress = tr("kami_libs.format.of", Format.number(done), Format.number(job.quota))
        Draw.textRight(ui.g, progress, row.rest.right, r.y + 3, Palette.textSecondary)
        ui.fitText(row.rest.x, r.y + 3, row.rest.w - Draw.width(progress) - 6, JobsView.nameOf(info, id), key = "holder:${job.id}:$id")
    }

    private fun addRow(ui: Ui, s: Stack, info: Info, job: JobSpecLine) {
        if (pickFor != job.id) { pickFor = job.id; pick = null }
        val row = Row(s.take(CONTROL_H), 4)
        val free = info.members.filter { it.id !in job.holders }.map { Option(it.id, it.name) }
        val add = tr("kami_libs.common.add")
        val busy = page.pending("job_add:${job.id}")
        if (ui.button(row.takeFromRight(buttonWidth(add, Icons.ADD)), add, Icons.ADD, ButtonStyle.PRIMARY, pick != null, tr("kami_claims.jobs.pick_player"), pending = busy, key = "holder:${job.id}:add")) {
            page.act("job_add", pick!!, job.id, key = "job_add:${job.id}")
            pick = null
        }
        ui.select(row.rest, free, pick, tr("kami_claims.jobs.pick_player"), key = "holder:${job.id}:pick")?.let { pick = it }
    }

    private fun selfRow(ui: Ui, s: Stack, job: JobSpecLine, me: String) {
        val row = s.take(CONTROL_H)
        when {
            me in job.holders -> if (ui.button(row, tr("kami_claims.jobs.leave"), Icons.REMOVE, key = "job:${job.id}:leave")) page.act("job_remove", me, job.id, key = "job_remove:$me")
            job.assignment == "open" && job.active -> if (ui.button(row, tr("kami_claims.jobs.take"), Icons.ADD, ButtonStyle.PRIMARY, key = "job:${job.id}:take")) page.act("job_take", job.id)
            else -> Unit
        }
    }

    fun contracts(ui: Ui, s: Stack, info: Info, job: JobSpecLine) {
        val mine = info.contracts.filter { it.job == job.id }
        ui.section(s, tr("kami_claims.jobs.contracts"), Format.number(mine.size))
        if (mine.isEmpty()) ui.fitText(s.x, s.take(TABLE_ROW_H).y + 3, s.w, tr("kami_claims.jobs.contracts.empty"), Palette.textMuted)
        mine.forEach { contract(ui, s.take(TABLE_ROW_H), info, it) }
        val offering = JobsView.can(info, Perm.ASSIGN_JOBS) && job.assignment == "contract" && job.active
        val reason = if (offering) null else tr("kami_claims.jobs.offer.disabled")
        if (JobsView.can(info, Perm.ASSIGN_JOBS) && ui.button(s.take(CONTROL_H), tr("kami_claims.jobs.offer.action"), Icons.HANDSHAKE, ButtonStyle.PRIMARY, offering, reason, key = "job:${job.id}:offer")) offer(info, job)
    }

    private fun contract(ui: Ui, r: Rect, info: Info, k: ContractLine) {
        val row = Row(r, 4)
        val canCancel = info.rank == PRESIDENT || k.offeredBy == JobsView.me()
        if (canCancel && ui.iconButton(row.takeFromRight(SMALL_H), Icons.REMOVE, tr("kami_libs.common.cancel"), key = "contract:${k.id}:cancel")) page.act("contract_cancel", k.id, key = "contract:${k.id}")
        if (!k.accepted && k.player == JobsView.me()) {
            val accept = tr("kami_libs.common.accept")
            if (ui.button(row.takeFromRight(buttonWidth(accept, Icons.CHECK)), accept, Icons.CHECK, ButtonStyle.PRIMARY, key = "contract:${k.id}:accept")) page.act("contract_accept", k.id, key = "contract:${k.id}")
        }
        val state = tr(if (k.accepted) "kami_claims.jobs.contract.accepted" else "kami_claims.jobs.contract.pending")
        val terms = "${Format.money(k.pay.toLong())} · ${Format.number(k.quota)}/${Format.days(k.period.toLong())} · $state"
        Draw.textRight(ui.g, Draw.fit(terms, row.rest.w / 2), row.rest.right, r.y + 3, if (k.accepted) Palette.success else Palette.warning)
        ui.fitText(row.rest.x, r.y + 3, row.rest.w / 2 - 4, JobsView.nameOf(info, k.player), key = "contract:${k.id}")
    }

    private fun offer(info: Info, job: JobSpecLine) {
        var player: String? = null
        val pay = NumberState(job.pay.toLong())
        val quota = NumberState(job.quota.toLong())
        val period = NumberState(job.period.toLong())
        val days = NumberState(DEFAULT_TERM)
        val maxPay = (page.limits?.maxJobPay ?: DEFAULT_MAX_PAY).toLong()
        formDialog({ page.app.open(it) }, tr("kami_claims.jobs.offer.title"), job.name, Icons.HANDSHAKE, tr("kami_claims.jobs.offer.action"),
            { player != null }, { tr("kami_claims.jobs.pick_player") },
            { page.act("contract_offer", job.id, player!!, pay.value.toString(), quota.value.toString(), period.value.toString(), days.value.toString(), key = "contract_offer") }
        ) { r ->
            val s = Stack(r.x, r.y, r.w)
            fieldLabel(s.take(LABEL_H), tr("kami_libs.common.player"))
            select(s.take(CONTROL_H), info.members.map { Option(it.id, it.name) }, player, tr("kami_claims.jobs.pick_player"), key = "offer:player")?.let { player = it }
            val labels = s.take(LABEL_H).columns(2, 6)
            val cells = s.take(CONTROL_H).columns(2, 6)
            fieldLabel(labels[0], tr("kami_claims.jobs.field.pay"))
            fieldLabel(labels[1], tr("kami_claims.jobs.field.quota"))
            numberField(cells[0], pay, 0, maxPay, key = "offer:pay")
            numberField(cells[1], quota, 0, MAX_QUOTA, key = "offer:quota")
            val labels2 = s.take(LABEL_H).columns(2, 6)
            val cells2 = s.take(CONTROL_H).columns(2, 6)
            fieldLabel(labels2[0], tr("kami_claims.jobs.field.days"))
            fieldLabel(labels2[1], tr("kami_claims.jobs.offer.term"))
            numberField(cells2[0], period, 1, JobRules.MAX_PERIOD.toLong(), key = "offer:period")
            numberField(cells2[1], days, 1, MAX_TERM, key = "offer:term")
            s.bottom - r.y
        }
    }

    fun grants(ui: Ui, s: Stack, info: Info) {
        seedGrant()
        ui.section(s, tr("kami_claims.jobs.grants"))
        ui.select(s.take(CONTROL_H), info.members.map { Option(it.id, it.name) }, grantee, tr("kami_claims.jobs.pick_player"), key = "grant:player")?.let { grantee = it }
        val labels = s.take(LABEL_H).columns(2, 6)
        val cells = s.take(CONTROL_H).columns(2, 6)
        ui.fieldLabel(labels[0], "X")
        ui.fieldLabel(labels[1], "Z")
        ui.numberField(cells[0], grantX, -MAX_COORD, MAX_COORD, key = "grant:x")
        ui.numberField(cells[1], grantZ, -MAX_COORD, MAX_COORD, key = "grant:z")
        val row = Row(s.take(CONTROL_H), 4)
        val who = grantee
        val reason = tr("kami_claims.jobs.pick_player")
        val assign = tr("kami_claims.jobs.grant")
        if (ui.button(row.take(row.rest.w / 2), assign, Icons.ADD, ButtonStyle.PRIMARY, who != null, reason, key = "grant:assign")) page.act("assign", who!!, grantX.value.toString(), grantZ.value.toString(), Dim.current(), key = "grant")
        if (ui.button(row.rest, tr("kami_claims.jobs.revoke"), Icons.REMOVE, enabled = who != null, disabledReason = reason, key = "grant:revoke")) page.act("unassign", who!!, grantX.value.toString(), grantZ.value.toString(), Dim.current(), key = "grant")
    }

    private fun seedGrant() {
        if (grantSeeded) return
        val at = Minecraft.getInstance().player?.chunkPosition() ?: return
        grantX.commit(at.x.toLong())
        grantZ.commit(at.z.toLong())
        grantSeeded = true
    }

    fun stripHeight(info: Info) = info.members.firstOrNull { it.id == JobsView.me() }?.jobs.orEmpty().count { JobsView.job(info, it) != null }.coerceAtMost(STRIP_ROWS) * STRIP_ROW_H

    fun strip(ui: Ui, r: Rect, info: Info) {
        val mem = info.members.firstOrNull { it.id == JobsView.me() } ?: return
        mem.jobs.mapNotNull { JobsView.job(info, it) }.take(STRIP_ROWS).forEachIndexed { i, job ->
            val row = Row(Rect(r.x, r.y + i * STRIP_ROW_H, r.w, STRIP_ROW_H), 6)
            val done = mem.progress[job.id] ?: 0
            val label = tr("kami_libs.format.of", Format.number(done), Format.number(job.quota))
            Draw.fill(ui.g, Rect(row.take(6).x, r.y + i * STRIP_ROW_H + 4, 6, 6), JobsView.opaque(job.color))
            Draw.textRight(ui.g, "$label · ${Format.days(job.period.toLong())}", row.rest.right, r.y + i * STRIP_ROW_H + 3, Palette.textSecondary)
            ui.fitText(row.rest.x, r.y + i * STRIP_ROW_H + 3, STRIP_NAME_W, job.name, key = "mine:${job.id}")
            val bar = Rect(row.rest.x + STRIP_NAME_W + 6, r.y + i * STRIP_ROW_H + 5, (row.rest.w - STRIP_NAME_W - 120).coerceAtLeast(20), 5)
            ui.progress(bar, if (job.quota > 0) done.toDouble() / job.quota else 0.0, if (job.quota in 1..done) Palette.success else Palette.brass)
        }
    }

    private companion object {
        const val AVATAR = 10
        const val LABEL_H = 9
        const val DEFAULT_TERM = 30L
        const val MAX_TERM = 90L
        const val DEFAULT_MAX_PAY = 100
        const val MAX_COORD = 1_875_000L
        const val STRIP_ROWS = 3
        const val STRIP_ROW_H = 14
        const val STRIP_NAME_W = 90
    }
}
