package kami.claims.client.app.pages

import kami.claims.client.app.Tenure
import kami.claims.client.ClientClaims
import kami.claims.client.map.ChunkCells
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.tokenOffer
import kami.claims.research.Tokens
import kami.claims.client.app.ClaimsPage
import kami.libs.ui.app.Consequence
import kami.claims.client.app.Dialogs
import kami.libs.ui.widget.Flags
import kami.libs.ui.widget.lockChip
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.MiniMap
import kami.claims.gov.Perm
import kami.claims.gov.SectorRules
import kami.claims.net.Detail
import kami.claims.net.MineLine
import kami.claims.net.OfferLine
import kami.claims.service.View
import kami.libs.ui.app.Route
import kami.libs.ui.app.Personal
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
import kotlin.math.max

class MapSide(private val page: ClaimsPage, private val planner: MapPlanner) {
    private var accessOpen = false
    private val offerEditor = OfferEditor(page)
    private val staffPanel = StaffPanel(page, planner)
    private val cardLock = Memo()
    private val lockedMemo = Memo()
    private val tenantRent = Memo()
    private val rentText = Memo()
    private val debtText = Memo()

    private val app get() = page.app
    private val snap get() = page.snap
    private val info get() = page.info
    private val limits get() = page.limits
    private val dim get() = Dim.current()
    private val selection get() = planner.selection
    private fun lock(perm: Perm) = page.lock(perm)
    private fun can(perm: Perm) = page.can(perm)
    private fun pending(key: String) = page.pending(key)
    private fun unkey(k: Long) = ChunkCells.cell(k)

    fun reset() {
        offerEditor.close()
        staffPanel.close()
        accessOpen = false
    }

    fun draw(ui: Ui, r: Rect) {
        val inner = ui.sidePanel(r, inset = 6).rest
        when {
            selection.isEmpty() -> overview(ui, inner)
            selection.size == 1 && !planner.planning -> single(ui, inner)
            else -> planner.draw(ui, inner)
        }
    }

    private fun overview(ui: Ui, r: Rect) {
        val size = ui.remember("overview-h") { IntArray(1) }
        ui.scroll("map:overview", r, size[0]) { c ->
            val f = Flow(c.withHeight(4000), 4)
            overviewBody(ui, f, c)
            size[0] = f.rest.y - c.y
        }
    }

    private fun overviewBody(ui: Ui, f: Flow, r: Rect) {
        f.take(Draw.paragraph(ui.g, tr("kami_claims.map.howto.inspect"), r.x, r.y, r.w, Palette.textMuted))
        f.skip(6)
        ui.section(f.take(14), tr("kami_claims.map.prices"), tr("kami_claims.map.prices.unit"))
        snap.types.forEach { t ->
            val row = f.take(TABLE_ROW_H)
            val look = Vocabulary.type(t.name)
            val x = row.x + Draw.leadIcon(ui.g, look.icon, row.x, row.centerY) + 2
            val typeLock = ClientLocks.claimType(t.name)
            Draw.text(ui.g, look.label, x, row.y + 3, if (typeLock == null) look.color else Palette.textMuted)
            typeLock?.let { ui.lockChip(x + Draw.width(look.label) + 4, row.y - 1, it, "price-lock:${t.name}") }
            Draw.textRight(ui.g, if (t.period > 1) "${Format.money(t.price.toLong())} / ${Format.days(t.period.toLong())}" else Format.money(t.price.toLong()), row.right, row.y + 3, Palette.textSecondary)
            ui.tooltip("price:${t.name}", row, look.description)
        }
        info?.let { i ->
            f.skip(6)
            ui.section(f.take(14), tr("kami_claims.chunks.help.table"))
            ui.property(f.take(11), tr("kami_claims.nav.chunks"), Format.number(i.chunks))
            ui.property(f.take(11), tr("kami_claims.map.free_left"), Format.number((i.freeAllowed - i.chunks).coerceAtLeast(0)), Palette.success)
            ui.property(f.take(11), tr("kami_claims.ledger.upkeep"), Format.perDay(Format.money(i.upkeep)), Palette.danger)
        }
    }

