package kami.claims.client.app.pages

import kami.claims.client.BorderMode
import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Vocabulary
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.map.TerrainCache
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*

class HelpPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Help"
    override val needsCountry = false
    private var chapter = 0

    private class Chapter(val title: String, val icon: kami.libs.ui.style.Icon, val page: String?, val paragraphs: List<String>)

    private val chapters = listOf(
        Chapter("Countries", Icons.FLAG, "welcome", listOf(
            "A country owns land, collects plot tax and protects what its citizens build. Anyone can found one for free on the chunk they stand in.",
            "The president leads, the chancellor is second in command and takes over when the president is gone for too long. Officers handle citizens and jobs."
        )),
        Chapter("Land and upkeep", Icons.AREA, "map", listOf(
            "Every chunk has a type: mining, farming, residential and more. The type decides the daily price and the protection rules.",
            "The first chunks are free. Every other chunk costs upkeep every day. Claims must touch your existing land."
        )),
        Chapter("Treasury and runway", Icons.TREASURY, "budget", listOf(
            "Upkeep and wages are paid from the treasury. Deposit coins, and earn plot tax from citizens renting plots.",
            "Runway is how many days the treasury lasts at today's rates. If a bill can't be paid, the newest chunks go into debt. After a few unpaid days they are lost."
        )),
        Chapter("Plots", Icons.HOUSE, "plots", listOf(
            "Citizens rent residential chunks as plots. They pay a daily tax and decide who may build there.",
            "If the tax isn't paid, the owner is locked out after a few days and loses the plot later. Plot law sets those days."
        )),
        Chapter("Jobs", Icons.TOOL, "jobs", listOf(
            "Jobs pay members for work in matching land: miners in mining land, farmers in farming land and so on.",
            "Progress counts automatically. When the quota is reached in time, the wage is paid from the treasury."
        )),
        Chapter("Protection and borders", Icons.SHIELD, "protection", listOf(
            "Protection rules decide who may break, place, use or open things in each type of land.",
            "Borders show up as particles when you get close, and always when something is blocked. Press B to change how borders are shown."
        )),
        Chapter("Provinces", Icons.CHAIN, "provinces", listOf(
            "A province pays tribute to an overlord. The overlord may manage its land, capital, taxes, laws and jobs, but never its treasury or members.",
            "A province can't leave on its own. It may ask for independence, and the overlord grants or declines."
        ))
    )

    private val glossary = listOf(
        "Upkeep" to "Daily cost of land, paid from the treasury.",
        "Runway" to "How many days the treasury lasts at today's rates.",
        "Debt" to "Unpaid days of a chunk. At the limit the chunk is lost.",
        "Reserved" to "Lost land that only its old country may reclaim for a few days.",
        "Plot" to "A residential chunk rented by a citizen.",
        "Lapse" to "Unpaid days of a plot.",
        "Tribute" to "What a province pays its overlord every day.",
        "Nomansland" to "Land nobody owns. Nothing can be built there."
    )

    override fun draw(ui: Ui, r: Rect) {
        val (nav, body) = r.columnsFixed(150, -1, gap = 10)
        chapters.forEachIndexed { i, c ->
            val row = Rect(nav.x, nav.y + i * 20, nav.w, 18)
            if (i == chapter) { Draw.fill(ui.g, row, Palette.selected); Draw.fill(ui.g, row.left(2), Palette.brass) }
            else if (ui.hovering(row)) Draw.fill(ui.g, row, Palette.hover)
            Draw.icon(ui.g, c.icon, row.x + 3, row.y + 1)
            Draw.text(ui.g, c.title, row.x + 23, row.y + 5, if (i == chapter) Palette.text else Palette.textSecondary)
            if (ui.pressed(row) != null) chapter = i
        }
        val g = nav.dropTop(chapters.size * 20 + 10)
        if (ui.button(Rect(g.x, g.y, g.w, CONTROL_H), "Restart the tour", Icons.STAR, key = "tour")) { ClientClaims.prefs.tourDone = false; app.startTour() }
        val c = chapters[chapter]
        val card = ui.card(body.top((body.h * 0.6).toInt()), c.title, c.icon)
        var y = card.y
        c.paragraphs.forEach { y += Draw.paragraph(ui.g, it, card.x, y, card.w) + 6 }
        c.page?.let { page -> if (ui.link(card.x, y + 2, "Open ${Route(page).page}", key = "open:$page")) app.navigate(Route(page)) }
        val gb = ui.card(body.dropTop((body.h * 0.6).toInt() + 6), "Glossary", Icons.SCROLL)
        ui.scroll("glossary", gb, glossary.size * 12) { area ->
            glossary.forEachIndexed { i, (term, meaning) ->
                Draw.text(ui.g, term, area.x, area.y + i * 12, TextStyle.HEADING)
                Draw.text(ui.g, Draw.fit(meaning, area.w - 90), area.x + 86, area.y + i * 12, Palette.textSecondary)
            }
        }
    }
}

class SettingsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Settings"
    override val needsCountry = false

    private fun save() = ClientClaims.savePrefs()

    override fun draw(ui: Ui, r: Rect) {
        val p = ClientClaims.prefs
        val (left, right) = r.columns(2, 10)
        val lf = Flow(left, 6)
        val world = ui.card(lf.take(150), "Borders in the world", Icons.SHIELD)
        val wf = Flow(world, 4)
        ui.fieldLabel(wf.take(9), "Show borders")
        ui.segmented(wf.take(CONTROL_H), listOf(
            Option(BorderMode.OFF, "Off", description = "Only when something is blocked."),
            Option(BorderMode.AUTO, "Auto", description = "Near borders, when you aim across one, and when blocked."),
            Option(BorderMode.ALWAYS, "Always", description = "Always within 24 blocks."),
            Option(BorderMode.BUILDER, "Builder", description = "Always, as walls with a grid.")
        ), p.borderMode, key = "border-mode")?.let { p.borderMode = it; save() }
        ui.toggle(wf.take(14), p.borderLines, "Draw borders as thin walls", key = "lines")?.let { p.borderLines = it; save() }
        ui.toggle(wf.take(14), p.blockHints, "Colour blocks you can't change", tip = "Red outline when you can't build, amber across your border.", key = "hints")?.let { p.blockHints = it; save() }
        ui.fieldLabel(wf.take(9), "Particles per second", "${p.borderDensity}")
        ui.slider(wf.take(CONTROL_H), p.borderDensity.toDouble(), 50.0, 800.0, 50.0, key = "density")?.let { p.borderDensity = it.toInt(); save() }
        val hud = ui.card(lf.take(90), "HUD", Icons.EYE)
        val hf = Flow(hud, 4)
        ui.toggle(hf.take(14), p.hud, "Show where you are", key = "hud")?.let { p.hud = it; save() }
        ui.toggle(hf.take(14), p.hudBorderDistance, "Show distance to the nearest border", key = "hud-dist")?.let { p.hudBorderDistance = it; save() }
        ui.toggle(hf.take(14), p.worldToasts, "Warnings while playing", tip = "Debt, offers and other urgent alerts as small notifications.", key = "toasts")?.let { p.worldToasts = it; save() }
        val map = ui.card(lf.remaining(), "Map", Icons.MAP)
        val mf = Flow(map, 4)
        ui.toggle(mf.take(14), p.terrain, "Draw terrain from explored chunks", key = "terrain")?.let { p.terrain = it; TerrainCache.enabled = it; save() }
        ui.toggle(mf.take(14), p.skipClaimConfirm, "Claim without confirming when money lasts 7+ days", key = "skip-confirm")?.let { p.skipClaimConfirm = it; save() }
        if (ui.button(mf.take(CONTROL_H).left(150), "Clear map cache", Icons.REMOVE, key = "wipe")) { TerrainCache.wipe(); app.toast(Severity.SUCCESS, "Map cache cleared") }
        val rf = Flow(right, 6)
        val look = ui.card(rf.take(150), "Look and feel", Icons.BRUSH)
        val lf2 = Flow(look, 4)
        ui.fieldLabel(lf2.take(9), "Colours")
        ui.select(lf2.take(CONTROL_H), kami.libs.ui.style.Palette.Vision.entries.map { Option(it.name, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, description = if (it.name == "NORMAL") "Default colours" else "Adjusted for ${it.name.lowercase()}") }, p.vision, key = "vision")?.let {
            p.vision = it; kami.libs.ui.style.Palette.vision = kami.libs.ui.style.Palette.Vision.valueOf(it); save()
        }
        ui.toggle(lf2.take(14), p.reduceMotion, "Reduce motion", key = "motion")?.let { p.reduceMotion = it; save() }
        ui.fieldLabel(lf2.take(9), "Interface sounds", "${(p.sounds * 100).toInt()}%")
        ui.slider(lf2.take(CONTROL_H), p.sounds.toDouble(), 0.0, 1.0, 0.05, format = { "${(it * 100).toInt()}%" }, key = "sounds")?.let { p.sounds = it.toFloat(); save() }
        ui.fieldLabel(lf2.take(9), "Tooltip delay", "${p.tooltipDelay} ms")
        ui.slider(lf2.take(CONTROL_H), p.tooltipDelay.toDouble(), 0.0, 1500.0, 50.0, format = { "${it.toInt()} ms" }, key = "tip-delay")?.let { p.tooltipDelay = it.toInt(); save() }
        val guide = ui.card(rf.take(92), "Guidance", Icons.HELP)
        val gf = Flow(guide, 4)
        if (ui.button(gf.take(CONTROL_H), "Restart the guided tour", Icons.STAR, key = "tour")) { p.tourDone = false; save(); app.startTour() }
        if (ui.button(gf.take(CONTROL_H), "Show hidden alerts again (${p.dismissed.size})", Icons.BELL, enabled = p.dismissed.isNotEmpty(), disabledReason = "No hidden alerts", key = "undismiss")) { p.dismissed.clear(); save() }
        if (ui.button(gf.take(CONTROL_H), "Show the next steps checklist", Icons.CHECK, enabled = p.hiddenSteps, disabledReason = "It is already shown", key = "steps")) { p.hiddenSteps = false; save() }
        val keys = ui.card(rf.remaining(), "Keys", Icons.GENERIC)
        val kf = Flow(keys, 3)
        listOf("K" to "open this screen", "B" to "border display", "M" to "map", "?" to "help for this page", "Esc" to "close or go back", "Alt+←" to "previous page", "Ctrl+1..7" to "page groups").forEach { (k, v) -> ui.keyHints(keys.x, kf.take(13).y, listOf(k to v)) }
    }
}
