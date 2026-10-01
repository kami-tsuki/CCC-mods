package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.OfferRows
import kami.claims.client.app.tokenOffer
import kami.claims.client.app.Dialogs
import kami.claims.client.app.ClientLocks
import kami.claims.client.store.ClientResearch
import kami.claims.net.Info
import kami.claims.research.Capacity
import kami.libs.ui.core.Memo
import kami.libs.ui.style.Icon
import kami.claims.client.store.ClaimsStore
import kami.claims.research.Tokens
import kami.claims.Rank
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.widget.Flags
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.MiniMap
import kami.claims.net.TypeLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
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
    val row = Row(Rect(r.x, r.y + 4, r.w - 4, CONTROL_H))
    if (edgeButton(row, tr("kami_libs.common.apply"), Icons.SAVE, ButtonStyle.PRIMARY, enabled, reason, pending = pending, key = "$key:apply")) onApply()
    if (edgeButton(row, tr("kami_libs.common.discard"), Icons.UNDO, key = "$key:discard")) onDiscard()
    if (input.takeKey(GLFW.GLFW_KEY_S) { it.ctrl } != null && enabled) onApply()
}

private val ACTIONS = listOf("break", "place", "interact", "container")

class ProtectionPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.protection")
    override val subtitle get() = tr("kami_claims.law.protection.subtitle")
    override val help get() = listOf(
        Callout("protect:grid", tr("kami_claims.common.rules"), tr("kami_claims.law.help.grid.desc")),
        Callout("protect:explain", tr("kami_claims.law.explain.title"), tr("kami_claims.law.help.explain.desc")),
        Callout("protect:apply", tr("kami_libs.common.apply"), tr("kami_claims.law.help.apply.desc"))
    )
    private val staged = LinkedHashMap<String, String>()
    private var focusCell: Pair<String, String>? = null

    private fun key(type: String, field: String) = "$type:$field"
    private fun raw(t: TypeLine, field: String): String = when (field) {
        "machines" -> t.machines.toString()
        "fire" -> t.fire.toString()
        else -> t.access[ACTIONS.indexOf(field)]
    }
    private fun current(t: TypeLine, field: String) = staged[key(t.name, field)] ?: raw(t, field)
    private fun original(t: TypeLine, field: String) = raw(t, field)

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
        ACTIONS.forEachIndexed { i, f -> stage(t, f, values[i]) }
    }

    private fun stage(t: TypeLine, field: String, value: String) {
        if (value == original(t, field)) staged.remove(key(t.name, field)) else staged[key(t.name, field)] = value
    }

    override fun draw(ui: Ui, r: Rect) {
        val editable = can("rules")
        val bottom = r.bottom(EXPLAIN_H + if (staged.isEmpty()) 0 else 28)
        val grid = r.dropBottom(bottom.h, 6)
        ui.anchor("protect:grid", grid)
        val actions = ACTIONS
        val flags = listOf("machines", "fire")
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
                    ui.toggle(cell, on, "", editable, lock("rules"), tr("kami_libs.format.pair", Vocabulary.flags[i].second.label, tr(if (on) "kami_claims.law.on" else "kami_libs.common.off")), key = "flag:${t.name}:$f")?.let {
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
    override val subtitle get() = tr("kami_claims.law.housing.subtitle")
    override val help get() = listOf(
        Callout("plotlaw:offer", tr("kami_claims.law.offer.title"), tr("kami_claims.law.help.offer.desc")),
        Callout("plotlaw:limits", tr("kami_claims.law.limits.title"), tr("kami_claims.law.help.limits.desc")),
        Callout("plotlaw:chain", tr("kami_claims.common.rent_debt"), tr("kami_claims.law.help.chain.desc"))
    )
    private val cats = OfferRows.CATS
    private val ranks = listOf("citizen", "officer", "chancellor", "president")
    private val guests = listOf("parent", "province", "allied", "random")
    private val features = mapOf("parent" to "plots:family", "province" to "plots:family", "allied" to "plots:allies", "random" to "plots:public")
    private val rents = cats.associateWith { NumberState(0) }
    private val open = HashMap<String, Boolean>()
    private val rankLimits = ranks.associateWith { NumberState(0) }
    private val guestLimits = guests.associateWith { NumberState(0) }
    private val debtLimit = NumberState(0)
    private val moveOut = NumberState(0)
    private var synced = false
    private var offerExpanded = true
    private var rentExpanded = false
    private var limitsExpanded = false
    private val looks = Memo()

    private class Look(val labels: Map<String, String>, val ranks: Map<String, String>, val locks: Map<String, Lock>)

    private fun rent(info: Info, cat: String) = (info.offer.rent[cat] ?: 0).toLong()
    private fun isOpen(info: Info, cat: String) = cat == "citizen" || cat in info.offer.open

    private fun offerChanges(info: Info) = cats.count { rents.getValue(it).value != rent(info, it) || open[it] != isOpen(info, it) }
    private fun limitChanges(info: Info) =
        ranks.count { rankLimits.getValue(it).value != (info.rankPlots[it] ?: 0).toLong() } + guests.count { guestLimits.getValue(it).value != (info.guestPlots[it] ?: 0).toLong() }
    private fun lawChanges(info: Info) = (if (debtLimit.value != info.rentDebtLimit) 1 else 0) + (if (moveOut.value != info.moveOutDays.toLong()) 1 else 0)

    private fun load(info: Info) {
        cats.forEach { rents.getValue(it).sync(rent(info, it)); open[it] = isOpen(info, it) }
        ranks.forEach { rankLimits.getValue(it).sync((info.rankPlots[it] ?: 0).toLong()) }
        guests.forEach { guestLimits.getValue(it).sync((info.guestPlots[it] ?: 0).toLong()) }
        debtLimit.sync(info.rentDebtLimit)
        moveOut.sync(info.moveOutDays.toLong())
    }

    private fun discard(info: Info) {
        cats.forEach { rents.getValue(it).commit(rent(info, it)); open[it] = isOpen(info, it) }
        ranks.forEach { rankLimits.getValue(it).commit((info.rankPlots[it] ?: 0).toLong()) }
        guests.forEach { guestLimits.getValue(it).commit((info.guestPlots[it] ?: 0).toLong()) }
        debtLimit.commit(info.rentDebtLimit)
        moveOut.commit(info.moveOutDays.toLong())
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val offerChanges = offerChanges(info)
        val limitChanges = limitChanges(info)
        val lawChanges = lawChanges(info)
        val changes = offerChanges + limitChanges + lawChanges
        if (!synced || changes == 0) { load(info); synced = true }
        val look = looks.of(info.offer.levels, Format.locale) {
            Look(cats.associateWith { tr("kami_claims.claimant.$it") }, ranks.associateWith { Vocabulary.rank(it).label },
                features.mapNotNull { (cat, id) -> info.offer.levels[cat]?.let { cat to Lock.level(it, ClientLocks.featureName(id)) } }.toMap())
        }
        val ceiling = ClientResearch.state.max(Capacity.PLOTS).toLong()
        val offerReason = lock("housing")
        val offerEdit = offerReason == null
        val limitEdit = can("tax")
        val content = r.dropBottom(34, 4)
        val offerOpen = cats.count { open[it] == true }
        val wide = content.w >= 380
        val group = LIMIT_SUB_H + 4 * ROW_H
        val limitsBody = if (wide) group else 2 * group + 4
        val total = 3 * SEC_H + 2 * SEC_GAP + (if (offerExpanded) cats.size * ROW_H + BODY_PAD else 0) + (if (rentExpanded) RENT_H + BODY_PAD else 0) + (if (limitsExpanded) limitsBody + BODY_PAD else 0)
        ui.scroll("housing", content, total) { area ->
            val col = Rect(area.x, area.y, area.w.coerceAtMost(MAX_W), area.h)
            var y = area.y
            val offerHead = Rect(col.x, y, col.w, SEC_H)
            ui.anchor("plotlaw:offer", offerHead)
            if (head(ui, offerHead, "plotlaw:h:offer", tr("kami_claims.law.offer.title"), tr("kami_claims.law.offer.open", offerOpen), offerChanges > 0, offerExpanded, tr("kami_claims.law.offer.help"))) offerExpanded = !offerExpanded
            y += SEC_H
            if (offerExpanded) {
                cats.forEachIndexed { i, cat ->
                    val row = Rect(col.x, y + 2 + i * ROW_H, col.w, ROW_H - 2)
                    OfferRows.draw(ui, row, cat, look.labels.getValue(cat), look.locks[cat], open[cat] == true, offerEdit, offerReason, 104, "plotlaw:$cat") { field ->
                        ui.numberField(field, rents.getValue(cat), 0, info.maxRent.toLong(), unit = "◎", enabled = offerEdit && look.locks[cat] == null, key = "plotlaw:rent:$cat")
                    }?.let { open[cat] = it }
                }
                if (!offerEdit) ui.tooltip("plotlaw:tip", Rect(col.x, y, col.w, cats.size * ROW_H), offerReason)
                y += cats.size * ROW_H + BODY_PAD
            }
            y += SEC_GAP
            val rentHead = Rect(col.x, y, col.w, SEC_H)
            ui.anchor("plotlaw:chain", rentHead)
            if (head(ui, rentHead, "plotlaw:h:rent", tr("kami_claims.common.rent_debt"), "${Format.money(debtLimit.value)} · ${trn("kami_claims.unit.day", moveOut.value)}", lawChanges > 0, rentExpanded, tr("kami_claims.law.chain.desc", Format.money(debtLimit.value), trn("kami_claims.unit.day", moveOut.value)))) rentExpanded = !rentExpanded
            y += SEC_H
            if (rentExpanded) {
                val cols = Rect(col.x, y + 2, col.w, 30).columns(2, 8)
                ui.fieldLabel(cols[0].top(9), tr("kami_claims.law.debt_limit"))
                ui.numberField(Rect(cols[0].x, cols[0].y + 10, cols[0].w, CONTROL_H), debtLimit, 0, 100_000, unit = "◎", enabled = limitEdit, key = "debtlimit")
                ui.fieldLabel(cols[1].top(9), tr("kami_claims.law.move_out"))
                ui.numberField(Rect(cols[1].x, cols[1].y + 10, cols[1].w, CONTROL_H), moveOut, 1, 30, enabled = limitEdit, key = "moveout")
                if (!limitEdit) ui.tooltip("plotlaw:tip:law", Rect(col.x, y, col.w, RENT_H), lock("tax"))
                y += RENT_H + BODY_PAD
            }
            y += SEC_GAP
            val limitsHead = Rect(col.x, y, col.w, SEC_H)
            ui.anchor("plotlaw:limits", limitsHead)
            val raise = ClientLocks.raise(Capacity.PLOTS)
            val chip = raise?.let { lockChipWidth(it) + 6 } ?: 0
            if (head(ui, Rect(limitsHead.x, limitsHead.y, limitsHead.w - chip, SEC_H), "plotlaw:h:limits", tr("kami_claims.law.limits.title"), "${tr("kami_claims.law.ceiling")} ${Format.number(ceiling)}", limitChanges > 0, limitsExpanded, tr("kami_claims.law.limits.help"))) limitsExpanded = !limitsExpanded
            raise?.let { ui.lockChip(limitsHead.right - chip + 3, limitsHead.y + 3, it, "plotlaw:ceiling") }
            y += SEC_H
            if (limitsExpanded) {
                y += 2
                val groups = if (wide) Rect(col.x, y, col.w, group).columns(2, 12) else listOf(Rect(col.x, y, col.w, group), Rect(col.x, y + group + 4, col.w, group))
                val rankSec = ui.section(Rect(groups[0].x, groups[0].y, groups[0].w, 12), tr("kami_claims.law.limits.ranks"))
                ranks.forEachIndexed { i, k ->
                    limitRow(ui, Rect(rankSec.x, rankSec.y + i * ROW_H, rankSec.w, ROW_H - 2), look.ranks.getValue(k), null, null, rankLimits.getValue(k), ceiling, limitEdit, "plotlaw:rank:$k")
                }
                val guestSec = ui.section(Rect(groups[1].x, groups[1].y, groups[1].w, 12), tr("kami_claims.law.limits.guests"))
                guests.forEachIndexed { i, k ->
                    limitRow(ui, Rect(guestSec.x, guestSec.y + i * ROW_H, guestSec.w, ROW_H - 2), look.labels.getValue(k), OfferRows.ICONS.getValue(k), look.locks[k], guestLimits.getValue(k), ceiling, limitEdit, "plotlaw:guest:$k")
                }
            }
        }
        val summary = if (changes == 0) "" else tr("kami_claims.law.housing.summary", Format.number(offerChanges), Format.number(limitChanges), Format.number(lawChanges))
        ui.applyBar(r.bottom(28), changes, summary, offerEdit || limitEdit, null, pending("plot_law"), "plotlaw", {
            cats.forEach { cat ->
                if (rents.getValue(cat).value != rent(info, cat) || open[cat] != isOpen(info, cat)) act("plot_offer", cat, if (cat == "citizen" || open[cat] == true) "on" else "off", rents.getValue(cat).value.toString(), "default", key = "plot_offer:$cat")
            }
            ranks.forEach { k -> if (rankLimits.getValue(k).value != (info.rankPlots[k] ?: 0).toLong()) act("plot_limit", k, rankLimits.getValue(k).value.coerceIn(0, ceiling).toString(), key = "plot_limit:$k") }
            guests.forEach { k -> if (guestLimits.getValue(k).value != (info.guestPlots[k] ?: 0).toLong()) act("plot_limit", k, guestLimits.getValue(k).value.coerceIn(0, ceiling).toString(), key = "plot_limit:$k") }
            if (lawChanges > 0) act("plot_law", debtLimit.value.toString(), moveOut.value.toString(), key = "plot_law")
        }, { discard(info) })
    }

    private fun head(ui: Ui, r: Rect, key: String, title: String, summary: String, changed: Boolean, expanded: Boolean, tip: String): Boolean {
        val hit = ui.clickable(key, r)
        if (ui.hovering(r)) Draw.fill(ui.g, r, Palette.hover)
        val x = r.x + 2 + Draw.leadIcon(ui.g, if (expanded) Icons.CHEVRON_DOWN else Icons.CHEVRON_RIGHT, r.x + 2, r.centerY) + 2
        Draw.text(ui.g, title, x, r.y + 6, TextStyle.HEADING)
        val titleW = Draw.width(title, TextStyle.HEADING)
        Draw.textRight(ui.g, Draw.fit(summary, (r.right - x - titleW - 24).coerceAtLeast(20)), r.right - 6, r.y + 6, Palette.textMuted)
        if (changed) Draw.fill(ui.g, Rect(x + titleW + 4, r.centerY - 2, 4, 4), Palette.brass)
        Draw.hline(ui.g, r.x, r.bottom - 1, r.w, Palette.borderSubtle)
        ui.tooltip("$key:tip", r, tip)
        return hit
    }

    private fun limitRow(ui: Ui, row: Rect, label: String, icon: Icon?, gate: Lock?, state: NumberState, ceiling: Long, edit: Boolean, key: String) {
        ui.locked(row, gate, "$key:lock") {
            val field = Row(row, 6).takeFromRight(104)
            val x = row.x + (icon?.let { Draw.leadIcon(ui.g, it, row.x, row.centerY) + 2 } ?: 0)
            Draw.text(ui.g, Draw.fit(label, field.x - x - 4), x, row.y + 5, Palette.text)
            ui.numberField(field, state, 0, ceiling, enabled = edit && gate == null, key = key)
        }
        if (!edit) ui.tooltip("$key:tip", row, lock("tax"))
    }
}

private const val ROW_H = 22
private const val SEC_H = 20
private const val SEC_GAP = 4
private const val BODY_PAD = 6
private const val RENT_H = 34
private const val LIMIT_SUB_H = 13
private const val MAX_W = 440

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
        val lf = Flow(left, 6)
        val pb = ui.card(lf.take(left.h - NAME_CARD_H - 6), tr("kami_claims.identity.preview"), Icons.FLAG)
        ui.anchor("identity:preview", left)
        nameCard(ui, lf.remaining())
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
            val row = Row(f.take(CONTROL_H))
            if (ui.edgeButton(row, tr("kami_claims.identity.save"), Icons.SAVE, ButtonStyle.PRIMARY, editable, lock("rules"), pending = pending("flag"), key = "save-flag")) {
                act("flag", "%06x".format(color and 0xFFFFFF), pattern.toString(), emblem.toString(), "%06x".format(secondary and 0xFFFFFF), key = "flag")
            }
            if (ui.edgeButton(row, tr("kami_libs.common.discard"), Icons.UNDO, key = "discard-flag")) color = -1
        }
    }

    private fun nameCard(ui: Ui, r: Rect) {
        val f = Flow(ui.card(r, tr("kami_libs.common.name"), Icons.EDIT), 3)
        val offer = tokenOffer(Tokens.RENAME)
        val line = f.take(10)
        Draw.text(ui.g, Draw.fit(offer.text, line.w), line.x, line.y + 1, offer.severity.color)
        val president = ClaimsStore.rank == Rank.PRESIDENT
        if (ui.button(f.take(CONTROL_H), tr("kami_claims.common.rename_country"), Icons.EDIT, enabled = president, disabledReason = tr("kami_claims.identity.rename.president"), key = "rename")) Dialogs.rename(app)
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
private const val NAME_CARD_H = 62