    private fun single(ui: Ui, r: Rect) {
        val (x, z) = unkey(selection.first())
        val d = snap.detail?.takeIf { it.x == x && it.z == z }
        if (d == null) {
            ui.spinner(r.x, r.y)
            Draw.text(ui.g, tr("kami_claims.map.loading", x, z), r.x + 12, r.y, Palette.textMuted)
            return
        }
        val e = ClientClaims.at(dim, x, z)
        val own = e != null && e.flags and View.OWN != 0
        val buttons = (if (e == null && info != null) 1 else 0) + (if (own) 1 else 0) + (if (own && e != null && e.flags and View.CAPITAL == 0) 1 else 0) + (if (sectorable(e)) 1 else 0)
        val actionsH = if (buttons == 0) 0 else buttons * (CONTROL_H + 3)
        val size = ui.remember("side-h") { IntArray(1) }
        ui.scroll("side:$x:$z", r.dropBottom(actionsH), size[0]) { c ->
            val f = Flow(c.withHeight(4000), 3)
            detail(ui, f, c, d, x, z, e)
            size[0] = f.rest.y - c.y
        }
        if (buttons > 0) chunkActions(ui, r.bottom(actionsH), x, z, e)
    }

    private fun detail(ui: Ui, f: Flow, r: Rect, d: Detail, x: Int, z: Int, e: View.Entry?) {
        val head = f.take(22)
        val country = e?.let { ClientClaims.country(it) }
        if (country != null) Flags.draw(ui.g, Rect(head.x, head.y + 4, 16, 12), country.color, country.pattern, country.emblem, country.secondary)
        val textX = head.x + (if (country != null) 21 else 0)
        ui.fitText(textX, head.y + 1, head.right - textX, d.country.ifEmpty { tr("kami_claims.help.term.nomansland") }, style = TextStyle.HEADING)
        Draw.text(ui.g, tr("kami_claims.map.chunk", x, z), textX, head.y + 11, Palette.textMuted)
        MiniMap.draw(ui, f.take(if (app.compact) 52 else 64), x, z, 3, "side", listOf(x to z))
        if (d.type.isNotEmpty()) {
            val look = Vocabulary.type(d.type)
            ui.property(f.take(11), tr("kami_libs.common.type"), if (d.capital) tr("kami_claims.map.chunk.capital", look.label) else look.label, look.color, tip = look.description)
            ui.property(f.take(11), tr("kami_claims.ledger.upkeep"), if (d.free) tr("kami_libs.common.free") else Vocabulary.rate(d.price, d.period))
            if (d.owner.isNotEmpty()) ui.property(f.take(11), tr("kami_claims.role.owner"), d.owner)
            if (d.debt > 0) {
                val max = limits?.maxDebt ?: 3
                ui.property(f.take(11), tr("kami_claims.help.term.debt"), tr("kami_claims.map.debt", d.debt, max), Palette.danger)
                f.skip(2)
                ui.meter(f.take(5), d.debt, max, { if (it >= max) Severity.DANGER else Severity.WARNING })
            }
        } else if (d.note.isNotEmpty()) f.take(Draw.paragraph(ui.g, trJson(d.note), r.x, f.rest.y, r.w, Palette.textSecondary))
        if (d.reserved.isNotEmpty()) f.take(ui.callout(f.rest, Severity.WARNING, tr("kami_claims.map.reserved", d.reserved)))
        if (d.locked.isNotEmpty()) {
            val row = f.take(13)
            ui.lockChip(row.x, row.y, lockedLock(d.locked), "chunk-locked")
        }
        if (d.type == "residential") housing(ui, f, d, x, z)
        staffPanel.draw(ui, f, d, x, z, e)
        if (d.access.isNotEmpty()) {
            f.skip(4)
            if (ui.disclosure(f.take(14), tr("kami_claims.map.access"), accessOpen, key = "access-fold", hairline = true)) accessOpen = !accessOpen
            if (accessOpen) {
                val cells = f.take(2 * TABLE_ROW_H).grid(2, TABLE_ROW_H, 4)
                listOf("break" to 0, "place" to 1, "interact" to 2, "container" to 3).forEachIndexed { i, (k, a) ->
                    val allowed = d.access[k] == true
                    val look = Vocabulary.actions[a]
                    val tx = cells[i].x + Draw.leadIcon(ui.g, if (allowed) Icons.CHECK else Icons.CROSS, cells[i].x, cells[i].centerY) + 2
                    Draw.text(ui.g, look.label, tx, cells[i].y + 3, if (allowed) Palette.textSecondary else Palette.textMuted)
                    ui.tooltip("access:$k", cells[i], look.description)
                }
            }
        }
    }

