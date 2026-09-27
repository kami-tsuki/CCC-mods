package kami.claims.client.app.pages

import kami.libs.ui.text.trJson
import kami.libs.ui.text.trn
import kami.libs.ui.text.tr
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Flags
import kami.claims.client.app.Illustrations
import kami.claims.client.app.consequences
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.Route
import kami.libs.ui.app.wizardButtons
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*

class WelcomePage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.welcome")
    override val needsCountry = false
    override val sections = listOf("world")
    override val help get() = listOf(
        Callout("welcome:found", tr("kami_claims.welcome.help.found"), tr("kami_claims.welcome.help.found.desc")),
        Callout("welcome:join", tr("kami_claims.welcome.help.join"), tr("kami_claims.welcome.help.join.desc")),
        Callout("welcome:invites", tr("kami_claims.welcome.invites"), tr("kami_claims.welcome.help.invites.desc"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val head = r.top(HEAD_H)
        Draw.sprite(ui.g, Illustrations.NOMANSLAND, Rect(head.x, head.y, 32, 32))
        Draw.text(ui.g, tr("kami_claims.welcome.title"), head.x + 40, head.y + 4, TextStyle.TITLE)
        val desc = tr("kami_claims.welcome.desc")
        val shownDesc = Draw.fit(desc, head.w - 40)
        Draw.text(ui.g, shownDesc, head.x + 40, head.y + 18, Palette.textSecondary)
        if (shownDesc != desc) ui.tooltip("welcome-desc", head, desc)
        val cards = r.dropTop(HEAD_H + 6).top(CHOICE_H).columns(2, 6)
        ui.anchor("welcome:found", cards[0])
        ui.anchor("welcome:join", cards[1])
        choice(ui, cards[0], Illustrations.FOUND, tr("kami_claims.welcome.found.title"), listOf(tr("kami_claims.welcome.found.free", trn("kami_claims.unit.chunk", snap.freeChunks)), tr("kami_claims.welcome.found.upkeep"), tr("kami_claims.welcome.found.president")), tr("kami_claims.welcome.found.action"), "found") { openWizard() }
        val countries = snap.countries.size
        choice(ui, cards[1], Illustrations.CITIZENS, tr("kami_claims.welcome.join.title"), listOf(tr("kami_claims.welcome.join.count", trn("kami_claims.unit.country", countries)), tr("kami_claims.welcome.join.land"), tr("kami_claims.welcome.join.jobs")), tr("kami_claims.welcome.join.action"), "join") { app.navigate(Route("world")) }
        val list = r.dropTop(HEAD_H + CHOICE_H + 14)
        ui.anchor("welcome:invites", list)
        val body = ui.section(list.top(14), tr("kami_claims.welcome.invites"), Format.number(snap.invites.size))
        if (snap.invites.isEmpty()) {
            Draw.text(ui.g, tr("kami_claims.welcome.invites.empty"), body.x, body.y + 4, Palette.textMuted)
            return
        }
        var y = body.y
        snap.invites.forEach { name ->
            val line = snap.countries.firstOrNull { it.name == name }
            val row = Rect(body.x, y, body.w, 24)
            Draw.sprite(ui.g, Sprites.CARD, row)
            Flags.draw(ui.g, Rect(row.x + 6, row.y + 6, 16, 12), line?.color ?: 0x888888, line?.flag?.pattern ?: 0, line?.flag?.emblem ?: 0, line?.flag?.secondary ?: 0xFFFFFF)
            Draw.text(ui.g, name, row.x + 28, row.y + 3, TextStyle.HEADING)
            line?.let {
                val size = listOf(trn("kami_claims.unit.citizen", it.members), trn("kami_claims.unit.chunk", it.chunks))
                Draw.text(ui.g, if (it.parent.isNotEmpty()) tr("kami_claims.welcome.invite.row_province", size[0], size[1], it.parent) else tr("kami_claims.welcome.invite.row", size[0], size[1]), row.x + 28, row.y + 13, Palette.textMuted)
            }
            val join = tr("kami_claims.join.confirm.action")
            val joinW = buttonWidth(join)
            if (ui.button(Rect(row.right - joinW - 4, row.y + 3, joinW, CONTROL_H), join, style = ButtonStyle.PRIMARY, pending = pending("accept"), key = "join:$name")) {
                Dialogs.confirm(app, tr("kami_claims.join.confirm.title", name), tr("kami_claims.join.confirm.subtitle"), Icons.PEOPLE, listOf(
                    Consequence(tr("kami_claims.join.confirm.single")),
                    Consequence(tr("kami_claims.join.confirm.laws")),
                    Consequence(tr("kami_claims.join.confirm.leave"), Severity.SUCCESS)
                ), tr("kami_claims.join.confirm.action"), "accept", arrayOf(name))
            }
            if (ui.button(Rect(row.right - 124, row.y + 3, 56, 18), tr("kami_claims.welcome.view"), key = "view:$name")) app.navigate(Route("world", focus = "country:$name"))
            y += 28
        }
    }

    private fun choice(ui: Ui, r: Rect, art: net.minecraft.resources.ResourceLocation, title: String, facts: List<String>, action: String, key: String, onClick: () -> Unit) {
        Draw.sprite(ui.g, Sprites.CARD, r)
        Draw.sprite(ui.g, art, Rect(r.x + 8, r.y + 8, 32, 32))
        val textX = r.x + 48
        Draw.text(ui.g, Draw.fit(title, r.right - textX - 8, TextStyle.TITLE), textX, r.y + 8, TextStyle.TITLE, Palette.brass)
        facts.forEachIndexed { i, f ->
            val rowY = r.y + 20 + i * TABLE_ROW_H
            val x = textX + Draw.leadIcon(ui.g, Icons.CHECK, textX, rowY + TABLE_ROW_H / 2) + 2
            val shown = Draw.fit(f, r.right - x - 8)
            Draw.text(ui.g, shown, x, rowY + 3, Palette.textSecondary)
            if (shown != f) ui.tooltip("$key:fact:$i", Rect(textX, rowY, r.right - textX, TABLE_ROW_H), f)
        }
        val w = buttonWidth(action, Icons.FORWARD)
        if (ui.button(Rect(r.right - w - 8, r.bottom - CONTROL_H - 8, w, CONTROL_H), action, Icons.FORWARD, ButtonStyle.PRIMARY, key = key)) onClick()
    }

    private fun openWizard() {
        val name = TextState()
        var tour = true
        app.open(Dialog(tr("kami_claims.welcome.found.title"), tr("kami_claims.wizard.subtitle"), Icons.FLAG, DialogKind.CONFIRM, 380,
            listOf(tr("kami_claims.wizard.step.location"), tr("kami_claims.wizard.step.name"), tr("kami_claims.wizard.step.rules"), tr("kami_claims.wizard.step.confirm"))) { s ->
            val snap = ClaimsStore.snap ?: return@Dialog
            val b = s.body
            val limits = snap.limits
            val min = limits?.nameMin ?: 3
            val max = limits?.nameMax ?: 24
            name.error = when {
                name.text.length < min -> tr("kami_claims.wizard.name.error.short", min)
                !name.text.all { it.isLetterOrDigit() || it == '_' || it == '-' } -> tr("kami_claims.wizard.name.error.chars")
                snap.countries.any { it.name.equals(name.text, true) } -> tr("kami_claims.wizard.name.error.taken")
                else -> null
            }
            val here = snap.detail?.takeIf { it.x == snap.px && it.z == snap.pz }
            val problem = here?.takeIf { it.blocked }?.let { trJson(it.note) }
            when (s.step) {
                0 -> {
                    MiniMap.draw(this, Rect(b.x, b.y, 150, 150), snap.px, snap.pz, 6, "found", (-1..1).flatMap { dx -> (-1..1).map { dz -> snap.px + dx to snap.pz + dz } }, you = snap.px to snap.pz)
                    val f = Flow(Rect(b.x + 160, b.y, b.w - 160, 150), 4)
                    f.take(12).let { Draw.text(g, tr("kami_claims.wizard.capital"), it.x, it.y, TextStyle.HEADING) }
                    property(f.take(11), tr("kami_claims.wizard.chunk"), "${snap.px}, ${snap.pz}", copy = true)
                    property(f.take(11), tr("kami_claims.wizard.free_land"), trn("kami_claims.unit.chunk", snap.freeChunks), Palette.success)
                    f.skip(4)
                    f.take(Draw.paragraph(g, tr("kami_claims.wizard.capital.desc"), f.rest.x, f.rest.y, f.rest.w))
                    if (problem != null) callout(f.take(30), Severity.DANGER, problem)
                    s.used = 154
                }
                1 -> {
                    var y = b.y
                    fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_claims.wizard.name"), "${name.text.length}/$max"); y += 11
                    textField(Rect(b.x, y, b.w, CONTROL_H), name, tr("kami_claims.wizard.name.placeholder"), Icons.FLAG, maxLength = max, allow = { it.isLetterOrDigit() || it == '_' || it == '-' }, key = "name", autoFocus = true); y += CONTROL_H + 2
                    name.touched = name.text.isNotEmpty()
                    fieldHelp(Rect(b.x, y, b.w, 9), name, tr("kami_claims.wizard.name.desc", min, max)); y += 16
                    Draw.text(g, tr("kami_claims.wizard.name.later"), b.x, y, Palette.textMuted); y += 14
                    s.used = y - b.y
                }
                2 -> {
                    val cards = listOf(
                        Triple(Icons.AREA, tr("kami_claims.kpi.land"), tr("kami_claims.wizard.rules.land", trn("kami_claims.unit.chunk", snap.freeChunks))),
                        Triple(Icons.TREASURY, tr("kami_claims.kpi.treasury"), tr("kami_claims.wizard.rules.treasury")),
                        Triple(Icons.PEOPLE, tr("kami_claims.nav.citizens"), tr("kami_claims.wizard.rules.citizens")),
                        Triple(Icons.SHIELD, tr("kami_claims.nav.protection"), tr("kami_claims.wizard.rules.protection"))
                    )
                    var y = b.y
                    cards.forEach { (icon, head, text) ->
                        val x = b.x + Draw.leadIcon(g, icon, b.x, y + 4) + 4
                        Draw.text(g, head, x, y, TextStyle.HEADING)
                        y += 11 + Draw.paragraph(g, text, x, y + 11, b.right - x) + 4
                    }
                    checkbox(Rect(b.x, y, b.w, 14), tr("kami_claims.wizard.tour"), tour)?.let { tour = it }
                    s.used = y + 16 - b.y
                }
                else -> {
                    var y = b.y
                    Flags.draw(g, Rect(b.x, y, 36, 26), 0xB08D57, 0, 1, 0xFFFFFF)
                    Draw.text(g, name.text, b.x + 44, y + 4, TextStyle.TITLE)
                    Draw.text(g, tr("kami_claims.wizard.capital_at", snap.px, snap.pz), b.x + 44, y + 15, Palette.textMuted); y += 34
                    y += consequences(b.x, y, b.w, listOf(
                        Consequence(tr("kami_claims.wizard.confirm.president", name.text), Severity.SUCCESS),
                        Consequence(tr("kami_claims.wizard.confirm.free", trn("kami_claims.unit.chunk", snap.freeChunks)), Severity.SUCCESS),
                        Consequence(tr("kami_claims.wizard.confirm.upkeep")),
                        Consequence(tr("kami_claims.wizard.confirm.single"))
                    ))
                    s.used = y - b.y + 4
                }
            }
            val reason = when (s.step) {
                0 -> problem
                1 -> name.error
                else -> null
            }
            wizardButtons(s, if (name.text.isEmpty()) tr("kami_claims.wizard.finish.default") else tr("kami_claims.wizard.finish", name.text), reason == null, reason) {
                ClaimsStore.send("create", name.text, key = "create")
                kami.claims.client.ClientClaims.prefs.tourDone = !tour
                kami.claims.client.ClientClaims.savePrefs()
                s.close()
            }
        })
    }
}

private const val HEAD_H = 34
private const val CHOICE_H = 96
