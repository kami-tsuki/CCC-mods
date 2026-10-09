package kami.claims.client.app.pages

import kami.claims.client.BorderMode
import kami.claims.client.ClientClaims
import kami.claims.client.ClientHooks
import kami.claims.client.Prefs
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Modules
import kami.libs.ui.app.Personal
import kami.libs.ui.app.NAV_ROW_H
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.map.TerrainCache
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

class HelpPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_libs.common.help")
    override val needsCountry = false
    private var chapter = 0

    private class Chapter(val id: String, val icon: Icon, val page: String?) {
        val title get() = tr("kami_claims.help.$id")
        val paragraphs get() = listOf(tr("kami_claims.help.$id.p1"), tr("kami_claims.help.$id.p2"))
    }

    private val chapters = listOf(
        Chapter("countries", Icons.FLAG, "welcome"),
        Chapter("land", Icons.AREA, "map"),
        Chapter("treasury", Icons.TREASURY, "budget"),
        Chapter("plots", Icons.HOUSE, "plots"),
        Chapter("jobs", Icons.TOOL, "jobs"),
        Chapter("protection", Icons.SHIELD, "protection"),
        Chapter("provinces", Icons.CHAIN, "provinces")
    )

    private val glossary = listOf("upkeep", "runway", "debt", "reserved", "plot", "lapse", "tribute", "nomansland")

    override fun draw(ui: Ui, r: Rect) {
        val (nav, body) = r.columnsFixed(150, Rect.FILL, gap = 10)
        chapters.forEachIndexed { i, c ->
            val row = Rect(nav.x, nav.y + i * (NAV_ROW_H + 2), nav.w, NAV_ROW_H)
            if (i == chapter) { Draw.fill(ui.g, row, Palette.selected); Draw.fill(ui.g, row.left(2), Palette.brass) }
            else if (ui.hovering(row)) Draw.fill(ui.g, row, Palette.hover)
            val x = row.x + 6 + Draw.leadIcon(ui.g, c.icon, row.x + 6, row.centerY) + 2
            ui.fitText(x, row.y + 4, row.right - x, c.title, if (i == chapter) Palette.text else Palette.textSecondary)
            if (ui.pressed(row) != null) chapter = i
        }
        val g = nav.dropTop(chapters.size * (NAV_ROW_H + 2) + 8)
        if (ui.button(Rect(g.x, g.y, g.w, CONTROL_H), tr("kami_claims.help.tour"), Icons.STAR, key = "tour")) app.startTour()
        val c = chapters[chapter]
        val card = ui.card(body.top((body.h * 0.6).toInt()), c.title, c.icon)
        val textW = card.w - 4
        ui.scroll("help-chapter", card, c.paragraphs.sumOf { Draw.paragraphHeight(it, textW) + 6 } + (if (c.page != null) 18 else 0)) { area ->
            var y = area.y
            c.paragraphs.forEach { y += Draw.paragraph(ui.g, it, area.x, y, textW) + 6 }
            c.page?.let { page -> if (ui.link(area.x, y + 2, tr("kami_claims.help.open", tr("kami_claims.nav.$page")), key = "open:$page")) app.navigate(Route(page)) }
        }
        val gb = ui.card(body.dropTop((body.h * 0.6).toInt() + 6), tr("kami_claims.help.glossary"), Icons.SCROLL)
        ui.scroll("glossary", gb, glossary.size * 12) { area ->
            glossary.forEachIndexed { i, term ->
                Draw.text(ui.g, tr("kami_claims.help.term.$term"), area.x, area.y + i * 12, TextStyle.HEADING)
                val desc = tr("kami_claims.help.term.$term.desc")
                val shown = Draw.fit(desc, area.w - 90)
                Draw.text(ui.g, shown, area.x + 86, area.y + i * 12, Palette.textSecondary)
                if (shown != desc) ui.tooltip("term:$term", Rect(area.x, area.y + i * 12, area.w, 12), Tip.text(desc, tr("kami_claims.help.term.$term")))
            }
        }
    }
}

class SettingsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_libs.common.settings")
    override val needsCountry = false

    private fun save() = ClientClaims.savePrefs()
    private val keyList = Memo()

    override fun draw(ui: Ui, r: Rect) {
        val p = ClientClaims.prefs
        val (left, right) = r.columns(2, 10)
        ui.scroll("settings-left", left, 136 + 80 + 112 + 12) { leftColumn(ui, it, p) }
        ui.scroll("settings-right", right, 68 + 92 + 116 + 140 + 18) { rightColumn(ui, it, p) }
    }

    private fun leftColumn(ui: Ui, left: Rect, p: Prefs) {
        val lf = Flow(left, 6)
        val world = ui.card(lf.take(136), tr("kami_claims.settings.borders"), Icons.SHIELD)
        val wf = Flow(world, 4)
        ui.fieldLabel(wf.take(9), tr("kami_claims.settings.borders.mode"))
        ui.segmented(wf.take(CONTROL_H), listOf(
            Option(BorderMode.OFF, tr("kami_claims.border_mode.off"), description = tr("kami_claims.border_mode.off.desc")),
            Option(BorderMode.AUTO, tr("kami_claims.border_mode.auto"), description = tr("kami_claims.border_mode.auto.desc")),
            Option(BorderMode.ALWAYS, tr("kami_claims.border_mode.always"), description = tr("kami_claims.border_mode.always.desc")),
            Option(BorderMode.BUILDER, tr("kami_claims.border_mode.builder"), description = tr("kami_claims.border_mode.builder.desc"))
        ), p.borderMode, key = "border-mode")?.let { p.borderMode = it; save() }
        ui.toggle(wf.take(14), p.borderLines, tr("kami_claims.settings.borders.lines"), key = "lines")?.let { p.borderLines = it; save() }
        ui.toggle(wf.take(14), p.blockHints, tr("kami_claims.settings.borders.hints"), tip = tr("kami_claims.settings.borders.hints.tooltip"), key = "hints")?.let { p.blockHints = it; save() }
        ui.fieldLabel(wf.take(9), tr("kami_claims.settings.borders.density"), Format.number(p.borderDensity))
        ui.slider(wf.take(CONTROL_H), p.borderDensity.toDouble(), 50.0, 800.0, 50.0, key = "density")?.let { p.borderDensity = it.toInt(); save() }
        val hud = ui.card(lf.take(80), tr("kami_claims.settings.hud"), Icons.EYE)
        val hf = Flow(hud, 4)
        ui.toggle(hf.take(14), p.hud, tr("kami_claims.settings.hud.territory"), key = "hud")?.let { p.hud = it; save() }
        ui.toggle(hf.take(14), p.hudBorderDistance, tr("kami_claims.settings.hud.distance"), key = "hud-dist")?.let { p.hudBorderDistance = it; save() }
        ui.toggle(hf.take(14), p.worldToasts, tr("kami_claims.settings.hud.toasts"), tip = tr("kami_claims.settings.hud.toasts.tooltip"), key = "toasts")?.let { p.worldToasts = it; save() }
        val map = ui.card(lf.remaining(), tr("kami_claims.nav.map"), Icons.MAP)
        val mf = Flow(map, 4)
        ui.toggle(mf.take(14), p.terrain, tr("kami_claims.settings.map.terrain"), key = "terrain")?.let { p.terrain = it; TerrainCache.enabled = it; save() }
        ui.toggle(mf.take(14), p.skipClaimConfirm, tr("kami_claims.settings.map.skip_confirm"), key = "skip-confirm")?.let { p.skipClaimConfirm = it; save() }
        val wipe = tr("kami_claims.settings.map.wipe")
        if (ui.button(mf.take(CONTROL_H).left(buttonWidth(wipe, Icons.REMOVE)), wipe, Icons.REMOVE, key = "wipe")) { TerrainCache.wipe(); app.toast(Severity.SUCCESS, tr("kami_claims.settings.map.wiped")) }
    }

    private val taxPrepaid = NumberState(3)
    private val taxDue = NumberState(12)
    private val taxLapse = NumberState(3)

    private fun taxRow(ui: Ui, row: Rect, on: Boolean, id: String, number: Triple<NumberState, Int, Int>?): Boolean? {
        val p = ClientClaims.prefs
        val (label, field) = if (number == null) row to null else row.columns(listOf(1.6f, 1f), 4).let { it[0] to it[1] }
        val toggled = ui.toggle(label, on, tr("kami_claims.settings.hud.tax.$id"), tip = tr("kami_claims.settings.hud.tax.$id.tooltip"), key = "tax-$id")
        if (number != null && field != null) {
            val (state, value, max) = number
            state.sync(value.toLong())
            val unit = tr(if (id == "due") "kami_libs.unit.hour.short" else "kami_libs.unit.day.short", "")
            ui.numberField(field, state, 1, max.toLong(), unit = unit, enabled = on, key = "tax-$id-n")?.let {
                when (id) { "prepaid" -> p.taxPrepaidDays = it.toInt(); "due" -> p.taxDueHours = it.toInt(); else -> p.taxLapseDays = it.toInt() }
                save()
            }
        }
        return toggled
    }

    private fun rightColumn(ui: Ui, right: Rect, p: Prefs) {
        val rf = Flow(right, 6)
        val look = ui.card(rf.take(68), tr("kami_libs.common.interface"), Icons.BRUSH)
        val inf = Flow(look, 4)
        Draw.paragraph(ui.g, tr("kami_claims.settings.interface.moved"), look.x, inf.take(18).y, look.w, maxLines = 2)
        val prefs = Modules.all.firstOrNull { it.id == Personal.MODULE }
        val open = tr("kami_claims.settings.interface.open")
        if (ui.button(inf.take(CONTROL_H).left(buttonWidth(open, Icons.SETTINGS)), open, Icons.SETTINGS, enabled = prefs != null, disabledReason = tr("kami_claims.settings.interface.missing"), key = "prefs")) prefs?.open()
        val guide = ui.card(rf.take(92), tr("kami_claims.settings.guidance"), Icons.HELP)
        val gf = Flow(guide, 4)
        if (ui.button(gf.take(CONTROL_H), tr("kami_claims.help.tour"), Icons.STAR, key = "tour")) app.startTour()
        if (ui.button(gf.take(CONTROL_H), tr("kami_claims.settings.guidance.undismiss", Format.number(p.dismissed.size)), Icons.BELL, enabled = p.dismissed.isNotEmpty(), disabledReason = tr("kami_claims.settings.guidance.undismiss.disabled"), key = "undismiss")) { p.dismissed.clear(); save() }
        if (ui.button(gf.take(CONTROL_H), tr("kami_claims.settings.guidance.goals"), Icons.CHECK, enabled = p.hiddenSteps, disabledReason = tr("kami_claims.settings.guidance.goals.disabled"), key = "goals")) { p.hiddenSteps = false; save() }
        val tax = ui.card(rf.take(116), tr("kami_claims.settings.hud.tax"), Icons.COIN)
        val tf = Flow(tax, 4)
        taxRow(ui, tf.take(CONTROL_H), p.taxCondDebt, "debt", null)?.let { p.taxCondDebt = it; save() }
        taxRow(ui, tf.take(CONTROL_H), p.taxCondPrepaid, "prepaid", Triple(taxPrepaid, p.taxPrepaidDays, 30))?.let { p.taxCondPrepaid = it; save() }
        taxRow(ui, tf.take(CONTROL_H), p.taxCondDue, "due", Triple(taxDue, p.taxDueHours, 72))?.let { p.taxCondDue = it; save() }
        taxRow(ui, tf.take(CONTROL_H), p.taxCondLapse, "lapse", Triple(taxLapse, p.taxLapseDays, 30))?.let { p.taxCondLapse = it; save() }
        val keys = ui.card(rf.remaining(), tr("kami_claims.settings.keys"), Icons.GENERIC)
        val kf = Flow(keys, 3)
        keyList.of(Format.locale, ClientHooks.openKey(), ClientHooks.bordersKey()) { listOf(ClientHooks.openKey() to tr("key.kami_claims.open"), ClientHooks.bordersKey() to tr("key.kami_claims.borders"), "M" to tr("kami_claims.nav.map"), "?" to tr("kami_claims.tour.help"), "Esc" to tr("kami_claims.settings.keys.escape"), "Alt+←" to tr("kami_libs.pager.prev.tooltip"), tr("kami_claims.settings.keys.groups.key") to tr("kami_claims.settings.keys.groups")) }.forEach { (k, v) -> ui.keyHints(keys.x, kf.take(13).y, listOf(k to v)) }
    }
}
