package kami.claims.client.app.pages

import kami.claims.client.store.ClientResearch
import kami.claims.client.ClientClaims
import kami.claims.client.map.ChunkCells
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Finance
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.SectorShapes
import kami.claims.client.store.ClaimsStore
import kami.claims.gov.Perm
import kami.claims.net.SectorLine
import kami.claims.net.PreviewLine
import kami.claims.service.View
import kami.libs.ui.core.Dim
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*

class MapPlanner(private val page: ClaimsPage) {
    val selection = LinkedHashSet<Long>()
    var selectionRev = 0
        private set
    var plan = Plan.CLAIM
    var planType = ""
    var planning = false
    val sector = SectorEditor(page, this)
    private var lastPreviewSent = 0L
    private var previewWaitSince = 0L
    private var selectionTouchedAt = 0L
    private val argsMemo = Memo()
    private val keyMemo = Memo()
    private val countMemo = Memo()
    private val planModes = Memo()
    private val planTypes = Memo()
    private val outcomeGroups = Memo()

    private val app get() = page.app
    private val snap get() = page.snap
    private val info get() = page.info
    private val dim get() = Dim.current()
    private fun lock(perm: Perm) = page.lock(perm)
    private fun pending(key: String) = page.pending(key)
    private fun unkey(k: Long) = ChunkCells.cell(k)

    fun ensureType() {
        if (planType.isEmpty()) planType = snap.types.firstOrNull()?.name ?: ""
    }

    fun begin(p: Plan) {
        plan = p
        planning = true
        ClaimsStore.clearPreview()
        if (p == Plan.SECTOR) sector.startNew()
    }

    fun editSector(line: SectorLine) {
        selection.clear()
        SectorShapes.of(dim).firstOrNull { it.line.id == line.id }?.let { selection += it.cells }
        begin(Plan.SECTOR)
        sector.load(line)
        touchSelection()
    }

    fun clear() {
        selection.clear()
        touchSelection()
        if (plan == Plan.SECTOR) { plan = Plan.CLAIM; SectorDraft.chunks = emptyList() }
    }

    fun end() {
        clear()
        planning = false
    }

    fun touchSelection() {
        selectionRev++
        selectionTouchedAt = System.currentTimeMillis()
        previewWaitSince = 0L
    }

    fun choosePlan() {
        plan = MapModel.choose(plan, selection.map { unkey(it) }.map { (x, z) -> ClientClaims.at(dim, x, z)?.let { it.flags and View.OWN != 0 } })
    }

    private fun previewKey(kind: String, args: Array<String>) = "$kind|${args.joinToString("|")}"

    fun previewFor(): PreviewLine? {
        if (selection.isEmpty()) return null
        val kind = plan.kind ?: return null
        val key = keyMemo.of(selectionRev, plan, planType) { previewKey(kind, planArgs()) }
        return ClaimsStore.previewLine()?.takeIf { it.kind == kind && it.key == key }
    }

    private fun planArgs(): Array<String> = argsMemo.of(selectionRev, plan, planType) {
        MapModel.args(plan, planType, selection)
    }

    fun requestPreview() {
        val kind = plan.kind
        if (kind == null || selection.size < 1 || info == null || !ClientClaims.active(dim)) return
        val now = System.currentTimeMillis()
        if (now - selectionTouchedAt < 80L) return
        if (previewFor() != null) { previewWaitSince = 0L; return }
        if (now - lastPreviewSent < 150L) return
        if (previewFor() == null && previewWaitSince == 0L) previewWaitSince = System.currentTimeMillis()
        lastPreviewSent = now
        ClaimsStore.preview(kind, *planArgs())
    }

    private class Commit(val ready: Int, val preview: PreviewLine, val after: Long)

