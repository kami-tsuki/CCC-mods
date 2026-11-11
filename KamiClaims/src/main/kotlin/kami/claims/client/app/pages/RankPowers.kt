package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Vocabulary
import kami.claims.gov.Perm
import kami.claims.gov.PermGroup
import kami.claims.gov.RoleTemplate
import kami.libs.text.SearchQuery
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.disclosure
import kami.libs.ui.widget.permissionRow
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.select
import kami.libs.ui.widget.triState
import kami.libs.ui.widget.triStateToggle
import net.minecraft.locale.Language

class PowerSpec(
    val owner: String,
    val perms: Set<String>,
    val original: Set<String>,
    val universe: List<Perm>,
    val locked: String?,
    val templates: Boolean,
    val onChange: (Set<String>) -> Unit
)

class PowerEditor(private val page: ClaimsPage) {
    private val search = TextState()
    private val collapsed = HashSet<PermGroup>()

    fun draw(ui: Ui, r: Rect, spec: PowerSpec) {
        val bar = Row(r.top(CONTROL_H), 6)
        if (spec.templates) {
            val label = tr("kami_claims.ranks.template")
            ui.select(bar.takeFromRight(maxOf(TEMPLATE_W, Draw.width(label) + SELECT_PAD)), templates(), null, label, spec.locked == null, spec.locked, key = "power-template")?.let { applyTemplate(spec, it) }
        }
        ui.searchField(bar.rest, search, tr("kami_claims.ranks.search"), key = "power-search")
        val sections = sections(spec.universe)
        val total = sections.sumOf { (group, list) -> HEAD_H + if (expanded(group)) list.size * ROW_H else 0 }
        ui.scroll("powers:${spec.owner}", r.dropTop(CONTROL_H + 6), total) { area ->
            var y = area.y
            sections.forEach { (group, list) -> y += section(ui, Rect(area.x, y, area.w, 0), spec, group, list) }
        }
    }

    private var cachedText: String? = null
    private var cachedLanguage: Language? = null
    private var cachedUniverse: List<Perm>? = null
    private var cachedSections = emptyList<Pair<PermGroup, List<Perm>>>()

    private fun sections(universe: List<Perm>): List<Pair<PermGroup, List<Perm>>> {
        val language = Language.getInstance()
        if (cachedText != search.text || cachedLanguage !== language || cachedUniverse !== universe) {
            val query = SearchQuery.parse(search.text)
            cachedSections = PermGroup.entries
                .map { group -> group to universe.filter { it.group == group && matches(query, it) } }
                .filter { it.second.isNotEmpty() }
            cachedText = search.text
            cachedLanguage = language
            cachedUniverse = universe
        }
        return cachedSections
    }

    private fun matches(query: SearchQuery, perm: Perm): Boolean {
        val look = Vocabulary.perm(perm)
        return query.matches(look.label + " " + look.description)
    }

    private fun expanded(group: PermGroup) = search.text.isNotBlank() || group !in collapsed

    private fun templates() = RoleTemplate.entries.map { Option(it, tr("kami_claims.template.${it.key}"), null, tr("kami_claims.template.${it.key}.desc")) }

    private fun allowed(spec: PowerSpec, perm: Perm) = perm.name in spec.original || page.info?.myPerms?.contains(perm.name) == true

    private fun reason(spec: PowerSpec, perm: Perm) = spec.locked ?: if (allowed(spec, perm)) null else tr("kami_claims.ranks.lock.missing")

    private fun section(ui: Ui, at: Rect, spec: PowerSpec, group: PermGroup, list: List<Perm>): Int {
        val head = Rect(at.x, at.y, at.w, HEAD_H)
        val open = expanded(group)
        val held = list.count { it.name in spec.perms }
        val toggleRect = head.right(TOGGLE_W)
        if (ui.disclosure(head.dropRight(TOGGLE_W, 4), Vocabulary.group(group).label, open, "$held/${list.size}", key = "power-head:${spec.owner}:$group")) {
            if (!collapsed.remove(group)) collapsed += group
        }
        ui.triStateToggle(toggleRect, tr("kami_claims.ranks.all"), triState(held, list.size), spec.locked == null, spec.locked, key = "power-all:${spec.owner}:$group")?.let { grantAll(spec, list, it) }
        if (!open) return HEAD_H
        list.forEachIndexed { i, perm ->
            val look = Vocabulary.perm(perm)
            val row = Rect(at.x + ROW_INDENT, at.y + HEAD_H + i * ROW_H, at.w - ROW_INDENT, ROW_H)
            ui.permissionRow(row, look.label, perm.name in spec.perms, Vocabulary.risk(perm), look.description, reason(spec, perm), "perm:${spec.owner}:${perm.name}")
                ?.let { on -> spec.onChange(if (on) spec.perms + perm.name else spec.perms - perm.name) }
        }
        return HEAD_H + list.size * ROW_H
    }

    private fun grantAll(spec: PowerSpec, list: List<Perm>, on: Boolean) {
        if (!on) return spec.onChange(spec.perms - list.map { it.name }.toSet())
        val grantable = list.filter { allowed(spec, it) }
        val skipped = list.count { it.name !in spec.perms && !allowed(spec, it) }
        if (skipped > 0) page.app.toast(Severity.WARNING, tr("kami_claims.ranks.skipped", skipped))
        spec.onChange(spec.perms + grantable.map { it.name })
    }

    private fun applyTemplate(spec: PowerSpec, template: RoleTemplate) {
        if (spec.perms == spec.original) return stageTemplate(spec, template)
        confirmDialog(page.app::open, tr("kami_claims.ranks.template.confirm.title", tr("kami_claims.template.${template.key}")), null, Icons.WARNING,
            listOf(Consequence(tr("kami_claims.ranks.template.confirm.body"), Severity.WARNING)), tr("kami_claims.ranks.template")) { stageTemplate(spec, template) }
    }

    private fun stageTemplate(spec: PowerSpec, template: RoleTemplate) {
        val wanted = template.perms.filter { it in spec.universe }
        val granted = wanted.filter { allowed(spec, it) }
        if (granted.size < wanted.size) page.app.toast(Severity.WARNING, tr("kami_claims.ranks.skipped", wanted.size - granted.size))
        spec.onChange(granted.map { it.name }.toSet())
    }
}

private const val TEMPLATE_W = 130
private const val SELECT_PAD = 26
private const val TOGGLE_W = 56
private const val HEAD_H = 16
private const val ROW_H = 14
private const val ROW_INDENT = 8
