package kami.essentials.client

import kami.essentials.skills.Constellation
import kami.essentials.skills.Denial
import kami.essentials.skills.Effects
import kami.essentials.skills.NodeKind
import kami.essentials.skills.SkillNode
import kami.essentials.skills.SkillTree
import kami.essentials.skills.SkillView
import kami.libs.skills.SkillClass
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.PersonalSection
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.PROGRESS_LABELLED_H
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.button
import kami.libs.ui.widget.card
import kami.libs.ui.widget.emptyState
import kami.libs.ui.widget.panel
import kami.libs.ui.widget.progressBar
import kami.libs.ui.widget.property
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.segmented
import kotlin.math.max

private const val SIDE_W = 190
private const val SCROLL_BAR = 4

val skillsSection = PersonalSection(
    "skills", 10, { "kami_essentials.skill.group" },
    { if (PersonalApp.instance.snap?.skills == null) emptyList() else listOf(NavItem("skills.tree", tr("kami_essentials.skill.tree"), Icons.STAR)) },
    { app, id -> if (id == "skills.tree") SkillsPage(app as PersonalApp) else null }
)

private fun effectLine(key: String, v: Double) = tr("kami_essentials.skill.effect.$key", Effects.value(key, Effects.capped(key, v)))

private fun SkillNode.kindLabel() = tr("kami_essentials.skill.kind.${kind.name.lowercase()}", cls.label())

class SkillsPage(private val app: PersonalApp) : Page() {
    override val title get() = tr("kami_essentials.skill.tree")
    override val subtitle get() = view()?.let { v -> SkillClass.of(v.cls)?.let { tr("kami_essentials.skill.subtitle", it.label(), v.level) } }
    override val help get() = listOf(
        Callout("skills:head", tr("kami_essentials.skill.help.level"), tr("kami_essentials.skill.help.level.desc")),
        Callout("skills:tree", tr("kami_essentials.skill.tree"), tr("kami_essentials.skill.help.tree.desc"))
    )
    private val graph = SkillMap.view()
    private val search = TextState()
    private var selected: Int? = null
    private var tab = "node"
    private val stateMemo = Memo()
    private val tipMemo = Memo()

    private fun view(): SkillView? = app.snap?.skills

    private fun state(v: SkillView): NodeState = stateMemo.of(v, search.text) { SkillMap.state(v, search.text, ::searchText) }

    private fun searchText(n: SkillNode) = n.title() + " " + n.effects.keys.joinToString(" ") { nameText(it) }

    override fun draw(ui: Ui, r: Rect) {
        val v = view() ?: run { ui.emptyState(r, tr("kami_essentials.skill.off"), tr("kami_essentials.skill.off.body")); return }
        head(ui, r.top(PROGRESS_LABELLED_H), v)
        val body = r.dropTop(PROGRESS_LABELLED_H + 6)
        val narrow = body.w < SIDE_W * 2 + 60
        val stacked = if (narrow) body.rows(listOf(2f, 1f), 6) else null
        val map = stacked?.get(0) ?: body.dropRight(SIDE_W + 6)
        val side = stacked?.get(1) ?: body.right(SIDE_W)
        ui.anchor("skills:tree", map)
        tree(ui, map, v)
        side(ui, side, v)
    }

    private fun head(ui: Ui, r: Rect, v: SkillView) {
        ui.anchor("skills:head", r)
        val cells = r.columns(listOf(1.2f, 1f, 0.55f, 0.9f), 8)
        val maxed = v.level >= v.maxLevel
        ui.progressBar(cells[0], v.xp - v.levelXp, if (maxed) 1 else v.nextXp - v.levelXp, tr("kami_essentials.skill.level", v.level), if (maxed) tr("kami_essentials.skill.max") else null, key = "skill-xp")
        ui.progressBar(cells[1], v.active.coerceAtMost(v.nextGate), v.nextGate.coerceAtLeast(1), tr("kami_essentials.skill.gate"), Format.duration((v.nextGate - v.active).coerceAtLeast(0) * 1000), color = Palette.info, key = "skill-gate")
        ui.progressBar(cells[2], (v.points - v.spent).toLong(), v.points.toLong().coerceAtLeast(1), tr("kami_essentials.skill.points"), Format.number(v.points - v.spent), color = Palette.success, key = "skill-points")
        ui.searchField(cells[3].bottom(CONTROL_H), search, tr("kami_essentials.skill.search"), key = "skill-search")
    }