    fun draw(ui: Ui, r: Rect) {
        val size = ui.remember("plan-h") { IntArray(1) }
        val pinned = previewFor() != null
        val out = arrayOfNulls<Commit>(1)
        ui.scroll("map:plan", if (pinned) r.dropBottom(CONTROL_H + 4) else r, size[0]) { c ->
            val f = Flow(c.withHeight(4000), 4)
            out[0] = planBody(ui, f)
            size[0] = f.rest.y - c.y
        }
        val commit = out[0] ?: return
        val ready = commit.ready
        val button = r.bottom(CONTROL_H)
        val label = when (plan) {
            Plan.CLAIM -> tr("kami_claims.map.commit.claim", trn("kami_claims.unit.chunk", ready))
            Plan.RETYPE -> tr("kami_claims.map.commit.retype", trn("kami_claims.unit.chunk", ready))
            Plan.UNCLAIM -> tr("kami_claims.map.commit.unclaim", trn("kami_claims.unit.chunk", ready))
            Plan.SECTOR -> return
        }
        val keyName = when (plan) { Plan.CLAIM -> "claimcells"; Plan.RETYPE -> "typecells"; Plan.UNCLAIM -> "unclaimcells"; Plan.SECTOR -> return }
        if (ui.button(button, label, if (plan == Plan.UNCLAIM) Icons.REMOVE else Icons.CHECK, if (plan == Plan.UNCLAIM) ButtonStyle.DANGER else ButtonStyle.PRIMARY,
                ready > 0 && plan.lock(page) == null, plan.lock(page) ?: tr("kami_claims.map.commit.disabled"), pending = pending(keyName), key = "plan-go")) commit(ready, commit.preview, commit.after)
    }

