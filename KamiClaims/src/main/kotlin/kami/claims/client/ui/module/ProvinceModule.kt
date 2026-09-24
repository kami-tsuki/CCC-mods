package kami.claims.client.ui.module

import kami.claims.client.ui.*
import kami.claims.net.Info
import kami.claims.net.ProvinceLine
import kami.claims.net.ProvinceOfferLine
import net.minecraft.client.gui.GuiGraphics

class ProvinceModule(s: ClaimsScreen) : Module(s) {
    private var mode = 0
    private var flat = false
    private var amount = "10"
    private var inviteName = ""
    private var requestName = ""
    private var giveTarget = ""

    private val provinces = ScrollList<ProvinceLine>(0, 0, 0, 0) { g, pr, x, y, w, _ ->
        g.drawString(s.text, Ui.fit(pr.name, 100), x, y + 3, Ui.TEXT, false)
        g.drawString(s.text, term(pr.mode, pr.amount), x + 106, y + 3, Ui.GOLD, false)
        val flags = listOfNotNull(if (pr.debt > 0) "debt ${pr.debt}" else null, if (pr.wantsIndependence) "wants independence" else null)
        g.drawString(s.text, flags.joinToString("  "), x + 176, y + 3, if (pr.debt > 0) Ui.BAD else Ui.WARN, false)
    }
    private val invites = ScrollList<ProvinceOfferLine>(0, 0, 0, 0) { g, o, x, y, _, _ ->
        g.drawString(s.text, Ui.fit(o.name, 100), x, y + 3, Ui.TEXT, false)
        g.drawString(s.text, term(o.mode, o.amount), x + 106, y + 3, Ui.GOLD, false)
    }
    private val requests = ScrollList<String>(0, 0, 0, 0) { g, n, x, y, _, _ -> g.drawString(s.text, n, x, y + 3, Ui.TEXT, false) }

    private fun term(mode: String, amt: Double) = if (mode == "percent") "${(amt * 100).toInt()}%" else "${amt.toInt()} spur/day"
    private val list get() = when (mode) { 0 -> provinces; 1 -> invites; else -> requests }

    override fun snapshot() {
        val i = s.info ?: return
        provinces.items = i.provinces
        invites.items = i.provinceInvites
        requests.items = i.provinceRequests
    }

    override fun init() {
        val i = s.info ?: return
        val x = s.ax
        val y = s.ay
        val staff = s.can("province")
        listOf("Status" to 0, "Invites" to 1, "Requests" to 2).forEach { (label, m) -> s.btn(label, x + m * 84, y, 80, mode != m) { mode = m; s.show(ClaimsScreen.Tab.PROVINCES) } }
        snapshot()
        val rx = x + s.aw - 144
        when (mode) {
            0 -> {
                if (i.parent.isNotEmpty()) s.btn(if (i.independenceRequested) "Independence requested" else "Request independence", x, y + 24, s.aw - 150, staff) { s.act("province_independence") }
                provinces.x = x; provinces.y = y + 48; provinces.w = s.aw - 150; provinces.h = s.ah - 48
                val sel = { provinces.current }
                s.btn("Manage", rx, y + 24, 144, staff && sel() != null, "View and edit this province's claims, rules and jobs") { sel()?.let { s.act("view", it.name) } }
                s.btn("Release", rx, y + 44, 144, staff && sel() != null) { sel()?.let { s.act("province_release", it.name) } }
                s.btn("Forgive debt", rx, y + 64, 144, staff && sel() != null) { sel()?.let { s.act("province_forgive", it.name) } }
                s.btn(if (flat) "Flat" else "Percent", rx, y + 86, 70, staff && sel() != null) { flat = !flat; s.show(ClaimsScreen.Tab.PROVINCES) }
                s.edit(rx + 74, y + 86, 70, amount, 8, "Amount") { amount = it }
                s.btn("Set tax", rx, y + 106, 144, staff && sel() != null) { sel()?.let { s.act("province_tax", it.name, if (flat) "flat" else "percent", amount) } }
                s.edit(rx, y + 128, 144, giveTarget, 24, "Give to country") { giveTarget = it }
                s.btn("Give", rx, y + 148, 144, staff && sel() != null && giveTarget.isNotBlank()) { sel()?.let { s.act("province_give", it.name, giveTarget) } }
            }
            1 -> {
                invites.x = x; invites.y = y + 24; invites.w = s.aw - 150; invites.h = s.ah - 24
                s.btn("Accept", rx, y + 24, 144, invites.current != null) { invites.current?.let { s.act("province_accept", it.name) } }
                if (i.parent.isEmpty()) {
                    s.edit(x, y + s.ah - 60, s.aw - 150, inviteName, 24, "Country to invite") { inviteName = it }
                    s.btn(if (flat) "Flat" else "Percent", x, y + s.ah - 38, 70, staff) { flat = !flat; s.show(ClaimsScreen.Tab.PROVINCES) }
                    s.edit(x + 76, y + s.ah - 38, 70, amount, 8, "Amount") { amount = it }
                    s.btn("Send invite", x + 152, y + s.ah - 38, s.aw - 150 - 152, staff && inviteName.isNotBlank()) { s.act("province_invite", inviteName, if (flat) "flat" else "percent", amount) }
                }
            }
            else -> {
                requests.x = x; requests.y = y + 24; requests.w = s.aw - 150; requests.h = s.ah - 24
                s.btn(if (flat) "Flat" else "Percent", rx, y + 24, 70, staff && requests.current != null) { flat = !flat; s.show(ClaimsScreen.Tab.PROVINCES) }
                s.edit(rx + 74, y + 24, 70, amount, 8, "Amount") { amount = it }
                s.btn("Approve", rx, y + 44, 144, staff && requests.current != null) { requests.current?.let { s.act("province_approve", it, if (flat) "flat" else "percent", amount) } }
                s.btn("Deny", rx, y + 64, 144, staff && requests.current != null) { requests.current?.let { s.act("province_deny", it) } }
                if (i.parent.isEmpty()) {
                    s.edit(x, y + s.ah - 38, s.aw - 150, requestName, 24, "Country to request") { requestName = it }
                    s.btn("Send request", x, y + s.ah - 18, s.aw - 150, staff && requestName.isNotBlank()) { s.act("province_request", requestName) }
                }
            }
        }
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        val i = s.info ?: return
        val y = s.ay
        when (mode) {
            0 -> {
                if (i.parent.isNotEmpty()) {
                    s.label(g, "Province of ${i.parent}  ${term(i.taxMode, i.taxAmount)}  debt ${i.provinceDebt}/${s.snap.maxProvinceDebt}", s.ax, y + 40, if (i.provinceDebt > 0) Ui.BAD else Ui.DIM)
                } else s.label(g, "Independent", s.ax, y + 40, Ui.GOOD)
                provinces.draw(g, mx, my)
                if (i.provinces.isEmpty()) s.label(g, "No provinces of your own yet.", s.ax, y + 52, Ui.DIM)
            }
            1 -> {
                invites.draw(g, mx, my)
                if (invites.items.isEmpty()) s.label(g, "No invitations.", s.ax, y + 28, Ui.DIM)
            }
            else -> {
                requests.draw(g, mx, my)
                if (requests.items.isEmpty()) s.label(g, "No requests.", s.ax, y + 28, Ui.DIM)
            }
        }
    }

    override fun click(mx: Double, my: Double, button: Int) = list.click(mx, my)
    override fun scroll(mx: Double, my: Double, dy: Double) = list.scroll(mx, my, dy)
}
