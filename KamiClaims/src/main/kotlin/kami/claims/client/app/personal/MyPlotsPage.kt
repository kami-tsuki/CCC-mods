package kami.claims.client.app.personal

import kami.claims.client.ClientHooks
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Tenure
import kami.claims.client.app.Vocabulary
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.net.HomeLine
import kami.claims.net.Snap
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val CARD_H = 38
private const val SKELETON_CARDS = 3
private const val SKELETON_MAP = 66
private const val DETAIL_RETRY_MS = 1500L

private fun keyOf(h: HomeLine) = key(h.dim, h.x, h.z)
private fun key(dim: String, x: Int, z: Int) = "$dim|$x|$z"

class MyPlotsPage(private val host: KamiApp) : Page() {
    override val title get() = tr("kami_claims.personal.plots")

    private var focus: String? = null
    private val trustName = TextState()
    private var trustRole = "household"
    private val cards = Memo()
    private var asked = 0L
    private var detailH = 0

    init {
        ClaimsStore.onOutcome { if (it.ok && it.key == "plot_trust") trustName.set("") }
    }

    private class Card(val home: HomeLine, val title: String, val rent: String, val state: String, val severity: Severity, val note: String, val color: Int, val pillKey: String)

    override fun opened(route: Route) {
        val (x, z) = route.focus?.split(':')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 2 } ?: return
        focus = key(route.param("dim") ?: ClaimsStore.snap?.dim.orEmpty(), x, z)
    }

    override fun draw(ui: Ui, r: Rect) {
        val snap = ClaimsStore.snap
        val view = ClaimsPersonal.view()
        if (snap == null || view == null) return skeleton(ui, r)
        val homes = view.homes
        if (homes.isEmpty()) {
            if (ui.emptyState(r, tr("kami_claims.plots.empty.mine.title"), tr("kami_claims.plots.empty.mine.desc"), Illustrations.CITIZENS, tr("kami_claims.plots.empty.mine.action").takeUnless { Personal.viewing }, Icons.HOUSE)) {
                ClaimsApp.instance.navigate(Route("plots", focus = "free"))
                ClientHooks.openApp()
            }
            return
        }
        val chosen = homes.firstOrNull { keyOf(it) == focus } ?: homes.first().also { focus = keyOf(it) }
        val (list, detail) = r.columns(listOf(1f, 1.3f), 8)
        list(ui, list, homes, chosen)
        detail(ui, detail, snap, chosen)
    }

    private fun skeleton(ui: Ui, r: Rect) {
        val (list, detail) = r.columns(listOf(1f, 1.3f), 8)
        repeat(SKELETON_CARDS) { ui.skeletonTile(Rect(list.x, list.y + it * (CARD_H + 4), list.w, CARD_H)) }
        Draw.sprite(ui.g, Sprites.CARD, detail)
        val body = detail.inset(8)
        ui.skeleton(body.top(SKELETON_MAP).withWidth(SKELETON_MAP))
        ui.skeletonLines(body.dropTop(SKELETON_MAP + 8), 5)
    }

    private fun list(ui: Ui, r: Rect, homes: List<HomeLine>, chosen: HomeLine) {
        val lines = cards.of(homes, Format.locale, System.currentTimeMillis() / 60_000) {
            homes.map {
                Card(it, tr("kami_claims.home.where", it.country, it.x, it.z), Format.perDay(Format.money(it.rent.toLong())), Tenure.label(it.state), Tenure.severity(it.state, it.debt), Tenure.note(it, it.state, it.until, it.debt), Tenure.color(it.state, it.debt), "plot-state:${keyOf(it)}")
            }
        }
        ui.scroll("my-plots", r, lines.size * (CARD_H + 4)) { area ->
            lines.forEachIndexed { i, l ->
                val h = l.home
                val card = Rect(area.x, area.y + i * (CARD_H + 4), area.w, CARD_H)
                val picked = h === chosen
                Draw.sprite(ui.g, if (picked) Sprites.CARD_HOVER else Sprites.CARD, card)
                if (picked) Draw.fill(ui.g, card.left(2), Palette.brass)
                ui.attention(card, host.isFocus("${h.x}:${h.z}"))
                ui.fitText(card.x + 6, card.y + 5, card.w - 80, l.title, style = TextStyle.HEADING)
                Draw.textRight(ui.g, l.rent, card.right - 6, card.y + 5, Palette.money)
                val pill = ui.statusPill(card.x + 6, card.y + 19, l.state, l.severity, key = l.pillKey)
                ui.fitText(card.x + 12 + pill, card.y + 22, card.w - pill - 20, l.note, l.color)
                if (ui.pressed(card) != null) focus = keyOf(h)
            }
        }
    }

    private fun detail(ui: Ui, r: Rect, snap: Snap, c: HomeLine) {
        val near = c.dim == snap.dim
        val moving = c.state == Tenure.MOVING
        val body = ui.card(r, tr("kami_claims.home.where", c.country, c.x, c.z), Icons.HOUSE, if (moving) Severity.DANGER else if (c.debt > 0) Severity.WARNING else null)
        val row = Row(body.bottom(CONTROL_H), 4)
        val lock = ClaimsPersonal.readOnly()
        if (ui.button(row.takeFromRight(row.rest.w / 2), tr("kami_claims.plots.release"), Icons.REMOVE, ButtonStyle.SECONDARY, lock == null, lock, key = "plot-release")) Dialogs.release(host, c.x, c.z, c.country, moving)
        if (ui.button(row.remaining(), tr("kami_claims.toast.show_on_map"), Icons.MAP, enabled = near, disabledReason = tr("kami_claims.personal.plots.other_dim"), key = "plot-map")) {
            ClaimsApp.instance.openMapAt(c.x, c.z)
            ClientHooks.openApp()
        }
        ui.scroll("plot-detail", body.dropBottom(CONTROL_H, 4), detailH) { area ->
            val f = Flow(Rect(area.x, area.y, area.w, Int.MAX_VALUE / 2), 4)
            Tenure.hint(c.state)?.let { hint ->
                if (moving && c.until > 0) Draw.text(ui.g, Tenure.countdown(c.until), f.rest.x, f.rest.y, TextStyle.HEADING, Palette.danger).also { f.skip(14) }
                f.take(Draw.paragraph(ui.g, hint, f.rest.x, f.rest.y, f.rest.w, if (moving) Palette.danger else Palette.warning))
            }
            val top = f.take(66)
            if (near) MiniMap.draw(ui, top.left(66), c.x, c.z, 2, "plot", listOf(c.x to c.z))
            val props = Flow(if (near) top.dropLeft(74) else top, 3)
            ui.property(props.take(11), tr("kami_libs.flow.rent"), Format.perDay(Format.money(c.rent.toLong())))
            ui.property(props.take(11), tr("kami_libs.common.state"), Tenure.label(c.state), Tenure.severity(c.state, c.debt).color)
            if (c.debt > 0) ui.property(props.take(11), tr("kami_claims.common.rent_debt"), Format.money(c.debt), Palette.danger)
            else if (c.credit > 0) ui.property(props.take(11), tr("kami_claims.personal.debts.credit"), Format.money(c.credit), Palette.paid)
            if (c.nextBill > 0) ui.property(props.take(11), tr("kami_claims.personal.debts.next_bill"), Format.until(ClaimsStore.receivedAt + c.nextBill))
            ui.section(f.take(14), tr("kami_claims.plots.access"))
            if (near) access(ui, f, snap, c) else ui.fitText(f.rest.x, f.take(12).y, f.rest.w, tr("kami_claims.personal.plots.other_dim"), Palette.textMuted)
            detailH = f.rest.y - area.y
        }
    }

    private fun access(ui: Ui, f: Flow, snap: Snap, c: HomeLine) {
        val d = snap.detail?.takeIf { it.x == c.x && it.z == c.z }
        val now = System.currentTimeMillis()
        if (d == null && now - asked > DETAIL_RETRY_MS) {
            asked = now
            ClaimsStore.quiet("chunk", c.x.toString(), c.z.toString())
        }
        val roles = d?.roles.orEmpty()
        if (roles.isEmpty()) f.take(12).let { Draw.text(ui.g, tr("kami_claims.plots.access.empty"), it.x, it.y, Palette.textMuted) }
        roles.forEach { line ->
            val row = f.take(14)
            val name = line.substringBefore(":")
            val role = line.substringAfter(": ")
            val roleLabel = tr("kami_claims.role.$role")
            val nameW = minOf(96, row.w - Draw.width(roleLabel) - 34).coerceAtLeast(0)
            ui.fitText(row.x, row.y + 3, nameW, name, Palette.text)
            ui.chip(row.x + nameW + 4, row.y, roleLabel, Vocabulary.standing(role).color)
            val key = "plot_untrust:$name"
            if (!Personal.viewing && ui.iconButton(Rect(row.right - 16, row.y - 1, 16, 16), Icons.REMOVE, tr("kami_libs.common.remove_x", name), !ClaimsStore.isPending(key), key = "untrust:$name"))
                Dialogs.confirm(host, tr("kami_claims.plots.untrust.title", name), tr("kami_claims.home.where", c.country, c.x, c.z), Icons.REMOVE,
                    listOf(Consequence(tr("kami_claims.plots.untrust.body", roleLabel), Severity.WARNING)), tr("kami_libs.common.remove_x", name), "plot_untrust",
                    arrayOf(name, c.x.toString(), c.z.toString()), danger = true, key = key)
        }
        if (Personal.viewing) return
        f.skip(4)
        ui.section(f.take(14), tr("kami_claims.plots.access.add"))
        val roleOptions = listOf(
            Option("household", tr("kami_claims.role.household"), Icons.HOUSE, tr("kami_claims.role.household.desc")),
            Option("allied", tr("kami_claims.role.allied"), Icons.HANDSHAKE, tr("kami_claims.role.allied.desc")),
            Option("banished", tr("kami_claims.role.banished"), Icons.BAN, tr("kami_claims.role.banished.desc"))
        )
        val entry = f.take(CONTROL_H)
        ui.playerField(entry.left(entry.w - 90), trustName, { snap.names }, { ClaimsStore.quiet("names", it) }, key = "trust-name")
        if (ui.button(entry.right(86), tr("kami_libs.common.add"), Icons.ADD, ButtonStyle.PRIMARY, trustName.text.length >= 3, tr("kami_claims.field.player.disabled"), pending = ClaimsStore.isPending("plot_trust"), key = "trust-go")) {
            ClaimsStore.send("plot_trust", trustName.text, trustRole, c.x.toString(), c.z.toString(), key = "plot_trust")
        }
        ui.radioGroup(f.take(radioGroupHeight(roleOptions, entry.w)), roleOptions, trustRole, key = "trust-role")?.let { trustRole = it }
    }
}