    private fun planBody(ui: Ui, f: Flow): Commit? {
        val count = selection.size
        val head = f.take(12)
        Draw.text(ui.g, tr("kami_claims.chunks.selected", trn("kami_claims.unit.chunk", count)), head.x, head.y, TextStyle.HEADING)
        val clearLabel = tr("kami_claims.map.clear")
        if (ui.link(head.right - Draw.width(clearLabel), head.y, clearLabel, key = "clear-selection")) { clear(); return null }
        val (free, own) = countMemo.of(selectionRev, ClientClaims.countries, dim) {
            val entries = selection.map { unkey(it) }.map { (x, z) -> ClientClaims.at(dim, x, z) }
            entries.count { it == null } to entries.count { it != null && it.flags and View.OWN != 0 }
        }
        val iconOnly = f.rest.w < 170
        val sectorLock = lock(Perm.SECTORS)
        val modes = planModes.of(free, own, lock(Perm.CLAIM), lock(Perm.SPEND), lock(Perm.CHUNK_TYPE), lock(Perm.UNCLAIM), sectorLock, iconOnly, Format.locale) {
            fun label(text: String) = if (iconOnly) "" else text
            listOf(
                Option(Plan.CLAIM, label(tr("kami_claims.map.plan.claim")), Icons.ADD, tr("kami_claims.map.plan.claim.tooltip"), disabledReason = if (free == 0) tr("kami_claims.map.plan.claim.disabled") else Plan.CLAIM.lock(page)),
                Option(Plan.RETYPE, label(tr("kami_libs.common.type")), Icons.EDIT, tr("kami_claims.map.plan.retype.tooltip"), disabledReason = if (own == 0) tr("kami_claims.map.plan.own.disabled") else lock(Perm.CHUNK_TYPE)),
                Option(Plan.UNCLAIM, label(tr("kami_claims.chunks.release.action")), Icons.REMOVE, tr("kami_claims.map.plan.unclaim.tooltip"), disabledReason = if (own == 0) tr("kami_claims.map.plan.own.disabled") else lock(Perm.UNCLAIM)),
                Option(Plan.SECTOR, label(tr("kami_claims.map.plan.sector")), Icons.LAYERS, tr("kami_claims.map.plan.sector.tooltip"), disabledReason = when {
                    sectorLock != null -> sectorLock
                    own == 0 -> tr("kami_claims.map.plan.own.disabled")
                    else -> null
                })
            )
        }
        ui.segmented(f.take(CONTROL_H), modes, plan, key = "plan")?.let {
            if (plan == Plan.SECTOR) SectorDraft.chunks = emptyList()
            if (it == Plan.SECTOR) sector.startNew()
            plan = it
            ClaimsStore.clearPreview()
            touchSelection()
        }
        if (plan == Plan.SECTOR) { sector.body(ui, f); return null }
        if (plan != Plan.UNCLAIM) {
            ui.fieldLabel(f.take(9), tr(if (plan == Plan.CLAIM) "kami_claims.map.plan.claim_as" else "kami_claims.map.plan.new_type"))
            val typeOptions = planTypes.of(snap.types, Format.locale, ClientResearch.state, ClientResearch.defs) {
                snap.types.map { t ->
                    val look = Vocabulary.type(t.name)
                    Option(t.name, look.label, look.icon, "${Vocabulary.rate(t.price, t.period)} · ${look.description}", look.color, lock = ClientLocks.claimType(t.name))
                }
            }
            ui.select(f.take(CONTROL_H), typeOptions, planType, key = "plan-type")?.let { planType = it; ClaimsStore.clearPreview(); touchSelection() }
        }
        val preview = previewFor()
        f.skip(2)
        if (preview == null) {
            val row = f.take(14)
            val reason = plan.lock(page)
            when {
                !ClientClaims.active(dim) -> f.take(ui.callout(f.rest, Severity.INFO, tr("kami_claims.block.dimension")))
                reason != null -> f.take(ui.callout(f.rest, Severity.WARNING, reason))
                previewWaitSince != 0L && System.currentTimeMillis() - previewWaitSince > 1400L -> {
                    f.take(ui.callout(f.rest, Severity.WARNING, tr("kami_claims.map.check.delayed")))
                    if (ui.button(f.take(CONTROL_H), tr("kami_claims.map.check.retry"), Icons.PENDING, ButtonStyle.SECONDARY, key = "preview-retry")) {
                        ClaimsStore.clearPreview()
                        previewWaitSince = System.currentTimeMillis()
                        lastPreviewSent = 0L
                    }
                }
                else -> {
                    ui.spinner(row.x, row.y + 1)
                    Draw.text(ui.g, tr("kami_claims.map.check.running"), row.x + 12, row.y + 1, Palette.textMuted)
                }
            }
            return null
        }
        previewWaitSince = 0L
        val byOutcome = outcomeGroups.of(preview) { preview.cells.groupBy { it.outcome } }
        val ready = (byOutcome["CLAIM"]?.size ?: 0) + (byOutcome["FREE"]?.size ?: 0) + (byOutcome["RETYPE"]?.size ?: 0) + (byOutcome["UNCLAIM"]?.size ?: 0)
        ui.section(f.take(14), tr("kami_claims.map.result"))
        byOutcome["CLAIM"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.success, tr("kami_claims.map.result.claim", Format.number(it.size)), Icons.CHECK) }
        byOutcome["FREE"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.success, tr("kami_claims.map.result.free", Format.number(it.size)), Icons.STAR) }
        byOutcome["RETYPE"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.warning, tr("kami_claims.map.result.retype", Format.number(it.size)), Icons.EDIT) }
        byOutcome["UNCLAIM"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.danger, tr("kami_claims.done.released", Format.number(it.size)), Icons.REMOVE) }
        byOutcome["SKIP"]?.let { outcomeRow(ui, f.take(TABLE_ROW_H), Palette.textMuted, tr("kami_claims.map.result.skip", Format.number(it.size)), Icons.CHEVRON_RIGHT) }
        byOutcome["BLOCKED"]?.groupBy { trJson(it.reason) }?.forEach { (reason, cells) ->
            val row = f.take(TABLE_ROW_H)
            outcomeRow(ui, row, Palette.danger, tr("kami_claims.map.result.blocked", Format.number(cells.size), reason), Icons.CROSS)
            ui.tooltip("blocked:$reason", row, reason)
        }
        byOutcome["RETYPE"]?.mapNotNull { it.reason.ifEmpty { null } }?.distinct()?.forEach { f.take(ui.callout(f.rest, Severity.WARNING, trJson(it))) }
        val info = info ?: return null
        val net = Finance.of(info).net
        val after = net - preview.upkeepPerDay.toLong()
        f.skip(4)
        ui.section(f.take(14), tr("kami_libs.common.cost"))
        if (plan == Plan.CLAIM) ui.property(f.take(11), tr("kami_claims.map.cost.now"), Format.money(preview.cost), if (preview.cost > info.treasury) Palette.danger else Palette.money)
        ui.property(f.take(11), tr("kami_claims.map.cost.upkeep"), perDayDelta(preview.upkeepPerDay), if (preview.upkeepPerDay > 0) Palette.danger else Palette.success)
        ui.property(f.take(11), tr("kami_claims.help.term.runway"), "${app.runwayText(info.treasury, net)} → ${app.runwayText(info.treasury - preview.cost, after)}", app.runwayColor(info.treasury - preview.cost, after))
        return Commit(ready, preview, after)
    }

    private fun perDayDelta(v: Double) = Format.perDay(tr("kami_libs.unit.money", (if (v > 0) "+" else "") + Format.decimal(v)))

    private fun outcomeRow(ui: Ui, r: Rect, color: Int, text: String, icon: Icon) {
        val x = r.x + Draw.leadIcon(ui.g, icon, r.x, r.centerY) + 2
        ui.fitText(x, r.y + 3, r.right - x, text, color)
    }

    private fun commit(ready: Int, preview: PreviewLine, after: Long) {
        val info = info ?: return
        val args = planArgs()
        val runwayAfter = MapModel.runwayAfter(info.treasury, preview.cost, after)
        when (plan) {
            Plan.CLAIM -> {
                val items = listOfNotNull(
                    if (runwayAfter < 7) Consequence(tr("kami_claims.map.commit.runway", trn("kami_claims.unit.day", runwayAfter)), Severity.DANGER) else null,
                    Consequence(tr("kami_claims.map.commit.cost", Format.money(preview.cost))),
                    Consequence(tr("kami_claims.chunks.retype.delta", perDayDelta(preview.upkeepPerDay)), if (preview.upkeepPerDay > 0) Severity.WARNING else Severity.NEUTRAL),
                    Consequence(tr("kami_claims.notice.release_lock"))
                )
                if (ClientClaims.prefs.skipClaimConfirm && runwayAfter >= 7) ClaimsStore.send("claimcells", *args, key = "claimcells")
                else Dialogs.confirm(app, tr("kami_claims.map.commit.claim.title", trn("kami_claims.unit.chunk", ready)), Vocabulary.type(planType).label, Icons.AREA, items, tr("kami_claims.map.plan.claim"), "claimcells", args)
            }
            Plan.RETYPE -> Dialogs.confirm(app, tr("kami_claims.chunks.retype.confirm.title", trn("kami_claims.unit.chunk", ready), Vocabulary.type(planType).label), null, Icons.EDIT, listOfNotNull(
                if (preview.cells.any { it.reason.isNotEmpty() && it.outcome == "RETYPE" }) Consequence(tr("kami_claims.map.commit.plots_lost"), Severity.DANGER) else null,
                Consequence(tr("kami_claims.chunks.retype.delta", perDayDelta(preview.upkeepPerDay))),
                Consequence(tr("kami_claims.map.commit.rules"))
            ), tr("kami_claims.chunks.retype.action"), "typecells", args)
            Plan.UNCLAIM -> Dialogs.confirm(app, tr("kami_claims.chunks.release.confirm.title", trn("kami_claims.unit.chunk", ready)), tr("kami_claims.chunks.release.confirm.subtitle"), Icons.REMOVE, listOf(
                Consequence(tr("kami_claims.chunks.release.open"), Severity.DANGER),
                Consequence(tr("kami_claims.map.commit.builds"), Severity.WARNING),
                Consequence(tr("kami_claims.chunks.release.saved", perDayDelta(-preview.upkeepPerDay)), Severity.SUCCESS)
            ), tr("kami_claims.chunks.release.action"), "unclaimcells", args, danger = true, hold = true)
            Plan.SECTOR -> return
        }
        ClaimsStore.clearPreview()
    }
}
