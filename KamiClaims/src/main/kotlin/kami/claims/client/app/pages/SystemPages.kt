package kami.claims.client.app.pages

import kami.claims.client.BorderMode
import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.NAV_ROW_H
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.UiScale
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
    override val title get() = tr("kami_claims.nav.help")
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
            Draw.text(ui.g, Draw.fit(c.title, row.right - x), x, row.y + 4, if (i == chapter) Palette.text else Palette.textSecondary)
            if (ui.pressed(row) != null) chapter = i
        }
        val g = nav.dropTop(chapters.size * (NAV_ROW_H + 2) + 8)
        if (ui.button(Rect(g.x, g.y, g.w, CONTROL_H), tr("kami_claims.help.tour"), Icons.STAR, key = "tour")) app.startTour()
        val c = chapters[chapter]
        val card = ui.card(body.top((body.h * 0.6).toInt()), c.title, c.icon)
        var y = card.y
        c.paragraphs.forEach { y += Draw.paragraph(ui.g, it, card.x, y, card.w) + 6 }
        c.page?.let { page -> if (ui.link(card.x, y + 2, tr("kami_claims.help.open", tr("kami_claims.nav.$page")), key = "open:$page")) app.navigate(Route(page)) }
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
    override val title get() = tr("kami_claims.nav.settings")
    override val needsCountry = false

    private fun save() = ClientClaims.savePrefs()

    override fun draw(ui: Ui, r: Rect) {
        val p = ClientClaims.prefs
        val (left, right) = r.columns(2, 10)
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
        if (ui.button(mf.take(CONTROL_H).left(150), tr("kami_claims.settings.map.wipe"), Icons.REMOVE, key = "wipe")) { TerrainCache.wipe(); app.toast(Severity.SUCCESS, tr("kami_claims.settings.map.wiped")) }
        val rf = Flow(right, 6)
        val look = ui.card(rf.take(182), tr("kami_claims.settings.display"), Icons.BRUSH)
        val lf2 = Flow(look, 4)
        ui.fieldLabel(lf2.take(9), tr("kami_claims.settings.display.scale"))
        ui.segmented(lf2.take(CONTROL_H), UiScale.choices.map { Option(it, Format.percent(it.toDouble())) }, UiScale.snap(p.uiScale), key = "ui-scale")?.let { p.uiScale = it; UiScale.factor = it; save() }
        ui.fieldLabel(lf2.take(9), tr("kami_claims.settings.display.colours"))
        ui.select(lf2.take(CONTROL_H), Palette.Vision.entries.map { Option(it.name, tr("kami_claims.settings.vision.${it.name.lowercase()}"), description = tr("kami_claims.settings.vision.${it.name.lowercase()}.desc")) }, p.vision, key = "vision")?.let {
            p.vision = it; Palette.vision = Palette.Vision.valueOf(it); save()
        }
        ui.toggle(lf2.take(14), p.reduceMotion, tr("kami_claims.settings.display.motion"), key = "motion")?.let { p.reduceMotion = it; save() }
        ui.fieldLabel(lf2.take(9), tr("kami_claims.settings.display.sounds"), Format.percent(p.sounds.toDouble()))
        ui.slider(lf2.take(CONTROL_H), p.sounds.toDouble(), 0.0, 1.0, 0.05, format = { Format.percent(it) }, key = "sounds")?.let { p.sounds = it.toFloat(); save() }
        ui.fieldLabel(lf2.take(9), tr("kami_claims.settings.display.tooltip_delay"), "${Format.number(p.tooltipDelay)} ms")
        ui.slider(lf2.take(CONTROL_H), p.tooltipDelay.toDouble(), 0.0, 1500.0, 50.0, format = { "${Format.number(it.toLong())} ms" }, key = "tip-delay")?.let { p.tooltipDelay = it.toInt(); save() }
        val guide = ui.card(rf.take(92), tr("kami_claims.settings.guidance"), Icons.HELP)
        val gf = Flow(guide, 4)
        if (ui.button(gf.take(CONTROL_H), tr("kami_claims.help.tour"), Icons.STAR, key = "tour")) app.startTour()
        if (ui.button(gf.take(CONTROL_H), tr("kami_claims.settings.guidance.undismiss", Format.number(p.dismissed.size)), Icons.BELL, enabled = p.dismissed.isNotEmpty(), disabledReason = tr("kami_claims.settings.guidance.undismiss.disabled"), key = "undismiss")) { p.dismissed.clear(); save() }
        if (ui.button(gf.take(CONTROL_H), tr("kami_claims.settings.guidance.goals"), Icons.CHECK, enabled = p.hiddenSteps, disabledReason = tr("kami_claims.settings.guidance.goals.disabled"), key = "goals")) { p.hiddenSteps = false; save() }
        val keys = ui.card(rf.remaining(), tr("kami_claims.settings.keys"), Icons.GENERIC)
        val kf = Flow(keys, 3)
        listOf("K" to tr("key.kami_claims.open"), "B" to tr("key.kami_claims.borders"), "M" to tr("kami_claims.nav.map"), "?" to tr("kami_claims.tour.help"), "Esc" to tr("kami_claims.settings.keys.escape"), "Alt+←" to tr("kami_libs.pager.prev.tooltip"), tr("kami_claims.settings.keys.groups.key") to tr("kami_claims.settings.keys.groups")).forEach { (k, v) -> ui.keyHints(keys.x, kf.take(13).y, listOf(k to v)) }
    }
}
