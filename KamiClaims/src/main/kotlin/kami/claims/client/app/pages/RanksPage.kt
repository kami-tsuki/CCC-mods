package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Vocabulary
import kami.claims.client.app.rankLine
import kami.claims.client.app.tint
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.restricted
import kami.claims.gov.CITIZEN
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.gov.RankRules
import kami.claims.net.AuditLine
import kami.claims.net.Info
import kami.claims.net.RankLine
import kami.claims.research.Capacity
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.COLOR_PICKER_H
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.MasterDetail
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TabItem
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.avatar
import kami.libs.ui.widget.button
import kami.libs.ui.widget.clickable
import kami.libs.ui.widget.colorPicker
import kami.libs.ui.widget.colorProblem
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.reorderList
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.section
import kami.libs.ui.widget.select
import kami.libs.ui.widget.sidePanel
import kami.libs.ui.widget.subTabs
import kami.libs.ui.widget.table
import kami.libs.ui.widget.textField

class RanksPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.ranks")
    override val help get() = listOf(
        Callout("ranks:list", tr("kami_claims.ranks.help.list"), tr("kami_claims.ranks.help.list.desc")),
        Callout("ranks:detail", tr("kami_claims.ranks.help.detail"), tr("kami_claims.ranks.help.detail.desc"))
    )
    private val layout = MasterDetail(0.34, 150, 230)
    private val selection = HashSet<Any>()
    private val drafts = RankDrafts()
    private val powers = PowerEditor(this)
    private val lookup = RankLookup()
    private val auditTable = TableState<AuditLine>()
    private var tab = 0
    private var createdName: String? = null

    init {
        subscribe(ClaimsStore.onOutcome {
            if (it.key == DELETE_KEY && it.ok) selection.clear()
            if (it.key == CREATE_KEY && !it.ok && it.confirm.isEmpty()) createdName = null
        })
    }

    override fun leaving(next: Route): Boolean {
        val info = info ?: return super.leaving(next)
        val problem = applyProblem(info)
        val review = (tr("kami_claims.ranks.leave.review").takeIf { canApply() && drafts.grantsPerms(info) }) ?: problem
        return guardLeave(next, drafts.count(info) > 0, if (canApply() && review == null) { { send(info) } } else null, review, drafts::clear) && super.leaving(next)
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        drafts.prune(info, selectedId(info))
        selectCreated(info)
        layout.draw(ui, r.dropBottom(BAR_H + 6, 4), app.compact, selection, { master(ui, it, info) }, { detail(ui, it, info) })
        val dirty = drafts.dirtyRanks(info).map { it.first.name } + drafts.dirtyGroups().map { tr("kami_claims.group.${it.lowercase()}") }
        val problem = applyProblem(info)
        val summary = if (pending(APPLY_KEY)) tr("kami_claims.ranks.applying") else dirty.joinToString(", ")
        ui.applyBar(r.bottom(BAR_H), drafts.count(info), summary, canApply() && problem == null, problem ?: lock(Perm.EDIT_RANKS), pending(APPLY_KEY), "ranks", { review(info) }, drafts::clear)
    }

    private fun canApply() = can(Perm.EDIT_RANKS) || can(Perm.EDIT_GROUPS)

    private fun applyProblem(info: Info) = drafts.dirtyRanks(info).filter { (line, draft) -> draft.identityChanged(line) }.firstNotNullOfOrNull { (line, draft) ->
        (NameCheck.problem(draft.name.text, limits) ?: colorProblem(draft.color))?.let { "${draft.name.text.trim().ifEmpty { line.name }}: $it" }
    }

    private fun selectCreated(info: Info) {
        val name = createdName ?: return
        info.ranks.firstOrNull { it.name == name }?.let {
            select(it.id)
            createdName = null
        }
    }

    private fun selectedId(info: Info): String {
        val picked = selection.firstOrNull() as? String
        return picked?.takeIf { it.startsWith(GROUP_PREFIX) || info.rankLine(it) != null } ?: info.ranks.firstOrNull()?.id.orEmpty()
    }

    private fun select(id: String) {
        selection.clear()
        selection += id
    }

    private fun master(ui: Ui, r: Rect, info: Info) {
        val listH = info.ranks.size * ROW_H
        val groupsH = SECTION_H + (GROUPS.size + 1) * ROW_H
        ui.scroll("ranks-master", r, listH + GAP + groupsH + GAP + CONTROL_H) { area ->
            ui.anchor("ranks:list", area)
            rankList(ui, Rect(area.x, area.y, area.w, listH), info)
            groups(ui, Rect(area.x, area.y + listH + GAP, area.w, groupsH), info)
            newRank(ui, Rect(area.x, area.y + listH + groupsH + 2 * GAP, area.w, CONTROL_H), info)
        }
    }

    private fun rankList(ui: Ui, r: Rect, info: Info) {
        val mine = ClaimsStore.myRank?.position ?: Int.MAX_VALUE
        val reorder = can(Perm.REORDER_RANKS)
        ui.reorderList(r, info.ranks.size, ROW_H, { it == 0 || it == info.ranks.lastIndex }, { from, to -> reorder && from > mine && to > mine }, reorder, "ranks-order", lock(Perm.REORDER_RANKS), { info.ranks[it].id }, { select(info.ranks[it].id) }) { i, row ->
            rankRow(this, row, info.ranks[i], selectedId(info) == info.ranks[i].id)
        }?.let { (from, to) -> info.ranks.getOrNull(from)?.let { move(it.id, to) } }
    }

    private fun rankRow(ui: Ui, row: Rect, line: RankLine, picked: Boolean) {
        if (picked) Draw.fill(ui.g, row, Palette.selected)
        Draw.fill(ui.g, Rect(row.x, row.centerY - DOT / 2, DOT, DOT), line.tint)
        val count = line.members.toString()
        Draw.textRight(ui.g, count, row.right - 4, row.y + 6, Palette.textMuted)
        ui.fitText(row.x + DOT + 4, row.y + 6, row.w - DOT - 12 - Draw.width(count), line.name, if (picked) Palette.text else Palette.textSecondary)
    }

    private fun move(id: String, to: Int) = enqueue("rank_move", id, to.toString(), key = "ranks:move")

    private fun groups(ui: Ui, r: Rect, info: Info) {
        val body = ui.section(Rect(r.x, r.y, r.w, SECTION_H), tr("kami_claims.ranks.groups"))
        GROUPS.forEachIndexed { i, key ->
            val row = Rect(body.x, body.y + i * ROW_H, body.w, ROW_H)
            val id = GROUP_PREFIX + key
            val picked = selectedId(info) == id
            if (picked) Draw.fill(ui.g, row, Palette.selected)
            val x = row.x + Draw.leadIcon(ui.g, if (key == "ALLIED") Icons.HANDSHAKE else Icons.CHAIN, row.x, row.centerY) + 4
            ui.fitText(x, row.y + 6, row.right - x - 4, tr("kami_claims.group.${key.lowercase()}"), if (picked) Palette.text else Palette.textSecondary)
            if (ui.clickable("group-pick:$key", row)) select(id)
        }
        val banished = Rect(body.x, body.y + GROUPS.size * ROW_H, body.w, ROW_H)
        val x = banished.x + Draw.leadIcon(ui.g, Icons.LOCK, banished.x, banished.centerY) + 4
        ui.fitText(x, banished.y + 6, banished.right - x - 4, tr("kami_claims.group.banished"), Palette.textMuted)
        ui.tooltip("group-banished", banished, tr("kami_claims.group.banished.desc"))
    }

    private fun newRank(ui: Ui, r: Rect, info: Info) {
        val full = info.ranks.size >= info.rankCap
        val reason = lock(Perm.CREATE_RANKS) ?: if (full) ClientLocks.raise(Capacity.RANKS)?.reason ?: tr("kami_claims.ranks.cap", info.ranks.size, info.rankCap) else null
        if (ui.button(r, tr("kami_claims.ranks.new.title"), Icons.ADD, ButtonStyle.PRIMARY, reason == null, reason, key = "rank-new")) RankDialogs.create(app) { createdName = it }
    }

    private fun detail(ui: Ui, r: Rect, info: Info) {
        ui.anchor("ranks:detail", r)
        val f = ui.sidePanel(r, 4, 8)
        val id = selectedId(info)
        if (id.startsWith(GROUP_PREFIX)) return groupDetail(ui, f, info, id.removePrefix(GROUP_PREFIX))
        rankDetail(ui, f, info, info.rankLine(id) ?: return)
    }

    private fun rankDetail(ui: Ui, f: Flow, info: Info, line: RankLine) {
        val draft = drafts.rank(line)
        val head = f.take(HEAD_H)
        Draw.fill(ui.g, Rect(head.x, head.y + 3, DOT + 2, DOT + 2), Palette.opaque(draft.color))
        ui.fitText(head.x + DOT + 8, head.y + 1, head.w - DOT - 8, draft.name.text.ifEmpty { line.name }, style = TextStyle.HEADING)
        Draw.text(ui.g, tr("kami_claims.ranks.holders", line.members), head.x + DOT + 8, head.y + 12, Palette.textMuted)
        val tabs = tabs(info, line)
        val current = tab.coerceAtMost(tabs.lastIndex)
        ui.subTabs(f.take(CONTROL_H), tabs, current, "rank-tabs")?.let { tab = it }
        f.skip(4)
        val body = f.rest
        when (current) {
            0 -> powers.draw(ui, body, PowerSpec(line.id, shown(line, draft), line.perms.toSet(), Perm.entries, rankLock(line), true) { draft.perms = it })
            1 -> members(ui, body, info, line)
            2 -> appearance(ui, body, info, line, draft)
            3 -> lookup.draw(ui, body, info)
            else -> audit(ui, body, info)
        }
    }

    private fun tabs(info: Info, line: RankLine) = listOfNotNull(
        TabItem(tr("kami_claims.ranks.tab.powers"), Icons.SHIELD),
        TabItem(tr("kami_claims.ranks.tab.members"), Icons.PEOPLE, line.members),
        TabItem(tr("kami_claims.ranks.tab.appearance"), Icons.EDIT),
        TabItem(tr("kami_claims.ranks.tab.lookup"), Icons.SEARCH),
        TabItem(tr("kami_claims.ranks.tab.audit"), Icons.LEDGER).takeIf { can(Perm.AUDIT) }
    )

    private fun shown(line: RankLine, draft: RankDraft) = if (line.id == PRESIDENT) Perm.entries.map { it.name }.toSet() else draft.perms

    private fun rankLock(line: RankLine): String? = when {
        line.id == PRESIDENT -> tr("kami_claims.ranks.lock.president")
        !can(Perm.EDIT_RANKS) -> lock(Perm.EDIT_RANKS)
        !ClaimsStore.outranks(line.id) -> tr("kami_claims.ranks.lock.above")
        else -> null
    }

    private fun groupDetail(ui: Ui, f: Flow, info: Info, key: String) {
        val head = f.take(HEAD_H)
        ui.fitText(head.x, head.y + 1, head.w, tr("kami_claims.group.${key.lowercase()}"), style = TextStyle.HEADING)
        Draw.text(ui.g, tr("kami_claims.group.${key.lowercase()}.desc"), head.x, head.y + 12, Palette.textMuted)
        val current = info.groups[key].orEmpty().toSet()
        f.skip(4)
        powers.draw(ui, f.rest, PowerSpec(GROUP_PREFIX + key, drafts.group(key, current), current, OUTSIDER_PERMS, lock(Perm.EDIT_GROUPS), false) { drafts.setGroup(key, it) })
    }

    private fun members(ui: Ui, r: Rect, info: Info, line: RankLine) {
        val holders = info.members.filter { it.rank == line.id }
        if (info.restricted(Perm.VIEW_PEOPLE)) {
            Draw.text(ui.g, ClientLocks.perm(Perm.VIEW_PEOPLE).orEmpty(), r.x + 2, r.y + 4, Palette.textMuted)
            return
        }
        if (holders.isEmpty()) {
            Draw.text(ui.g, tr("kami_claims.ranks.members.empty"), r.x + 2, r.y + 4, Palette.textMuted)
            return
        }
        val targets = info.ranks.filter { it.id != line.id && ClaimsStore.outranks(it.id) }
        val reason = lock(Perm.ASSIGN_RANKS) ?: if (!ClaimsStore.outranks(line.id)) tr("kami_claims.ranks.lock.above") else if (targets.isEmpty()) tr("kami_claims.ranks.members.no_target") else null
        val moveW = maxOf(MOVE_W, Draw.width(tr("kami_claims.ranks.move")) + SELECT_PAD)
        ui.scroll("rank-members:${line.id}", r, holders.size * MEMBER_H) { area ->
            holders.forEachIndexed { i, m ->
                val row = Rect(area.x, area.y + i * MEMBER_H, area.w, MEMBER_H)
                ui.avatar(m.id, row.x, row.y + 4, 12, m.online)
                ui.fitText(row.x + 18, row.y + 6, row.w - 18 - moveW - 6, m.name)
                ui.select(Rect(row.right - moveW, row.y + 2, moveW, CONTROL_H), targets.map { Option(it.id, it.name, null, null, it.tint) }, null, tr("kami_claims.ranks.move"), reason == null, reason, key = "move:${m.id}")
                    ?.let { id -> info.rankLine(id)?.let { RankDialogs.assign(app, info, m, it) } }
            }
        }
    }

    private fun appearance(ui: Ui, r: Rect, info: Info, line: RankLine, draft: RankDraft) {
        val lock = rankLock(line)
        val f = Flow(r, 4)
        ui.fieldLabel(f.take(9), tr("kami_claims.ranks.field.name"), "${draft.name.text.length}/${NameCheck.max(limits)}")
        val nameRect = f.take(CONTROL_H)
        ui.textField(nameRect, draft.name, line.name, Icons.STAR, lock == null, NameCheck.max(limits), NameCheck::allows, "rank-name:${line.id}")
        ui.tooltip("rank-name-lock:${line.id}", nameRect, lock)
        ui.fieldLabel(f.take(9), tr("kami_claims.ranks.field.color"))
        val colorRect = f.take(COLOR_PICKER_H)
        if (lock == null) ui.colorPicker(colorRect, draft.color, "rank-color:${line.id}")?.let { draft.color = it }
        else Draw.fill(ui.g, Rect(colorRect.x, colorRect.y, COLOR_PICKER_H, CONTROL_H), Palette.opaque(draft.color))
        ui.fieldLabel(f.take(9), tr("kami_claims.ranks.field.plots"))
        val plotsRect = f.take(CONTROL_H)
        ui.numberField(plotsRect, draft.plots, 0, RankRules.MAX_PLOTS.toLong(), enabled = lock == null, key = "rank-plots:${line.id}")
        ui.tooltip("rank-plots-lock:${line.id}", plotsRect, lock)
        val deleteReason = lock(Perm.CREATE_RANKS) ?: if (line.id == CITIZEN) tr("kami_claims.ranks.delete.citizen") else lock
        if (ui.button(f.rest.bottom(CONTROL_H), tr("kami_claims.ranks.delete.action"), Icons.REMOVE, ButtonStyle.DANGER, deleteReason == null, deleteReason, key = "rank-delete")) RankDialogs.delete(app, info, line)
    }

    private fun audit(ui: Ui, r: Rect, info: Info) {
        ui.table(r, listOf(
            Column.text<AuditLine>(tr("kami_claims.ranks.audit.when"), 64) { Format.ago(it.at) },
            Column.text<AuditLine>(tr("kami_claims.ranks.audit.actor"), 84) { it.actor },
            Column.text<AuditLine>(tr("kami_claims.ranks.audit.action"), 84) { Vocabulary.audit(it.action) },
            Column.text<AuditLine>(tr("kami_claims.ranks.audit.detail"), -1) { "${named(info, it.target)} ${named(info, it.detail)}".trim() }
        ), info.audit, auditTable, { "${it.at}:${it.actor}:${it.action}:${it.target}" })
    }

    private fun named(info: Info, id: String) =
        info.members.firstOrNull { it.id == id }?.name ?: info.rankLine(id)?.name ?: info.jobSpecs.firstOrNull { it.id == id }?.name ?: info.sectors.firstOrNull { it.id == id }?.name ?: id

    private fun review(info: Info) = Dialogs.review(app, tr("kami_claims.ranks.review.title"), tr("kami_claims.ranks.review.subtitle"), Icons.STAR, drafts.lines(info), tr("kami_libs.common.apply")) { send(info) }

    private fun send(info: Info) {
        drafts.dirtyRanks(info).forEach { (line, draft) ->
            if (draft.identityChanged(line)) enqueue("rank_edit", line.id, draft.name.text.trim(), draft.color.toString(), key = APPLY_KEY)
            if (draft.plots.value != line.plots.toLong()) enqueue("rank_plots", line.id, draft.plots.value.toString(), key = APPLY_KEY)
            if (draft.perms != line.perms.toSet()) enqueue("rank_perms", line.id, draft.perms.sorted().joinToString(","), key = APPLY_KEY)
        }
        drafts.dirtyGroups().forEach { enqueue("group_perms", it, drafts.stagedGroup(it).sorted().joinToString(","), key = APPLY_KEY) }
    }
}

private val GROUPS = listOf("ALLIED", "PROVINCE", "PARENT")
private val OUTSIDER_PERMS = Perm.entries.filter { it in Perm.outsiderAllowed }
private const val GROUP_PREFIX = "group:"
private const val APPLY_KEY = "ranks:apply"
private const val CREATE_KEY = "ranks:create"
private const val DELETE_KEY = "ranks:delete"
private const val ROW_H = 20
private const val SECTION_H = 13
private const val GAP = 8
private const val DOT = 6
private const val HEAD_H = 26
private const val BAR_H = 28
private const val MEMBER_H = 22
private const val MOVE_W = 110
private const val SELECT_PAD = 26