    private fun tree(ui: Ui, r: Rect, v: SkillView) {
        ui.panel(r, sunken = true)
        val s = state(v)
        val pick = graph.frame(ui, r, selected ?: -1, "skill-tree") { SkillMap.draw(ui, r, it, v, s) }
        val hover = graph.hovered
        if (hover >= 0) {
            val node = SkillTree.nodes[hover]
            val t = tipMemo.of(hover, v) { tip(v, node) }
            ui.tooltip("skill:$hover", Rect(ui.mouseX - 4, ui.mouseY - 4, 8, 8)) { t }
        }
        pick?.let { click(v, SkillTree.nodes[it.id], it.twice) }
    }

    private fun click(v: SkillView, n: SkillNode, twice: Boolean) {
        selected = n.id
        tab = "node"
        if (Personal.viewing) return
        if (v.cls == null && n.kind == NodeKind.START) pick(n.cls)
        else if (twice && reason(v, n) == null && (v.nodes[n.id] ?: 0) < n.ranks) take(n)
    }

    private fun take(n: SkillNode) = ClientEssentials.request("skill_take", n.id.toString())

    private fun reason(v: SkillView, n: SkillNode): String? {
        val rank = v.nodes[n.id] ?: 0
        return when (SkillTree.denial(v.nodes, SkillClass.of(v.cls), v.level, v.points - v.spent, n)) {
            null, Denial.MAXED -> null
            Denial.NO_CLASS -> tr("kami_essentials.skill.error.no_class")
            Denial.PATH -> tr("kami_essentials.skill.error.path")
            Denial.KEYSTONE -> tr("kami_essentials.skill.need.keystone", SkillTree.nodes[n.alt].title())
            Denial.ALT -> tr("kami_essentials.skill.need.alt", SkillTree.nodes[n.alt].title())
            Denial.LEVEL -> tr("kami_essentials.skill.need.level", SkillTree.levelNeed(v.nodes, n, rank + 1))
            Denial.SECTOR -> tr("kami_essentials.skill.need.sector", n.gate?.sector(rank + 1) ?: 0, n.cls.label())
            Denial.POINTS -> tr("kami_essentials.skill.error.points")
        }
    }

    private fun lines(v: SkillView, n: SkillNode): List<Pair<String, Int>> {
        val rank = v.nodes[n.id] ?: 0
        val out = ArrayList<Pair<String, Int>>()
        out += n.kindLabel() to n.cls.color
        n.effects.forEach { (k, x) -> out += effectLine(k, x * max(1, rank)) to (if (x < 0) Palette.danger else Palette.text) }
        if (n.ranks > 1) {
            out += tr("kami_essentials.skill.rank", rank, n.ranks) to Palette.textSecondary
            if (rank in 1 until n.ranks) n.effects.forEach { (k, x) -> out += tr("kami_essentials.skill.next", effectLine(k, x * (rank + 1))) to Palette.textMuted }
        }
        if (n.alt >= 0) out += tr("kami_essentials.skill.alt", SkillTree.nodes[n.alt].title()) to (if (SkillTree.blocked(v.nodes, n)) Palette.danger else Palette.info)
        constellation(n)?.let { c ->
            val have = c.nodes.count { it in v.nodes }
            out += tr("kami_essentials.skill.constellation", nameText(c.name), have, c.nodes.size) to if (have == c.nodes.size) Palette.success else Palette.info
            c.bonus.forEach { (k, x) -> out += effectLine(k, x) to Palette.textSecondary }
        }
        return out
    }

    private fun constellation(n: SkillNode): Constellation? = SkillTree.constellations.getOrNull(n.cluster)

    private fun tip(v: SkillView, n: SkillNode): Tip {
        val out = lines(v, n).toMutableList()
        val why = reason(v, n)
        if (why != null) out += why to Palette.warning
        else if ((v.nodes[n.id] ?: 0) < n.ranks && !Personal.viewing) out += tr("kami_essentials.skill.double_click") to Palette.textMuted
        return Tip(n.title(), out)
    }