    private fun lockedLock(raw: String) = lockedMemo.of(raw, Format.locale) { Lock(tr("kami_libs.common.locked"), tr("kami_claims.map.locked", trJson(raw))) }

    private fun housing(ui: Ui, f: Flow, d: Detail, x: Int, z: Int) {
        val o = d.offer
        val officer = d.relation == View.REL_MEMBER && can(Perm.HOUSING)
        d.mine?.let { tenant(ui, f, d, it, x, z) }
        if (o != null && d.mine == null && (!d.taken || !officer)) card(ui, f, d, o, x, z)
        if (o != null && officer) offerEditor.draw(ui, f, d, o, x, z)
    }

    private fun plotLock(o: OfferLine) =
        if (o.lock == "level") Lock.level(o.level, Tenure.category(o.category.ifEmpty { "random" }))
        else Lock(tr("kami_claims.map.plot.lock.${o.lock}"), tr("kami_claims.map.plot.lock.${o.lock}.how"))

    private fun card(ui: Ui, f: Flow, d: Detail, o: OfferLine, x: Int, z: Int) {
        f.skip(4)
        if (o.lock != "taken") {
            val row = f.take(12)
            val price = rentText.of(o.price, Format.locale) { Format.perDay(Format.money(o.price.toLong())) }
            Draw.text(ui.g, price, row.x, row.y + 2, Palette.money)
            if (o.category.isNotEmpty()) {
                val catW = row.w - Draw.width(price) - 8
                ui.fitText(row.right - catW, row.y + 2, catW, Tenure.category(o.category), Palette.textMuted, align = Align.RIGHT)
            }
            ui.tooltip("plot-group", row, tr("kami_claims.map.plot.group"))
        }
        val lock = if (o.lock.isEmpty()) null else cardLock.of(o.lock, o.level, o.category, Format.locale) { plotLock(o) }
        if (ui.lockedButton(f.take(CONTROL_H), tr("kami_claims.plots.rent"), lock, Icons.HOUSE, ButtonStyle.PRIMARY, pending = pending("plot_claim"), key = "plot-rent")) Dialogs.rent(app, x, z, o.price, d.country)
    }

