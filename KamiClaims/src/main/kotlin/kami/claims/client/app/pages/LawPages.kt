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
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*
import org.lwjgl.glfw.GLFW

private fun Ui.applyBar(r: Rect, changes: Int, summary: String, enabled: Boolean, reason: String?, pending: Boolean, key: String, onApply: () -> Unit, onDiscard: () -> Unit) {
    if (changes == 0) return
    Draw.fill(g, r, Palette.alpha(Palette.brass, 0x28))
    Draw.outline(g, r, Palette.alpha(Palette.brass, 0x90))
    Draw.fill(g, Rect(r.x + 6, r.centerY - 2, 4, 4), Palette.brass)
    Draw.text(g, trn("kami_claims.unit.unsaved", changes), r.x + 14, r.y + 5, TextStyle.HEADING, Palette.brass)
    Draw.text(g, Draw.fit(summary, r.w - 220), r.x + 14, r.y + 15, Palette.textSecondary)
    val applyLabel = tr("kami_libs.common.apply")
    val applyW = buttonWidth(applyLabel, Icons.SAVE)
    val apply = Rect(r.right - applyW - 4, r.y + 4, applyW, CONTROL_H)
    if (button(apply, applyLabel, Icons.SAVE, ButtonStyle.PRIMARY, enabled, reason, pending = pending, key = "$key:apply")) onApply()
    val discard = tr("kami_libs.common.discard")
    val discardW = buttonWidth(discard, Icons.UNDO)
    if (button(Rect(apply.x - discardW - 4, r.y + 4, discardW, CONTROL_H), discard, Icons.UNDO, key = "$key:discard")) onDiscard()
    if (input.takeKey(GLFW.GLFW_KEY_S) { it.ctrl } != null && enabled) onApply()
}

class ProtectionPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.protection")
    override val subtitle get() = tr("kami_claims.law.protection.subtitle")
    override val help get() = listOf(
        Callout("protect:grid", tr("kami_claims.law.help.grid"), tr("kami_claims.law.help.grid.desc")),
        Callout("protect:explain", tr("kami_claims.law.explain.title"), tr("kami_claims.law.help.explain.desc")),
        Callout("protect:apply", tr("kami_libs.common.apply"), tr("kami_claims.law.help.apply.desc"))
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
        app.toast(Severity.WARNING, tr("kami_claims.law.unsaved"), tr("kami_claims.law.unsaved.desc"))
        return false
    }

    override fun actionsWidth() = Draw.width(tr("kami_claims.law.preset.label")) + 134

    override fun actions(ui: Ui, r: Rect) {
        val presets = listOf("private" to Icons.LOCK, "workers" to Icons.TOOL, "public" to Icons.SCALES, "default" to Icons.UNDO).map { (id, icon) -> Option(id, tr("kami_claims.law.preset.$id"), icon, tr("kami_claims.law.preset.$id.desc")) }
        Draw.textRight(ui.g, tr("kami_claims.law.preset.label"), r.right - 134, r.y + 4, Palette.textMuted)
        ui.select(Rect(r.right - 130, r.y, 130, r.h), presets, null, tr("kami_claims.law.preset.placeholder"), can("rules"), lock("rules"), key = "preset")?.let { preset -> applyPreset(preset) }
    }

    private fun applyPreset(preset: String) {
        val type = focusCell?.first ?: return app.toast(Severity.INFO, tr("kami_claims.law.select_row"), tr("kami_claims.law.select_row.desc"))
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
        val bottom = r.bottom(EXPLAIN_H + if (staged.isEmpty()) 0 else 28)
        val grid = r.dropBottom(bottom.h, 6)
        ui.anchor("protect:grid", grid)
        val actions = listOf("break", "place", "interact", "container")
        val flags = listOf("machines", "fire", "fluid")
        val labelW = (grid.w * 0.24).toInt().coerceIn(96, 150)
        val actionW = (grid.w - labelW - flags.size * 34) / actions.size
        val header = grid.top(HEAD_H)
        actions.forEachIndexed { i, a ->
            val look = Vocabulary.actions[i]
            val c = Rect(header.x + labelW + i * actionW, header.y, actionW, HEAD_H)
            val x = c.x + 2 + Draw.leadIcon(ui.g, look.icon, c.x + 2, c.centerY) + 2
            Draw.text(ui.g, Draw.fit(look.label.uppercase(Format.locale), c.right - x), x, c.y + 5, Palette.textMuted)
            ui.tooltip("head:$a", c, Tip.text(look.description, look.label))
        }
        flags.forEachIndexed { i, f ->
            val look = Vocabulary.flags[i].second
            val c = Rect(header.x + labelW + actions.size * actionW + i * 34, header.y, 34, HEAD_H)
            Draw.icon(ui.g, look.icon, c)
            ui.tooltip("head:$f", c, Tip.text(look.description, look.label))
        }
        Draw.hline(ui.g, grid.x, header.bottom, grid.w, Palette.border)
        ui.scroll("rules", grid.dropTop(HEAD_H + 2), snap.types.size * RULE_H) { area ->
            snap.types.forEachIndexed { row, t ->
                val y = area.y + row * RULE_H
                val line = Rect(area.x, y, area.w, RULE_H - 1)
                if (focusCell?.first == t.name) Draw.fill(ui.g, line, Palette.alpha(Palette.brass, 0x14))
                else if (row % 2 == 1) Draw.fill(ui.g, line, Palette.alpha(0xFFFFFF, 0x05))
                val look = Vocabulary.type(t.name)
                val labelX = line.x + 2 + Draw.leadIcon(ui.g, look.icon, line.x + 2, line.centerY) + 2
                Draw.text(ui.g, Draw.fit(look.label, line.x + labelW - labelX - 4), labelX, y + 5, look.color)
                ui.tooltip("type:${t.name}", Rect(line.x, y, labelW, line.h), Tip(look.label, listOf(look.description to Palette.textSecondary, Vocabulary.rate(t.price, t.period) to Palette.textMuted)))
                actions.forEachIndexed { i, a ->
                    val cell = Rect(line.x + labelW + i * actionW + 1, y + 2, actionW - 3, SMALL_H)
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
                    val cell = Rect(line.x + labelW + actions.size * actionW + i * 34 + 5, y + 2, 26, SMALL_H)
                    val on = current(t, f) == "true"
                    ui.toggle(cell, on, "", editable, lock("rules"), tr("kami_claims.law.flag.state", Vocabulary.flags[i].second.label, tr(if (on) "kami_claims.law.on" else "kami_claims.law.off")), key = "flag:${t.name}:$f")?.let {
                        stage(t, f, it.toString())
                        focusCell = t.name to f
                    }
                    if (staged.containsKey(key(t.name, f))) Draw.fill(ui.g, Rect(cell.right + 1, y + 2, 2, 2), Palette.brass)
                }
            }
        }
        explanation(ui, bottom.top(EXPLAIN_H - 6))
        val bar = bottom.dropTop(EXPLAIN_H - 2)
        ui.anchor("protect:apply", bar)
        ui.applyBar(bar, staged.size, staged.entries.take(3).joinToString(" · ") { (k, v) -> "${Vocabulary.type(k.substringBefore(':')).label} ${fieldLabel(k.substringAfter(':'))} → ${valueLabel(v)}" },
            editable, lock("rules"), pending("rules"), "rules", {
                act("rules", staged.entries.joinToString(";") { (k, v) -> "$k:$v" }, key = "rules")
                staged.clear()
            }, { staged.clear() })
    }

    private fun fieldLabel(field: String) = Vocabulary.flags.firstOrNull { it.first == field }?.second?.label ?: tr("kami_claims.action.$field")

    private fun valueLabel(value: String) = when (value) {
        "true" -> tr("kami_claims.law.allowed")
        "false" -> tr("kami_claims.law.blocked")
        else -> Vocabulary.access(value).label
    }

    private fun explanation(ui: Ui, r: Rect) {
        ui.anchor("protect:explain", r)
        val body = ui.card(r, tr("kami_claims.law.explain.title"), Icons.INFO)
        val (type, field) = focusCell ?: run {
            Draw.paragraph(ui.g, tr("kami_claims.law.explain.empty"), body.x, body.y, body.w, Palette.textMuted)
            return
        }
        val t = snap.types.firstOrNull { it.name == type } ?: return
        val value = current(t, field)
        val flag = Vocabulary.flags.firstOrNull { it.first == field }?.second
        val detail = flag?.description ?: Vocabulary.access(value).description
        val example = when {
            flag != null -> null
            value == "job" && t.job.isNotEmpty() -> tr("kami_claims.law.example.job", Vocabulary.job(t.job))
            value == "none" -> tr("kami_claims.law.example.none")
            value == "any" -> tr("kami_claims.law.example.any")
            else -> null
        }
        val sentence = tr("kami_claims.law.explain", fieldLabel(field), Vocabulary.type(type).label, valueLabel(value), detail)
        Draw.paragraph(ui.g, listOfNotNull(sentence, example).joinToString(" "), body.x, body.y, body.w)
    }
}

class PlotLawPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.plotlaw")
    override val subtitle get() = tr("kami_claims.law.plot.subtitle")
    override val help get() = listOf(
        Callout("plotlaw:tax", tr("kami_claims.ledger.plot_tax"), tr("kami_claims.law.help.tax.desc")),
        Callout("plotlaw:chain", tr("kami_claims.law.chain"), tr("kami_claims.law.help.chain.desc"))
    )
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
        val tb = ui.card(left.top(96), tr("kami_claims.ledger.plot_tax"), Icons.TAX, help = tr("kami_claims.law.tax.override"))
        ui.anchor("plotlaw:tax", left.top(96))
        val f = Flow(tb, 4)
        ui.fieldLabel(f.take(9), tr("kami_claims.law.tax.field"))
        ui.numberField(f.take(CONTROL_H).left(160), tax, 0, 10_000, unit = "◎", enabled = editable, key = "tax")
        val plots = info.claimList.count { it.owner.isNotEmpty() }
        ui.property(f.take(11), tr("kami_claims.stats.rented_plots"), Format.number(plots))
        ui.property(f.take(11), tr("kami_claims.law.tax.income"), "${Format.number(info.income)} → ${Format.money(tax.value * plots)}", Palette.success)
        val chainHelp = tr("kami_claims.law.chain.desc", trn("kami_claims.unit.day", shutdown.value), trn("kami_claims.unit.day", release.value))
        val cb = ui.card(right.top(130), tr("kami_claims.law.chain"), Icons.CLOCK, help = chainHelp)
        ui.anchor("plotlaw:chain", right.top(130))
        val g2 = Flow(cb, 4)
        val cols = g2.take(30).columns(2, 8)
        ui.fieldLabel(cols[0].top(9), tr("kami_claims.law.chain.lock"))
        ui.numberField(Rect(cols[0].x, cols[0].y + 10, cols[0].w, CONTROL_H), shutdown, 0, 60, enabled = editable, key = "shutdown")
        ui.fieldLabel(cols[1].top(9), tr("kami_claims.law.chain.lose"))
        ui.numberField(Rect(cols[1].x, cols[1].y + 10, cols[1].w, CONTROL_H), release, 0, 60, enabled = editable, key = "release")
        g2.skip(4)
        ui.timeline(g2.take(34), listOf(
            Step(tr("kami_claims.plots.unpaid"), tr("kami_claims.chart.day", 1), StepState.CURRENT),
            Step(tr("kami_claims.plots.step.locked"), tr("kami_claims.chart.day", shutdown.value), StepState.PENDING),
            Step(tr("kami_claims.law.chain.free"), tr("kami_claims.chart.day", shutdown.value + release.value), StepState.DANGER)
        ))
        if (shutdown.value == 0L) g2.take(ui.callout(g2.rest, Severity.WARNING, tr("kami_claims.law.chain.zero")))
        val changes = listOf(tax.value != info.tax.toLong(), shutdown.value != info.shutdown.toLong(), release.value != info.release.toLong()).count { it }
        ui.applyBar(r.bottom(28), changes, tr("kami_claims.law.summary", Format.money(tax.value), Format.days(shutdown.value), Format.days(shutdown.value + release.value)), editable, lock("tax"), pending("plot_law"), "plotlaw", {
            act("plot_law", tax.value.toString(), shutdown.value.toString(), release.value.toString(), key = "plot_law")
        }, { synced = false; tax.commit(info.tax.toLong()); shutdown.commit(info.shutdown.toLong()); release.commit(info.release.toLong()) })
    }
}

class IdentityPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.identity")
    override val subtitle get() = tr("kami_claims.identity.subtitle")
    override val help get() = listOf(Callout("identity:preview", tr("kami_claims.identity.preview"), tr("kami_claims.identity.help.preview.desc")))
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
        val pb = ui.card(left, tr("kami_claims.identity.preview"), Icons.FLAG)
        ui.anchor("identity:preview", left)
        Flags.draw(ui.g, Rect(pb.x + (pb.w - 96) / 2, pb.y + 4, 96, 64), color, pattern, emblem, secondary)
        val name = Draw.fit(info.name, pb.w - 8, TextStyle.TITLE)
        Draw.text(ui.g, name, pb.x + (pb.w - Draw.width(name, TextStyle.TITLE)) / 2, pb.y + 76, TextStyle.TITLE)
        val sizes = listOf(36 to 26, 18 to 13, 12 to 9)
        var sx = pb.x + (pb.w - sizes.sumOf { it.first + 8 }) / 2
        sizes.forEach { (w, h) -> Flags.draw(ui.g, Rect(sx, pb.y + 92, w, h), color, pattern, emblem, secondary); sx += w + 8 }
        MiniMap.draw(ui, Rect(pb.x, pb.y + 126, pb.w, pb.bottom - pb.y - 126), snap.px, snap.pz, 6, "identity")
        val f = Flow(right, 6)
        val cb = ui.card(f.take(66), tr("kami_claims.identity.primary"), Icons.BRUSH)
        cb.grid(8, 18, swatches.size, 3).forEachIndexed { i, cell -> swatch(ui, cell, swatches[i], color == swatches[i], editable) { color = swatches[i] } }
        val sb = ui.card(f.take(66), tr("kami_claims.identity.secondary"), Icons.BRUSH)
        sb.grid(8, 18, swatches.size, 3).forEachIndexed { i, cell -> swatch(ui, cell, swatches[i], secondary == swatches[i], editable) { secondary = swatches[i] } }
        val patternCard = ui.card(f.take(78), tr("kami_claims.identity.pattern"), Icons.LAYERS)
        patternCard.grid(6, 24, Flags.patterns.size, 3).forEachIndexed { i, cell ->
            val box = cell.centered(30, 22)
            Flags.draw(ui.g, box, color, i, 0, secondary)
            pick(ui, box, pattern == i, editable, tr("kami_claims.flag_pattern.${Flags.patterns[i]}")) { pattern = i }
        }
        val emblemCard = ui.card(f.take(64), tr("kami_claims.identity.emblem"), Icons.STAR)
        emblemCard.grid(8, 18, Flags.emblems.size, 3).forEachIndexed { i, cell ->
            val box = cell.centered(18, 18)
            Draw.fill(ui.g, box, Palette.sunken)
            Flags.emblems[i]?.let { Draw.icon(ui.g, it, box.x + 1, box.y + 1) } ?: Draw.text(ui.g, "-", box.x + 6, box.y + 5, Palette.textMuted)
            pick(ui, box, emblem == i, editable, if (i == 0) tr("kami_claims.identity.emblem.none") else tr("kami_claims.identity.emblem.number", i)) { emblem = i }
        }
        val dirty = color != info.color || pattern != info.flag.pattern || emblem != info.flag.emblem || secondary != info.flag.secondary
        if (dirty) {
            val bar = f.take(CONTROL_H)
            val saveLabel = tr("kami_claims.identity.save")
            val discard = tr("kami_libs.common.discard")
            val save = bar.right(buttonWidth(saveLabel, Icons.SAVE))
            if (ui.button(save, saveLabel, Icons.SAVE, ButtonStyle.PRIMARY, editable, lock("rules"), pending = pending("flag"), key = "save-flag")) {
                act("flag", "%06x".format(color and 0xFFFFFF), pattern.toString(), emblem.toString(), "%06x".format(secondary and 0xFFFFFF), key = "flag")
            }
            if (ui.button(Rect(save.x - buttonWidth(discard, Icons.UNDO) - 4, bar.y, buttonWidth(discard, Icons.UNDO), CONTROL_H), discard, Icons.UNDO, key = "discard-flag")) color = -1
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

private const val HEAD_H = 18
private const val RULE_H = 20
private const val EXPLAIN_H = 60