    private fun side(ui: Ui, r: Rect, v: SkillView) {
        if (v.cls == null) return classes(ui, r)
        val tabs = r.top(CONTROL_H)
        ui.segmented(tabs, listOf(Option("node", tr("kami_essentials.skill.tab.node")), Option("bonuses", tr("kami_essentials.skill.tab.bonuses"))), tab, key = "skill-tab")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 4)
        if (tab == "bonuses") bonuses(ui, body, v) else node(ui, body, v)
    }

    private fun node(ui: Ui, r: Rect, v: SkillView) {
        val n = selected?.let { SkillTree.nodes.getOrNull(it) }
        val box = ui.card(r, n?.title() ?: tr("kami_essentials.skill.details"), n?.cls?.icon() ?: Icons.STAR)
        val f = Flow(box, 2)
        val mine = SkillClass.of(v.cls)
        if (box.h >= 3 * (Draw.LINE + 2) + 4 + CONTROL_H * 2 + Draw.LINE * 2) {
            ui.property(f.take(Draw.LINE), tr("kami_essentials.skill.class"), mine?.label().orEmpty(), mine?.color ?: Palette.text)
            ui.property(f.take(Draw.LINE), tr("kami_essentials.skill.refunds"), "${v.refunds - v.refundsUsed} / ${v.refunds}")
            ui.property(f.take(Draw.LINE), tr("kami_essentials.skill.bonus"), tr("kami_essentials.skill.bonus.value", v.classBonus))
            f.take(4)
        }
        if (n == null) {
            val text = tr("kami_essentials.skill.pick")
            val a = f.take(Draw.paragraphHeight(text, box.w))
            Draw.paragraph(ui.g, text, a.x, a.y, a.w, Palette.textMuted)
            return
        }
        val bottom = box.bottom(CONTROL_H)
        val top = f.take(0).y
        val area = Rect(box.x, top, box.w, (bottom.y - 4 - top).coerceAtLeast(0))
        val why = reason(v, n)
        paragraphs(ui, "skill-node:${n.id}", area, lines(v, n) + listOfNotNull(why?.let { it to Palette.warning }))
        if (Personal.viewing) return
        val buttons = bottom.columns(2, 4)
        val rank = v.nodes[n.id] ?: 0
        val start = SkillTree.starts[mine] == n.id
        if (ui.button(buttons[0], tr(if (rank == 0) "kami_essentials.skill.take" else "kami_essentials.skill.upgrade"), Icons.ADD, ButtonStyle.PRIMARY, why == null && rank < n.ranks, why, key = "skill-take")) take(n)
        val refundable = rank > 0 && !start && v.refundsUsed < v.refunds
        if (ui.button(buttons[1], tr("kami_essentials.skill.refund"), Icons.UNDO, enabled = refundable, disabledReason = tr("kami_essentials.skill.refund.reason"), key = "skill-refund")) ClientEssentials.request("skill_refund", n.id.toString())
    }

    private fun bonuses(ui: Ui, r: Rect, v: SkillView) {
        val box = ui.card(r, tr("kami_essentials.skill.tab.bonuses"), Icons.STATS)
        val values = SkillTree.values(v.nodes).mapValues { (k, x) -> Effects.capped(k, x) }.filterValues { it != 0.0 }.toSortedMap()
        if (values.isEmpty()) {
            Draw.paragraph(ui.g, tr("kami_essentials.skill.bonuses.empty"), box.x, box.y, box.w, Palette.textMuted)
            return
        }
        paragraphs(ui, "skill-bonuses", box, values.map { (k, x) -> effectLine(k, x) to (if (x < 0) Palette.danger else Palette.text) })
    }

    private fun paragraphs(ui: Ui, key: String, r: Rect, rows: List<Pair<String, Int>>) {
        ui.scroll(key, r, rows.sumOf { Draw.paragraphHeight(it.first, r.w - SCROLL_BAR) + 2 }) { c ->
            var y = c.y
            rows.forEach { (text, color) -> y += Draw.paragraph(ui.g, text, c.x, y, c.w, color) + 2 }
        }
    }

    private fun classes(ui: Ui, r: Rect) {
        val box = ui.card(r, tr("kami_essentials.skill.choose"), Icons.STAR, Severity.INFO)
        val text = tr("kami_essentials.skill.choose.body")
        val h = Draw.paragraph(ui.g, text, box.x, box.y, box.w, Palette.textSecondary)
        val f = Flow(box.dropTop(h + 6), 3)
        SkillClass.entries.forEach { cls ->
            val row = f.take(CONTROL_H)
            if (ui.button(row, cls.label(), cls.icon(), enabled = !Personal.viewing, key = "skill-class-${cls.id}")) pick(cls)
            Draw.fill(ui.g, Rect(row.x, row.y + 2, 2, row.h - 4), Palette.opaque(cls.color))
        }
    }

    private fun pick(cls: SkillClass) {
        confirmDialog(
            app::open, tr("kami_essentials.skill.confirm", cls.label()), tr("kami_essentials.skill.confirm.sub"), cls.icon(),
            listOf(
                Consequence(tr("kami_essentials.skill.confirm.final"), Severity.DANGER),
                Consequence(tr("kami_essentials.skill.confirm.bonus", app.snap?.skills?.classBonus ?: 0), Severity.SUCCESS),
                Consequence(tr("kami_essentials.skill.confirm.free"))
            ),
            tr("kami_essentials.skill.confirm.primary"), danger = true, typed = cls.label()
        ) { ClientEssentials.request("skill_class", cls.id) }
    }
}