    private fun tenant(ui: Ui, f: Flow, d: Detail, m: MineLine, x: Int, z: Int) {
        f.skip(4)
        val row = f.take(13)
        val moving = d.state == Tenure.MOVING
        val w = ui.badge(row.x, row.y + 1, Tenure.label(d.state), Tenure.severity(d.state, m.debt))
        if (moving && d.until > 0) ui.fitText(row.x + w + 4, row.y + 2, row.right - row.x - w - 4, Tenure.note(d, d.state, d.until, m.debt), Palette.danger)
        if (d.state == "removed") f.take(ui.callout(f.rest, Severity.WARNING, tr("kami_claims.map.plot.removed.desc")))
        f.skip(2)
        ui.property(f.take(11), tr("kami_libs.flow.rent"), tenantRent.of(m.price, m.category, Format.locale) { "${Format.perDay(Format.money(m.price.toLong()))} · ${Tenure.category(m.category)}" })
        if (m.debt > 0) {
            ui.property(f.take(11), tr("kami_claims.common.rent_debt"), debtText.of(m.debt, m.limit, Format.locale) { tr("kami_libs.format.of", Format.money(m.debt), Format.money(m.limit)) }, Palette.warning)
            if (m.limit > 0) {
                f.skip(2)
                val max = m.limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                ui.meter(f.take(5), m.debt.coerceAtMost(max.toLong()).toInt(), max, { if (it >= max) Severity.DANGER else Severity.WARNING })
            }
        }
        f.skip(2)
        if (Personal.available && ui.button(f.take(CONTROL_H), tr("kami_claims.map.plot.view_mine"), Icons.HOUSE, ButtonStyle.SECONDARY, key = "plot-mine")) Personal.open(Route("claims.plots", mapOf("dim" to dim), "$x:$z"))
        f.skip(2)
        if (ui.button(f.take(CONTROL_H), tr("kami_claims.plots.release"), Icons.REMOVE, ButtonStyle.SECONDARY, pending = pending("plot_release"), key = "plot-release")) Dialogs.release(app, x, z, d.country, moving)
    }

    private fun sectorable(e: View.Entry?) = e != null && e.flags and View.OWN != 0 && ClientClaims.typeName(e) in SectorRules.workTypes

    private fun chunkActions(ui: Ui, rest: Rect, x: Int, z: Int, e: View.Entry?) {
        val own = e != null && e.flags and View.OWN != 0
        var y = rest.bottom - CONTROL_H
        fun place(label: String, icon: Icon, style: ButtonStyle, enabled: Boolean, reason: String?, key: String, action: () -> Unit) {
            val cell = Rect(rest.x, y, rest.w, CONTROL_H)
            if (ui.button(cell, label, icon, style, enabled, reason, pending = pending(key), key = "chunk-$key")) action()
            y -= CONTROL_H + 3
        }
        if (e == null && info != null) place(tr("kami_claims.map.action.plan"), Icons.ADD, ButtonStyle.PRIMARY, Plan.CLAIM.lock(page) == null, Plan.CLAIM.lock(page), "claim") { planner.begin(Plan.CLAIM) }
        if (own && e != null) {
            if (e.flags and View.CAPITAL == 0) place(tr("kami_claims.map.action.capital"), Icons.CROWN, ButtonStyle.SECONDARY, can(Perm.CAPITAL) && can(Perm.SPEND), lock(Perm.CAPITAL) ?: lock(Perm.SPEND), "capital") {
                Dialogs.confirm(app, tr("kami_claims.map.capital.confirm.title", x, z), null, Icons.CROWN, listOf(
                    Consequence(tr("kami_claims.map.capital.safe")),
                    tokenOffer(Tokens.CAPITAL_MOVE).let { Consequence(it.text, it.severity) },
                    Consequence(tr("kami_claims.map.capital.cooldown", trn("kami_claims.unit.day", limits?.capitalCooldownDays ?: 7)), Severity.WARNING)
                ), tr("kami_claims.cap.capital"), "capital", arrayOf(x.toString(), z.toString()))
            }
            place(tr("kami_claims.map.action.retype"), Icons.EDIT, ButtonStyle.SECONDARY, can(Perm.CHUNK_TYPE), lock(Perm.CHUNK_TYPE), "retype") { planner.begin(Plan.RETYPE) }
        }
        if (sectorable(e)) place(tr("kami_claims.map.plan.sector"), Icons.LAYERS, ButtonStyle.SECONDARY, can(Perm.SECTORS), lock(Perm.SECTORS), "sector") { planner.begin(Plan.SECTOR) }
    }
}
