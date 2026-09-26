package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Flags
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.MiniMap
import kami.claims.net.TypeLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
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

private fun Ui.applyBar(r: Rect, changes: Int, summary: String, enabled: Boolean, reason: String?, pending: Boolean, key: String, onApply: () -> Unit, onDiscard: () -> Unit) {
    if (changes == 0) return
    Draw.fill(g, r, Palette.alpha(Palette.brass, 0x28))
    Draw.outline(g, r, Palette.alpha(Palette.brass, 0x90))
    Draw.fill(g, Rect(r.x + 6, r.centerY - 2, 4, 4), Palette.brass)
    Draw.text(g, "${Format.plural(changes, "unsaved change")}", r.x + 14, r.y + 5, TextStyle.HEADING, Palette.brass)
    Draw.text(g, Draw.fit(summary, r.w - 220), r.x + 14, r.y + 15, Palette.textSecondary)
    val apply = Rect(r.right - 90, r.y + 4, 86, 18)
    if (button(apply, "Apply", Icons.SAVE, ButtonStyle.PRIMARY, enabled, reason, pending = pending, key = "$key:apply")) onApply()
    if (button(Rect(apply.x - 76, r.y + 4, 72, 18), "Discard", Icons.UNDO, key = "$key:discard")) onDiscard()
    if (input.takeKey(org.lwjgl.glfw.GLFW.GLFW_KEY_S) { it.ctrl } != null && enabled) onApply()
}

class ProtectionPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Protection"
    override val subtitle get() = "who may do what in each type of land"
    override val help = listOf(
        Callout("protect:grid", "Rules", "Rows are land types, columns are actions. Click a cell to choose who may do it."),
        Callout("protect:explain", "Explanation", "The selected cell in plain words, with an example."),
        Callout("protect:apply", "Apply", "Nothing changes until you press Apply. Ctrl+S works too.")
    )
    private val staged = LinkedHashMap<String, String>()
    private var focusCell: Pair<String, String>? = null

    private fun key(type: String, field: String) = "$type:$field"
    private fun current(t: TypeLine, field: String): String = staged[key(t.name, field)] ?: when (field) {
        "machines" -> t.machines.toString()
        "fire" -> t.fire.toString()
        "fluid" -> t.fluid.toString()
        else -> t.access[listOf("break", "place", "interact", "container").indexOf(field)]
    }
    private fun original(t: TypeLine, field: String): String = when (field) {
        "machines" -> t.machines.toString()
        "fire" -> t.fire.toString()
        "fluid" -> t.fluid.toString()
        else -> t.access[listOf("break", "place", "interact", "container").indexOf(field)]
    }

    override fun leaving(next: Route): Boolean {
        if (staged.isEmpty()) return true
        app.toast(Severity.WARNING, "Unsaved rule changes", "Apply or discard them first.")
        return false
    }

    override fun actions(ui: Ui, r: Rect) {
        val presets = listOf(Option("private", "Private", Icons.LOCK, "Only officers change anything, citizens may use."), Option("workers", "Workers", Icons.TOOL, "Job workers build, citizens use."), Option("public", "Open market", Icons.SCALES, "Everyone may use and open, citizens build."), Option("default", "Server default", Icons.UNDO, "The rules from the server config."))
        Draw.textRight(ui.g, "Preset for selected row", r.right - 134, r.y + 6, Palette.textMuted)
        ui.select(Rect(r.right - 130, r.y, 130, 20), presets, null, "Apply a preset…", can("rules"), lock("rules"), key = "preset")?.let { preset -> applyPreset(preset) }
    }

    private fun applyPreset(preset: String) {
        val type = focusCell?.first ?: return app.toast(Severity.INFO, "Select a row first", "Click any cell of the land type you want to change.")
        val t = snap.types.firstOrNull { it.name == type } ?: return
        val values = when (preset) {
            "private" -> listOf("officer", "officer", "citizen", "citizen")
            "workers" -> listOf("job", "worker", "citizen", "worker")
            "public" -> listOf("citizen", "citizen", "any", "any")
            else -> t.defaults
        }
        listOf("break", "place", "interact", "container").forEachIndexed { i, f -> stage(t, f, values[i]) }
    }

    private fun stage(t: TypeLine, field: String, value: String) {
        if (value == original(t, field)) staged.remove(key(t.name, field)) else staged[key(t.name, field)] = value
    }

    override fun draw(ui: Ui, r: Rect) {
        val editable = can("rules")
        val bottom = r.bottom(70 + if (staged.isEmpty()) 0 else 30)
        val grid = r.dropBottom(bottom.h, 6)
        ui.anchor("protect:grid", grid)
        val actions = listOf("break", "place", "interact", "container")
        val flags = listOf("machines", "fire", "fluid")
        val labelW = (grid.w * 0.24).toInt().coerceIn(96, 150)
        val actionW = (grid.w - labelW - flags.size * 34) / actions.size
        val header = grid.top(22)
        actions.forEachIndexed { i, a ->
            val look = Vocabulary.actions[i]
            val c = Rect(header.x + labelW + i * actionW, header.y, actionW, 22)
            Draw.icon(ui.g, look.icon, c.x + 2, c.y + 2)
            Draw.text(ui.g, look.label.uppercase(), c.x + 20, c.y + 7, Palette.textMuted)
            ui.tooltip("head:$a", c, Tip.text(look.description, look.label))
        }
        flags.forEachIndexed { i, f ->
            val look = Vocabulary.flags[i].second
            val c = Rect(header.x + labelW + actions.size * actionW + i * 34, header.y, 34, 22)
            Draw.icon(ui.g, look.icon, c.centerX - 8, c.y + 2)
            ui.tooltip("head:$f", c, Tip.text(look.description, look.label))
        }
        Draw.hline(ui.g, grid.x, header.bottom, grid.w, Palette.border)
        ui.scroll("rules", grid.dropTop(24), snap.types.size * 22) { area ->
            snap.types.forEachIndexed { row, t ->
                val y = area.y + row * 22
                val line = Rect(area.x, y, area.w, 21)
                if (focusCell?.first == t.name) Draw.fill(ui.g, line, Palette.alpha(Palette.brass, 0x14))
                else if (row % 2 == 1) Draw.fill(ui.g, line, Palette.alpha(0xFFFFFF, 0x05))
                val look = Vocabulary.type(t.name)
                Draw.icon(ui.g, look.icon, line.x, y + 2)
                Draw.text(ui.g, Draw.fit(look.label, labelW - 22), line.x + 19, y + 7, look.color)
                ui.tooltip("type:${t.name}", Rect(line.x, y, labelW, 21), Tip(look.label, listOf(look.description to Palette.textSecondary, "${t.price} ◎ per ${if (t.period > 1) "${t.period} days" else "day"}" to Palette.textMuted)))
                actions.forEachIndexed { i, a ->
                    val cell = Rect(line.x + labelW + i * actionW + 1, y + 1, actionW - 3, 19)
                    val value = current(t, a)
                    val changed = staged.containsKey(key(t.name, a))
                    ui.select(cell, Vocabulary.accessOptions(), value, enabled = editable, disabledReason = lock("rules"), key = "cell:${t.name}:$a", menuWidth = 220)?.let {
                        stage(t, a, it)
                        focusCell = t.name to a
                    }
                    if (ui.hovering(cell) && ui.input.presses.isNotEmpty()) focusCell = t.name to a
                    if (changed) Draw.fill(ui.g, Rect(cell.right - 3, cell.y + 1, 2, 2), Palette.brass)
                    if (focusCell == t.name to a) Draw.outline(ui.g, cell.grow(1), Palette.brass)
                }
                flags.forEachIndexed { i, f ->
                    val cell = Rect(line.x + labelW + actions.size * actionW + i * 34 + 6, y + 4, 24, 12)
                    val on = current(t, f) == "true"
                    ui.toggle(Rect(cell.x, y + 1, 26, 19), on, "", editable, lock("rules"), "${Vocabulary.flags[i].second.label}: ${if (on) "on" else "off"}", key = "flag:${t.name}:$f")?.let {
                        stage(t, f, it.toString())
                        focusCell = t.name to f
                    }
                    if (staged.containsKey(key(t.name, f))) Draw.fill(ui.g, Rect(cell.right + 1, y + 2, 2, 2), Palette.brass)
                }
            }
        }
        explanation(ui, bottom.top(64))
        val bar = bottom.dropTop(70)
        ui.anchor("protect:apply", bar)
        ui.applyBar(bar, staged.size, staged.entries.take(3).joinToString(" · ") { (k, v) -> "${Vocabulary.type(k.substringBefore(':')).label} ${k.substringAfter(':')} → ${Vocabulary.access.firstOrNull { it.first == v }?.second?.label ?: v}" },
            editable, lock("rules"), pending("rules"), "rules", {
                act("rules", staged.entries.joinToString(";") { (k, v) -> "$k:$v" }, key = "rules")
                staged.clear()
            }, { staged.clear() })
    }

    private fun explanation(ui: Ui, r: Rect) {
        ui.anchor("protect:explain", r)
        val body = ui.card(r, "What this means", Icons.INFO)
        val (type, field) = focusCell ?: run {
            Draw.paragraph(ui.g, "Click a cell to see in plain words who may do it. Allies include citizens of provinces in the same family.", body.x, body.y, body.w, Palette.textMuted)
            return
        }
        val t = snap.types.firstOrNull { it.name == type } ?: return
        val look = Vocabulary.type(type)
        val value = current(t, field)
        val sentence = if (field in listOf("machines", "fire", "fluid")) {
            val flag = Vocabulary.flags.first { it.first == field }.second
            "${flag.label} in ${look.label} land: ${if (value == "true") "allowed" else "blocked"}. ${flag.description}"
        } else {
            val access = Vocabulary.access(value)
            val action = Vocabulary.actions[listOf("break", "place", "interact", "container").indexOf(field)]
            val example = when (value) {
                "job" -> " Example: in ${look.label} land only members with the ${Vocabulary.type(type).label.lowercase()} job${if (t.job.isNotEmpty()) " (${t.job})" else ""} may do it."
                "none" -> " Not even the president."
                "any" -> " Even visitors from other countries."
                else -> ""
            }
            "${action.label} (${action.description.lowercase()}) in ${look.label} land: ${access.label}. ${access.description}$example"
        }
        Draw.paragraph(ui.g, sentence, body.x, body.y, body.w)
    }
}

class PlotLawPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Plot law"
    override val subtitle get() = "tax and what happens when it isn't paid"
    override val help = listOf(Callout("plotlaw:tax", "Plot tax", "What every plot owner pays per day, into the treasury."), Callout("plotlaw:chain", "Unpaid tax", "How many days until an owner is locked out, and when the plot becomes free again."))
    private var tax = NumberState(0)
    private var shutdown = NumberState(0)
    private var release = NumberState(0)
    private var synced = false

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val dirty = tax.value != info.tax.toLong() || shutdown.value != info.shutdown.toLong() || release.value != info.release.toLong()
        if (!dirty || !synced) { tax.sync(info.tax.toLong()); shutdown.sync(info.shutdown.toLong()); release.sync(info.release.toLong()); synced = true }
        val editable = can("tax")
        val (left, right) = r.dropBottom(34).columns(2, 10)
        val tb = ui.card(left.top(130), "Plot tax", Icons.TAX)
        ui.anchor("plotlaw:tax", left.top(130))
        val f = Flow(tb, 4)
        ui.fieldLabel(f.take(9), "Per plot per day")
        ui.numberField(f.take(CONTROL_H).left(160), tax, 0, 10_000, unit = "◎", enabled = editable, key = "tax")
        val plots = info.claimList.count { it.owner.isNotEmpty() }
        ui.property(f.take(11), "Rented plots", "$plots")
        ui.property(f.take(11), "Income per day", "${info.income} → ${tax.value * plots} ◎", Palette.success)
        f.take(ui.callout(f.rest, Severity.INFO, "A plot can have its own tax, set under Plots › All plots."))
        val cb = ui.card(right.top(130), "When tax isn't paid", Icons.CLOCK)
        ui.anchor("plotlaw:chain", right.top(130))
        val g2 = Flow(cb, 4)
        val cols = g2.take(30).columns(2, 8)
        ui.fieldLabel(cols[0].top(9), "Locked after (days)")
        ui.numberField(Rect(cols[0].x, cols[0].y + 10, cols[0].w, 18), shutdown, 0, 60, enabled = editable, key = "shutdown")
        ui.fieldLabel(cols[1].top(9), "Then lost after (days)")
        ui.numberField(Rect(cols[1].x, cols[1].y + 10, cols[1].w, 18), release, 0, 60, enabled = editable, key = "release")
        g2.skip(4)
        ui.timeline(g2.take(34), listOf(
            Step("Unpaid", "day 1", StepState.CURRENT),
            Step("Locked out", "day ${shutdown.value}", StepState.PENDING),
            Step("Plot free", "day ${shutdown.value + release.value}", StepState.DANGER)
        ))
        Draw.paragraph(ui.g, "An owner who doesn't pay can't use the plot after ${shutdown.value} days and loses it ${release.value} days later.", g2.rest.x, g2.rest.y, g2.rest.w, Palette.textSecondary)
        if (shutdown.value == 0L) g2.take(ui.callout(Rect(g2.rest.x, g2.rest.y + 24, g2.rest.w, 0), Severity.WARNING, "0 days locks owners out the first day they miss a payment."))
        val changes = listOf(tax.value != info.tax.toLong(), shutdown.value != info.shutdown.toLong(), release.value != info.release.toLong()).count { it }
        ui.applyBar(r.bottom(28), changes, "tax ${tax.value} ◎ · locked after ${shutdown.value}d · lost after ${shutdown.value + release.value}d", editable, lock("tax"), pending("plot_law"), "plotlaw", {
            act("plot_law", tax.value.toString(), shutdown.value.toString(), release.value.toString(), key = "plot_law")
        }, { synced = false; tax.commit(info.tax.toLong()); shutdown.commit(info.shutdown.toLong()); release.commit(info.release.toLong()) })
    }
}

class IdentityPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Identity"
    override val subtitle get() = "colour and flag"
    override val help = listOf(Callout("identity:preview", "Preview", "Your flag as others see it on maps, in lists and on the HUD."))
    private val swatches = intArrayOf(0x3FA34D, 0x3B82C4, 0xC4553B, 0x9B59B6, 0xD9A441, 0x2AA6A6, 0xC45B9A, 0x8A8F3B, 0xE0E0E0, 0x555566, 0x7A4A2A, 0x1F4E79, 0xB22222, 0x228B22, 0xFF8C00, 0x4B0082)
    private var color = -1
    private var pattern = -1
    private var emblem = -1
    private var secondary = -1

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        if (color < 0) { color = info.color; pattern = info.flag.pattern; emblem = info.flag.emblem; secondary = info.flag.secondary }
        val editable = can("rules")
        val (left, right) = r.columns(listOf(1f, 1.2f), 10)
        val pb = ui.card(left, "Preview", Icons.FLAG)
        ui.anchor("identity:preview", left)
        Flags.draw(ui.g, Rect(pb.x + (pb.w - 96) / 2, pb.y + 4, 96, 64), color, pattern, emblem, secondary)
        Draw.text(ui.g, info.name, pb.x + (pb.w - Draw.width(info.name, TextStyle.TITLE)) / 2, pb.y + 76, TextStyle.TITLE)
        val sizes = listOf(36 to 26, 18 to 13, 12 to 9)
        var sx = pb.x + (pb.w - sizes.sumOf { it.first + 8 }) / 2
        sizes.forEach { (w, h) -> Flags.draw(ui.g, Rect(sx, pb.y + 92, w, h), color, pattern, emblem, secondary); sx += w + 8 }
        MiniMap.draw(ui, Rect(pb.x, pb.y + 126, pb.w, pb.bottom - pb.y - 126), snap.px, snap.pz, 6, "identity")
        val f = Flow(right, 6)
        val cb = ui.card(f.take(66), "Main colour", Icons.BRUSH)
        cb.grid(8, 18, swatches.size, 3).forEachIndexed { i, cell -> swatch(ui, cell, swatches[i], color == swatches[i], editable) { color = swatches[i] } }
        val sb = ui.card(f.take(66), "Second colour", Icons.BRUSH)
        sb.grid(8, 18, swatches.size, 3).forEachIndexed { i, cell -> swatch(ui, cell, swatches[i], secondary == swatches[i], editable) { secondary = swatches[i] } }
        val patternCard = ui.card(f.take(78), "Pattern", Icons.LAYERS)
        patternCard.grid(6, 24, Flags.patterns.size, 3).forEachIndexed { i, cell ->
            val box = cell.centered(30, 22)
            Flags.draw(ui.g, box, color, i, 0, secondary)
            pick(ui, box, pattern == i, editable, Flags.patterns[i]) { pattern = i }
        }
        val emblemCard = ui.card(f.take(64), "Emblem", Icons.STAR)
        emblemCard.grid(8, 18, Flags.emblems.size, 3).forEachIndexed { i, cell ->
            val box = cell.centered(18, 18)
            Draw.fill(ui.g, box, Palette.sunken)
            Flags.emblems[i]?.let { Draw.icon(ui.g, it, box.x + 1, box.y + 1) } ?: Draw.text(ui.g, "—", box.x + 6, box.y + 5, Palette.textMuted)
            pick(ui, box, emblem == i, editable, if (i == 0) "No emblem" else "Emblem ${i}") { emblem = i }
        }
        val dirty = color != info.color || pattern != info.flag.pattern || emblem != info.flag.emblem || secondary != info.flag.secondary
        if (dirty) {
            val bar = f.take(22)
            if (ui.button(bar.right(100), "Save identity", Icons.SAVE, ButtonStyle.PRIMARY, editable, lock("rules"), pending = pending("flag"), key = "save-flag")) {
                act("flag", "%06x".format(color and 0xFFFFFF), pattern.toString(), emblem.toString(), "%06x".format(secondary and 0xFFFFFF), key = "flag")
            }
            if (ui.button(Rect(bar.right - 176, bar.y, 72, CONTROL_H), "Discard", Icons.UNDO, key = "discard-flag")) color = -1
        }
    }

    private fun swatch(ui: Ui, r: Rect, value: Int, chosen: Boolean, enabled: Boolean, onPick: () -> Unit) {
        Draw.fill(ui.g, r, Palette.opaque(value))
        pick(ui, r, chosen, enabled, "#%06X".format(value), onPick)
    }

    private fun pick(ui: Ui, r: Rect, chosen: Boolean, enabled: Boolean, tip: String, onPick: () -> Unit) {
        if (chosen) { Draw.outline(ui.g, r.grow(1), Palette.text); Draw.outline(ui.g, r.grow(2), Palette.brass) }
        if (ui.hovering(r) && enabled) { Draw.outline(ui.g, r, Palette.alpha(0xFFFFFF, 0x90)); ui.cursor = Cursor.HAND }
        ui.tooltip("pick:$tip", r, if (enabled) tip else lock("rules"))
        if (enabled && ui.pressed(r) != null) onPick()
    }
}
