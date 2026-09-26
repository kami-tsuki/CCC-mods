package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Consequence
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Flags
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.app.consequences
import kami.claims.client.store.ClaimsStore
import kami.claims.net.ProvinceLine
import kami.claims.net.ProvinceOfferLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.DialogScope
import kami.libs.ui.app.Route
import kami.libs.ui.app.dialogButtons
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

class ProvincesPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Provinces"
    override val sections = listOf("world")
    override val help = listOf(
        Callout("provinces:status", "Status", "Independent, overlord, or province and what it means."),
        Callout("provinces:tabs", "Offers", "Incoming invites and province requests."),
        Callout("provinces:family", "Family", "Your provinces and tribute overview.")
    )

    private var tab = 0
    private var selected: String? = null

    override fun opened(route: kami.libs.ui.app.Route) {
        val focus = route.focus ?: return
        when {
            focus.startsWith("offer:") || focus.startsWith("request:") -> tab = 1
            focus.startsWith("province:") -> { tab = 0; selected = focus.substringAfter(":") }
        }
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val offers = info.provinceInvites.size + info.provinceRequests.size
        val tabsRect = r.top(20)
        ui.anchor("provinces:tabs", tabsRect)
        ui.subTabs(tabsRect, listOf(TabItem("Overview", Icons.CHAIN), TabItem("Offers & requests", Icons.SCROLL, offers, Severity.WARNING), TabItem("Start relation", Icons.HANDSHAKE)), tab, "province-tabs")?.let { tab = it }
        val body = r.dropTop(24)
        when (tab) {
            0 -> overview(ui, body)
            1 -> offers(ui, body)
            else -> start(ui, body)
        }
    }

    private fun overview(ui: Ui, r: Rect) {
        val info = info ?: return
        if (info.parent.isNotEmpty()) {
            val box = r.top(250)
            ui.anchor("provinces:status", box)
            provinceStatus(ui, box)
            return
        }
        if (info.provinces.isEmpty()) {
            ui.anchor("provinces:status", r.top(120))
            val start = ui.emptyState(r, "Independent", "Provinces pay tribute to an overlord. Overlords manage land/laws/jobs, not treasury/members. Provinces cannot leave on their own.",
                Illustrations.PROVINCE, "Invite or join", Icons.HANDSHAKE, "empty-provinces")
            if (start) tab = 2
            return
        }
        val tributes = info.provinces.sumOf { estimate(it.mode, it.amount, it.income) }
        val tiles = r.top(46).columns(4, 6)
        ui.statTile(tiles[0], "Provinces", "${info.provinces.size}", Icons.CHAIN)
        ui.statTile(tiles[1], "Tribute / day", "≈ ${Format.number(tributes)}", Icons.INCOME, Palette.success)
        ui.statTile(tiles[2], "Missed tribute", "${info.provinces.sumOf { it.debt }}", Icons.DEBT, if (info.provinces.any { it.debt > 0 }) Palette.danger else Palette.text)
        ui.statTile(tiles[3], "Want out", "${info.provinces.count { it.wantsIndependence }}", Icons.BROKEN_CHAIN, if (info.provinces.any { it.wantsIndependence }) Palette.warning else Palette.text)
        val rest = r.dropTop(52)
        val treeH = hierarchyHeight(rest.w, info.provinces.size).coerceAtMost(rest.h / 2)
        val tree = rest.top(treeH)
        ui.anchor("provinces:family", tree)
        val root = GraphNode(info.name, info.name, "overlord", badge = { g, b -> Flags.draw(g, b, info.color, info.flag.pattern, info.flag.emblem, info.flag.secondary) })
        val nodes = info.provinces.map { p ->
            GraphNode(p.name, p.name, when {
                p.wantsIndependence -> "wants independence"
                p.debt > 0 -> "missed tribute ${p.debt}x"
                else -> "${p.members} citizens · ${p.chunks} chunks"
            }, Vocabulary.tribute(p.mode, p.amount), if (p.debt > 0) Palette.danger else Palette.geoProvince, if (p.wantsIndependence) Severity.WARNING else if (p.debt > 0) Severity.DANGER else null) { g, b -> Flags.draw(g, b, p.color, p.flag.pattern, p.flag.emblem, p.flag.secondary) }
        }
        ui.clip(tree) { ui.hierarchy(tree, root, nodes, selected)?.let { if (it != info.name) selected = it } }
        val chosen = info.provinces.firstOrNull { it.name == selected } ?: info.provinces.firstOrNull()?.also { selected = it.name } ?: return
        provinceDetail(ui, rest.dropTop(treeH, 6), chosen)
    }

    private fun estimate(mode: String, amount: Double, income: Long) = if (mode == "percent") (income * amount).toLong() else amount.toLong()

    private fun provinceDetail(ui: Ui, r: Rect, p: ProvinceLine) {
        val body = ui.card(r, p.name, Icons.CHAIN, if (p.wantsIndependence) Severity.WARNING else null, key = "province-card")
        val cols = body.columns(2, 10)
        val f = Flow(cols[0], 2)
        ui.property(f.take(11), "Tribute", Vocabulary.tribute(p.mode, p.amount))
        ui.property(f.take(11), "Estimate", "≈ ${estimate(p.mode, p.amount, p.income)} ◎/day")
        ui.property(f.take(11), "Missed payments", "${p.debt} / ${snap.maxProvinceDebt}", if (p.debt > 0) Palette.danger else Palette.text)
        ui.property(f.take(11), "Citizens", "${p.members}")
        ui.property(f.take(11), "Land", Format.plural(p.chunks, "chunk"))
        if (p.wantsIndependence) {
            f.skip(4)
            f.take(ui.callout(f.rest, Severity.WARNING, "${p.name} asks for independence. If declined, they can ask again in ${limits?.independenceCooldownDays ?: 3} days."))
        }
        val g = Flow(cols[1], 3)
        val staff = lock("province")
        fun action(label: String, icon: kami.libs.ui.style.Icon, style: ButtonStyle = ButtonStyle.SECONDARY, key: String, run: () -> Unit) {
            if (ui.button(g.take(CONTROL_H), label, icon, style, staff == null, staff, pending = pending(key), key = "prov-$key:${p.name}")) run()
        }
        if (p.wantsIndependence) {
            action("Grant independence", Icons.BROKEN_CHAIN, ButtonStyle.PRIMARY, "province_release") { release(p, true) }
            action("Decline", Icons.CROSS, key = "province_decline") {
                Dialogs.confirm(app, "Decline independence", "${p.name} stays your province", Icons.CHAIN, listOf(
                    Consequence("${p.name} is told that you said no."),
                    Consequence("They can ask again in ${limits?.independenceCooldownDays ?: 3} days.", Severity.WARNING)
                ), "Decline", "province_decline", arrayOf(p.name))
            }
        }
        action("Manage ${p.name}", Icons.EDIT, if (p.wantsIndependence) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY, "view") { ClaimsStore.send("view", p.name); app.navigate(Route("dashboard")) }
        action("Change tribute", Icons.PERCENT, key = "province_tax") { termsDialog("Change tribute of ${p.name}", "province_tax", p.name, p.mode, p.amount, p.income) }
        if (p.debt > 0) action("Forgive debt", Icons.UNDO, key = "province_forgive") {
            Dialogs.confirm(app, "Forgive ${p.name}", null, Icons.UNDO, listOf(Consequence("Missed payments reset to 0."), Consequence("Unpaid coins are not recovered.", Severity.WARNING)), "Forgive", "province_forgive", arrayOf(p.name))
        }
        action("Give to another country", Icons.FORWARD, key = "province_give") { giveDialog(p) }
        if (!p.wantsIndependence) action("Release", Icons.BROKEN_CHAIN, ButtonStyle.DANGER, "province_release") { release(p, false) }
    }

    private fun release(p: ProvinceLine, requested: Boolean) {
        Dialogs.confirm(app, if (requested) "Grant independence to ${p.name}" else "Release ${p.name}", "They become independent", Icons.BROKEN_CHAIN, listOf(
            Consequence("Tribute stops: ~${estimate(p.mode, p.amount, p.income)} ◎/day.", Severity.WARNING),
            Consequence("Mutual province-alliance ends.", Severity.WARNING),
            Consequence("You can't manage their land, laws or jobs anymore."),
            Consequence("Only they can decide to become a province again.", Severity.DANGER)
        ), if (requested) "Grant independence" else "Release ${p.name}", "province_release", arrayOf(p.name), danger = true, hold = true)
    }

    private fun giveDialog(p: ProvinceLine) {
        var target: String? = null
        val typed = TextState()
        app.open(Dialog("Transfer ${p.name}", "to another overlord", Icons.FORWARD, DialogKind.DESTRUCTIVE, 320) { s ->
            val candidates = snap.countries.filter { it.parent.isEmpty() && it.name != p.name && it.name != info?.name }
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "New overlord"); y += 11
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), candidates.map { Option(it.name, it.name, null, "${it.members} citizens · ${it.chunks} chunks", it.color) }, target, "Choose a country", key = "give-target")?.let { target = it }
            y += CONTROL_H + 6
            y += consequences(s.body.x, y, s.body.w, listOf(
                Consequence("${target ?: "The new overlord"} takes over the tribute and the right to manage ${p.name}.", Severity.WARNING),
                Consequence("You can't take it back.", Severity.DANGER)
            )) + 4
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Type ${p.name}"); y += 11
            textField(Rect(s.body.x, y, s.body.w, CONTROL_H), typed, p.name, key = "give-typed"); y += CONTROL_H + 4
            s.used = y - s.body.y
            val ready = target != null && typed.text.equals(p.name, true)
            dialogButtons(s, "Transfer", ready, if (target == null) "Choose a country first" else "Type required", hold = true) {
                ClaimsStore.send("province_give", p.name, target!!, "confirm", key = "province_give")
                s.close()
            }
        })
    }

    private fun provinceStatus(ui: Ui, r: Rect) {
        val info = info ?: return
        val body = ui.card(r, "Province of ${info.parent}", Icons.CHAIN, Severity.WARNING, key = "status")
        val f = Flow(body, 3)
        val top = f.take(34)
        Flags.draw(ui.g, Rect(top.x, top.y + 2, 36, 26), info.parentColor, 0, 0, 0xFFFFFF)
        Draw.text(ui.g, "Tribute: ${Vocabulary.tribute(info.taxMode, info.taxAmount)}", top.x + 44, top.y + 4, TextStyle.HEADING)
        Draw.text(ui.g, "~${estimate(info.taxMode, info.taxAmount, info.income)} ◎/day · missed ${info.provinceDebt}/${snap.maxProvinceDebt}", top.x + 44, top.y + 16, if (info.provinceDebt > 0) Palette.danger else Palette.textMuted)
        val cols = f.take(100).columns(2, 10)
        rightsList(ui, cols[0], "${info.parent} may", snap.delegable, Palette.warning, Icons.WARNING)
        rightsList(ui, cols[1], "You keep", snap.kept, Palette.success, Icons.CHECK)
        f.take(ui.callout(f.rest, Severity.DANGER, "You cannot leave by yourself. Only ${info.parent} can release or grant independence."))
        val row = f.take(CONTROL_H)
        val staff = lock("province")
        when {
            info.independenceRequested -> {
                ui.statusPill(row.x, row.y + 4, "Request sent, waiting for ${info.parent}", Severity.WARNING)
                val w = buttonWidth("Withdraw request")
                if (ui.button(Rect(row.right - w, row.y, w, CONTROL_H), "Withdraw request", enabled = staff == null, disabledReason = staff, pending = pending("province_withdraw"), key = "withdraw-indep")) act("province_withdraw")
            }
            info.independenceCooldown > 0 -> ui.statusPill(row.x, row.y + 4, "Declined. You can ask again in ${Format.duration(info.independenceCooldown)}", Severity.DANGER)
            else -> {
                val w = buttonWidth("Request independence", Icons.BROKEN_CHAIN)
                if (ui.button(Rect(row.x, row.y, w, CONTROL_H), "Request independence", Icons.BROKEN_CHAIN, ButtonStyle.PRIMARY, staff == null, staff, pending = pending("province_independence"), key = "req-indep")) {
                    Dialogs.confirm(app, "Ask ${info.parent} for independence", "This is only a request", Icons.BROKEN_CHAIN, listOf(
                        Consequence("${info.parent} decides. Nothing changes until approved."),
                        Consequence("If they decline, you must wait ${limits?.independenceCooldownDays ?: 3} days before asking again.", Severity.WARNING)
                    ), "Send request", "province_independence", emptyArray())
                }
            }
        }
    }

    private fun rightsList(ui: Ui, r: Rect, title: String, items: List<String>, color: Int, icon: kami.libs.ui.style.Icon) {
        Draw.fill(ui.g, r, Palette.alpha(color, 0x14))
        Draw.text(ui.g, title.uppercase(), r.x + 5, r.y + 4, color)
        var y = r.y + 16
        items.forEach { text ->
            Draw.icon(ui.g, icon, r.x + 3, y - 3, 12)
            y += Draw.paragraph(ui.g, text.replaceFirstChar { it.uppercase() }, r.x + 17, y, r.w - 20) + 2
        }
    }

    private fun offers(ui: Ui, r: Rect) {
        val info = info ?: return
        val f = Flow(r, 6)
        ui.section(f.take(14), "Invites to become a province", "${info.provinceInvites.size}")
        if (info.provinceInvites.isEmpty()) f.take(12).let { Draw.text(ui.g, "No invites right now.", it.x, it.y, Palette.textMuted) }
        info.provinceInvites.forEach { o ->
            val row = f.take(34)
            val focus = app.isFocus("offer:${o.name}")
            Draw.sprite(ui.g, Sprites.CARD, row)
            ui.attention(row, focus)
            Flags.draw(ui.g, Rect(row.x + 6, row.y + 8, 24, 17), o.color, 0, 0, 0xFFFFFF)
            Draw.text(ui.g, if (o.answered) "${o.name} accepted your request" else "${o.name} invites you", row.x + 38, row.y + 6, TextStyle.HEADING)
            Draw.text(ui.g, "${Vocabulary.tribute(o.mode, o.amount)} · ${o.members} citizens · expires ${Format.until(o.until)}", row.x + 38, row.y + 19, Palette.textMuted)
            val w = buttonWidth("Review terms", Icons.SCROLL)
            if (ui.button(Rect(row.right - w - 6, row.y + 8, w, 18), "Review terms", Icons.SCROLL, ButtonStyle.PRIMARY, lock("province") == null, lock("province"), key = "review:${o.name}")) agreement(o)
        }
        f.skip(6)
        ui.section(f.take(14), "Requests to become your province", "${info.provinceRequests.size}")
        if (info.parent.isNotEmpty()) { f.take(12).let { Draw.text(ui.g, "A province can't have provinces of its own.", it.x, it.y, Palette.textMuted) }; return }
        if (info.provinceRequests.isEmpty()) f.take(12).let { Draw.text(ui.g, "No requests right now.", it.x, it.y, Palette.textMuted) }
        info.provinceRequests.forEach { name ->
            val line = snap.countries.firstOrNull { it.name == name }
            val row = f.take(30)
            Draw.sprite(ui.g, Sprites.CARD, row)
            ui.attention(row, app.isFocus("request:$name"))
            Flags.draw(ui.g, Rect(row.x + 6, row.y + 7, 22, 16), line?.color ?: 0x888888, line?.flag?.pattern ?: 0, line?.flag?.emblem ?: 0, line?.flag?.secondary ?: 0xFFFFFF)
            Draw.text(ui.g, name, row.x + 36, row.y + 5, TextStyle.HEADING)
            Draw.text(ui.g, line?.let { "${it.members} citizens · ${it.chunks} chunks" } ?: "", row.x + 36, row.y + 17, Palette.textMuted)
            val staff = lock("province")
            if (ui.button(Rect(row.right - 150, row.y + 6, 80, 18), "Set terms", Icons.PERCENT, ButtonStyle.PRIMARY, staff == null, staff, key = "terms:$name")) termsDialog("Terms for $name", "province_approve", name, "percent", 0.1, line?.income ?: 0)
            if (ui.button(Rect(row.right - 66, row.y + 6, 60, 18), "Deny", enabled = staff == null, disabledReason = staff, key = "deny:$name")) act("province_deny", name)
        }
    }

    private fun termsDialog(title: String, action: String, target: String, mode0: String, amount0: Double, income: Long) {
        var mode = mode0
        var percent = (amount0 * 100).coerceIn(0.0, 100.0)
        val flat = NumberState(if (mode0 == "flat") amount0.toLong() else 10)
        app.open(Dialog(title, if (action == "province_approve") "They must sign before it takes effect" else null, Icons.PERCENT, DialogKind.CONFIRM, 320) { s ->
            val limits = snap.limits
            val min = (limits?.tributeMin ?: 0.0) * 100
            val max = (limits?.tributeMax ?: 0.5) * 100
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Tribute type"); y += 11
            segmented(Rect(s.body.x, y, s.body.w, CONTROL_H), listOf(
                Option("percent", "Share of plot tax", Icons.PERCENT, "A share of the province's daily plot tax income."),
                Option("flat", "Fixed amount", Icons.COIN, "The same amount every day, no matter how much they earn.")
            ), mode, key = "mode")?.let { mode = it }
            y += CONTROL_H + 8
            if (mode == "percent") {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Share", "${percent.toInt()}%"); y += 11
                slider(Rect(s.body.x, y, s.body.w, CONTROL_H), percent.coerceIn(min, max), min, max, 1.0, format = { "${it.toInt()}%" }, key = "percent")?.let { percent = it }
            } else {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Per day"); y += 11
                numberField(Rect(s.body.x, y, s.body.w, CONTROL_H), flat, 0, 100_000, unit = "◎", key = "flat")
            }
            y += CONTROL_H + 6
            val estimate = if (mode == "percent") (income * percent / 100).toLong() else flat.value
            property(Rect(s.body.x, y, s.body.w, 11), "Today that is", "≈ $estimate ◎ per day", Palette.money); y += 14
            y += consequences(s.body.x, y, s.body.w, listOf(
                Consequence("Unpaid tribute counts as missed payments. You decide what happens then."),
                Consequence("You can change the tribute later.")
            ))
            s.used = y - s.body.y + 4
            dialogButtons(s, if (action == "province_approve") "Send terms" else "Save tribute") {
                ClaimsStore.send(action, target, mode, if (mode == "percent") percent.toInt().toString() else flat.value.toString(), key = action)
                s.close()
            }
        })
    }

    private fun agreement(o: ProvinceOfferLine) {
        var understood = false
        val typed = TextState()
        app.open(Dialog("Become a province of ${o.name}", "${info?.name} gives up authority", Icons.CHAIN, DialogKind.DESTRUCTIVE, 400, listOf("Terms", "Authority", "Sign"),
            stale = { if (ClaimsStore.info?.provinceInvites?.none { it.name == o.name } == true) "This offer is no longer open." else null }) { s ->
            val info = ClaimsStore.info ?: return@Dialog
            val b = s.body
            var y = b.y
            when (s.step) {
                0 -> {
                    g.blitSprite(Illustrations.PROVINCE, b.x, y, 48, 48)
                    Draw.paragraph(g, "${o.name} offers to make ${info.name} its province. Read every step. This decision can only be undone by ${o.name}.", b.x + 56, y + 4, b.w - 56)
                    y += 54
                    property(Rect(b.x, y, b.w, 11), "Tribute", Vocabulary.tribute(o.mode, o.amount), Palette.money); y += 13
                    property(Rect(b.x, y, b.w, 11), "Today that is", "≈ ${estimate(o.mode, o.amount, info.income)} ◎ per day (your plot tax: ${info.income} ◎)", Palette.money); y += 13
                    property(Rect(b.x, y, b.w, 11), "If you can't pay", "missed payments add up, ${o.name} decides", Palette.warning); y += 13
                    property(Rect(b.x, y, b.w, 11), "Offer expires", Format.until(o.until)); y += 17
                    if (info.provinces.isNotEmpty()) y += callout(Rect(b.x, y, b.w, 0), Severity.WARNING, "Your ${Format.plural(info.provinces.size, "province")} become direct provinces of ${o.name}.") + 4
                }
                1 -> {
                    Draw.fill(g, Rect(b.x, y, b.w, 16), Palette.alpha(Palette.danger, 0x30))
                    Draw.icon(g, Icons.WARNING, b.x + 2, y)
                    Draw.text(g, "YOU ARE GIVING UP AUTHORITY", b.x + 22, y + 4, TextStyle.TITLE, Palette.danger)
                    y += 22
                    val cols = Rect(b.x, y, b.w, 118).columns(listOf(1.2f, 1f), 8)
                    rightsList(this, cols[0], "${o.name} may", snap.delegable, Palette.danger, Icons.WARNING)
                    rightsList(this, cols[1], "You keep", snap.kept, Palette.success, Icons.CHECK)
                    y += 124
                    y += callout(Rect(b.x, y, b.w, 0), Severity.DANGER, "You can't leave on your own. Only ${o.name} can release you or grant independence. You may ask, they may say no.") + 4
                    y += callout(Rect(b.x, y, b.w, 0), Severity.INFO, "Your citizens become allies of ${o.name} and its other provinces, and theirs become yours.") + 6
                    checkbox(Rect(b.x, y, b.w, 14), "I understand that ${info.name} loses authority and can't leave by itself", understood, key = "understood")?.let { understood = it }
                    y += 18
                }
                else -> {
                    Draw.paragraph(g, "To sign, type the name of your country and hold the button.", b.x, y, b.w); y += 16
                    fieldLabel(Rect(b.x, y, b.w, 9), "Type ${info.name}"); y += 11
                    textField(Rect(b.x, y, b.w, CONTROL_H), typed, info.name, key = "sign-name", autoFocus = true); y += CONTROL_H + 6
                    y += consequences(b.x, y, b.w, listOf(Consequence("All officers of both countries get a message with these terms."))) + 2
                }
            }
            s.used = y - b.y
            val reason = when (s.step) {
                1 -> if (!understood) "Tick the box to confirm you understand" else null
                2 -> if (!typed.text.equals(info.name, true)) "Type ${info.name} first" else null
                else -> null
            }
            wizardButtons(s, "Hold to sign", reason == null, reason, hold = s.step == 2) {
                ClaimsStore.send("province_accept", o.name, "confirm", key = "province_accept")
                s.close()
            }
        })
    }

    private fun start(ui: Ui, r: Rect) {
        val info = info ?: return
        val cards = r.top(150).columns(2, 10)
        val staff = lock("province")
        val isProvince = info.parent.isNotEmpty()
        val a = ui.card(cards[0], "Invite a country", Icons.INVITE)
        Draw.paragraph(ui.g, "Offer another country to become your province. You set the tribute. They see the full terms and must sign.", a.x, a.y, a.w)
        if (ui.button(Rect(a.x, a.bottom - CONTROL_H, a.w, CONTROL_H), "Invite a country", Icons.INVITE, ButtonStyle.PRIMARY, staff == null && !isProvince, staff ?: "A province can't have provinces", key = "invite-country")) inviteDialog()
        val b = ui.card(cards[1], "Ask to join a country", Icons.CHAIN, Severity.WARNING)
        Draw.paragraph(ui.g, "Ask another country to take you as its province. They answer with terms. Nothing changes until you sign them.", b.x, b.y, b.w)
        if (ui.button(Rect(b.x, b.bottom - CONTROL_H, b.w, CONTROL_H), "Ask a country", Icons.CHAIN, enabled = staff == null && !isProvince, disabledReason = staff ?: "You already are a province", key = "request-country")) requestDialog()
        val explain = r.dropTop(160)
        ui.card(explain, "How provinces work", Icons.INFO).let { c ->
            var y = c.y
            listOf(
                "Provinces are normal countries with their own president, treasury, land and citizens.",
                "They pay tribute every day to their overlord, either a share of plot tax or a fixed amount.",
                "The overlord may manage their land, capital, taxes, laws and jobs, but never their treasury or members.",
                "Citizens of the whole family are allies of each other automatically.",
                "A province can't leave by itself. It can ask for independence, and the overlord decides."
            ).forEach { line ->
                Draw.icon(ui.g, Icons.CHEVRON_RIGHT, c.x - 2, y - 3, 12)
                y += Draw.paragraph(ui.g, line, c.x + 12, y, c.w - 12) + 3
            }
        }
    }

    private fun inviteDialog() {
        var target: String? = null
        app.open(Dialog("Invite a country as province", null, Icons.INVITE, DialogKind.CONFIRM, 300) { s ->
            val candidates = snap.countries.filter { it.parent.isEmpty() && it.name != info?.name }
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Country"); y += 11
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), candidates.map { Option(it.name, it.name, null, "${it.members} citizens · ${it.chunks} chunks", it.color) }, target, "Choose a country", key = "invite-target")?.let { target = it }
            y += CONTROL_H + 8
            y += consequences(s.body.x, y, s.body.w, listOf(Consequence("Next you choose the tribute. They get a few days to read and sign the terms."))) + 4
            s.used = y - s.body.y
            dialogButtons(s, "Next: tribute", target != null, "Choose a country first") {
                s.close()
                val line = snap.countries.firstOrNull { it.name == target }
                termsDialog("Tribute for ${target}", "province_invite", target!!, "percent", 0.1, line?.income ?: 0)
            }
        })
    }

    private fun requestDialog() {
        var target: String? = null
        app.open(Dialog("Ask to become a province", null, Icons.CHAIN, DialogKind.CONFIRM, 320) { s ->
            val candidates = snap.countries.filter { it.parent.isEmpty() && it.name != info?.name }
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Overlord"); y += 11
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), candidates.map { Option(it.name, it.name, null, "${it.members} citizens · ${it.chunks} chunks", it.color) }, target, "Choose a country", key = "request-target")?.let { target = it }
            y += CONTROL_H + 8
            y += consequences(s.body.x, y, s.body.w, listOf(
                Consequence("${target ?: "They"} answer with tribute terms."),
                Consequence("Nothing changes until you read and sign those terms.", Severity.SUCCESS)
            )) + 4
            s.used = y - s.body.y
            dialogButtons(s, "Send request", target != null, "Choose a country first") {
                ClaimsStore.send("province_request", target!!, key = "province_request")
                s.close()
            }
        })
    }
}
