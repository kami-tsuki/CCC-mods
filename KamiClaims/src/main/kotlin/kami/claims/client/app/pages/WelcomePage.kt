package kami.claims.client.app.pages

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
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*

class WelcomePage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Welcome"
    override val needsCountry = false
    override val sections = listOf("world")
    override val help = listOf(
        Callout("welcome:found", "Found", "Start on your current chunk with free starter land."),
        Callout("welcome:join", "Join", "Browse countries and send a join request."),
        Callout("welcome:invites", "Invites", "Open invites you can accept instantly.")
    )

    override fun draw(ui: Ui, r: Rect) {
        val head = r.top(78)
        Draw.sprite(ui.g, Illustrations.NOMANSLAND, Rect(head.x, head.y + 4, 64, 64))
        Draw.text(ui.g, "You are in nomansland", head.x + 76, head.y + 10, TextStyle.TITLE)
        Draw.paragraph(ui.g, "No owner here. Build/break is blocked. Found a country or join one.", head.x + 76, head.y + 26, head.w - 80)
        val cards = r.dropTop(84).top(120).columns(2, 10)
        ui.anchor("welcome:found", cards[0])
        ui.anchor("welcome:join", cards[1])
        choice(ui, cards[0], Illustrations.FOUND, "Found a country", listOf("${snap.freeChunks} free chunks incl. capital", "Upkeep starts after free land", "You become president"), "Start", "found") { openWizard() }
        val countries = snap.countries.size
        choice(ui, cards[1], Illustrations.CITIZENS, "Join a country", listOf("${Format.plural(countries, "country", "countries")} on this server", "Shared land and protection", "Jobs and plot renting"), "Browse", "join") { app.navigate(Route("world")) }
        val list = r.dropTop(210)
        ui.anchor("welcome:invites", list)
        val body = ui.section(list.top(14), "Invitations", "${snap.invites.size}")
        if (snap.invites.isEmpty()) {
            Draw.text(ui.g, "No invites yet. Ask officers for an invite or request from World.", body.x, body.y + 4, Palette.textMuted)
            return
        }
        var y = body.y
        snap.invites.forEach { name ->
            val line = snap.countries.firstOrNull { it.name == name }
            val row = Rect(body.x, y, body.w, 24)
            Draw.sprite(ui.g, kami.libs.ui.style.Sprites.CARD, row)
            Flags.draw(ui.g, Rect(row.x + 5, row.y + 5, 18, 13), line?.color ?: 0x888888, line?.flag?.pattern ?: 0, line?.flag?.emblem ?: 0, line?.flag?.secondary ?: 0xFFFFFF)
            Draw.text(ui.g, name, row.x + 30, row.y + 5, TextStyle.HEADING)
            line?.let { Draw.text(ui.g, "${Format.plural(it.members, "citizen")} · ${Format.plural(it.chunks, "chunk")}${if (it.parent.isNotEmpty()) " · province of ${it.parent}" else ""}", row.x + 30, row.y + 14, Palette.textMuted) }
            if (ui.button(Rect(row.right - 64, row.y + 3, 60, 18), "Join", style = ButtonStyle.PRIMARY, pending = pending("accept"), key = "join:$name")) {
                Dialogs.confirm(app, "Join $name", "You become a citizen", Icons.PEOPLE, listOf(
                    Consequence("One country membership at a time."),
                    Consequence("You follow $name laws and can rent residential plots."),
                    Consequence("You can leave later.", Severity.SUCCESS)
                ), "Join $name", "accept", arrayOf(name))
            }
            if (ui.button(Rect(row.right - 124, row.y + 3, 56, 18), "View", key = "view:$name")) app.navigate(Route("world", focus = "country:$name"))
            y += 28
        }
    }

    private fun choice(ui: Ui, r: Rect, art: net.minecraft.resources.ResourceLocation, title: String, facts: List<String>, action: String, key: String, onClick: () -> Unit) {
        Draw.sprite(ui.g, kami.libs.ui.style.Sprites.CARD, r)
        Draw.sprite(ui.g, art, Rect(r.x + 8, r.y + 10, 48, 48))
        Draw.text(ui.g, title, r.x + 64, r.y + 12, TextStyle.TITLE, Palette.brass)
        facts.forEachIndexed { i, f ->
            Draw.icon(ui.g, Icons.CHECK, r.x + 62, r.y + 24 + i * 12, 12)
            Draw.text(ui.g, Draw.fit(f, r.w - 82), r.x + 76, r.y + 26 + i * 12, Palette.textSecondary)
        }
        val w = buttonWidth(action, Icons.FORWARD)
        if (ui.button(Rect(r.right - w - 8, r.bottom - 28, w, CONTROL_H), action, Icons.FORWARD, ButtonStyle.PRIMARY, key = key)) onClick()
    }

    private fun openWizard() {
        val name = TextState()
        var tour = true
        app.open(Dialog("Found a country", "Current chunk", Icons.FLAG, DialogKind.CONFIRM, 380, listOf("Location", "Name", "Rules", "Confirm")) { s ->
            val snap = ClaimsStore.snap ?: return@Dialog
            val b = s.body
            val limits = snap.limits
            val min = limits?.nameMin ?: 3
            val max = limits?.nameMax ?: 24
            name.error = when {
                name.text.length < min -> "Use at least $min characters"
                !name.text.all { it.isLetterOrDigit() || it == '_' || it == '-' } -> "Use letters, digits, _ and - only"
                snap.countries.any { it.name.equals(name.text, true) } -> "That name is taken"
                else -> null
            }
            val here = snap.detail?.takeIf { it.x == snap.px && it.z == snap.pz }
            val problem = here?.note?.takeIf { it.isNotEmpty() && it != "Free to claim." && it != "Nobody owns this land. Nothing can be built here." }
            when (s.step) {
                0 -> {
                    MiniMap.draw(this, Rect(b.x, b.y, 150, 150), snap.px, snap.pz, 6, "found", (-1..1).flatMap { dx -> (-1..1).map { dz -> snap.px + dx to snap.pz + dz } }, you = snap.px to snap.pz)
                    val f = Flow(Rect(b.x + 160, b.y, b.w - 160, 150), 4)
                    f.take(12).let { Draw.text(g, "Your capital", it.x, it.y, TextStyle.HEADING) }
                    property(f.take(11), "Chunk", "${snap.px}, ${snap.pz}", copy = true)
                    property(f.take(11), "Free land", Format.plural(snap.freeChunks, "chunk"), Palette.success)
                    f.skip(4)
                    f.take(Draw.paragraph(g, "This chunk becomes your capital. Claim extra land later from the map.", f.rest.x, f.rest.y, f.rest.w))
                    if (problem != null) callout(f.take(30), Severity.DANGER, problem)
                    s.used = 154
                }
                1 -> {
                    var y = b.y
                    fieldLabel(Rect(b.x, y, b.w, 9), "Country name", "${name.text.length}/$max"); y += 11
                    textField(Rect(b.x, y, b.w, CONTROL_H), name, "e.g. Riverhold", Icons.FLAG, maxLength = max, allow = { it.isLetterOrDigit() || it == '_' || it == '-' }, key = "name", autoFocus = true); y += CONTROL_H + 2
                    name.touched = name.text.isNotEmpty()
                    fieldHelp(Rect(b.x, y, b.w, 9), name, "Shown on map and chat · $min-$max chars."); y += 16
                    Draw.text(g, "Color and flag can be set later.", b.x, y, Palette.textMuted); y += 14
                    s.used = y - b.y
                }
                2 -> {
                    val cards = listOf(
                        Triple(Icons.AREA, "Land", "First ${snap.freeChunks} chunks are free. Extra chunks add upkeep."),
                        Triple(Icons.TREASURY, "Treasury", "Upkeep is paid from treasury. Keep runway positive."),
                        Triple(Icons.PEOPLE, "Citizens", "Invite members. Officers and chancellor help manage."),
                        Triple(Icons.SHIELD, "Protection", "Set break/place/use/open rules per land type.")
                    )
                    var y = b.y
                    cards.forEach { (icon, head, text) ->
                        Draw.icon(g, icon, b.x, y)
                        Draw.text(g, head, b.x + 22, y + 1, TextStyle.HEADING)
                        y += 12 + Draw.paragraph(g, text, b.x + 22, y + 12, b.w - 22) + 2
                    }
                    checkbox(Rect(b.x, y, b.w, 14), "Show guided tour after founding", tour)?.let { tour = it }
                    s.used = y + 16 - b.y
                }
                else -> {
                    var y = b.y
                    Flags.draw(g, Rect(b.x, y, 36, 26), 0xB08D57, 0, 1, 0xFFFFFF)
                    Draw.text(g, name.text, b.x + 44, y + 4, TextStyle.TITLE)
                    Draw.text(g, "Capital at ${snap.px}, ${snap.pz}", b.x + 44, y + 15, Palette.textMuted); y += 34
                    y += consequences(b.x, y, b.w, listOf(
                        Consequence("You become president of ${name.text}.", Severity.SUCCESS),
                        Consequence("${snap.freeChunks} free chunks incl. capital.", Severity.SUCCESS),
                        Consequence("Extra land adds daily upkeep."),
                        Consequence("One country lead at a time.")
                    ))
                    s.used = y - b.y + 4
                }
            }
            val reason = when (s.step) {
                0 -> problem
                1 -> name.error
                else -> null
            }
            wizardButtons(s, "Found ${name.text.ifEmpty { "country" }}", reason == null, reason) {
                ClaimsStore.send("create", name.text, key = "create")
                kami.claims.client.ClientClaims.prefs.tourDone = !tour
                kami.claims.client.ClientClaims.savePrefs()
                s.close()
            }
        })
    }
}
