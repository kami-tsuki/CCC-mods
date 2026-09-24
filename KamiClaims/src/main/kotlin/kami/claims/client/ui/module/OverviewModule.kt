package kami.claims.client.ui.module

import kami.claims.Rank
import kami.claims.client.ui.*
import kami.claims.net.Line
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.max

class OverviewModule(s: ClaimsScreen) : Module(s) {
    private var amount = "10"
    private var name = ""
    private var search = ""
    private var confirm = false
    private val swatches = intArrayOf(0x3FA34D, 0x3B82C4, 0xC4553B, 0x9B59B6, 0xD9A441, 0x2AA6A6, 0xC45B9A, 0x8A8F3B, 0xE0E0E0, 0x555566)
    private val countries = ScrollList<Line>(0, 0, 0, 0) { g, c, x, y, w, _ ->
        g.drawString(s.text, Ui.fit("${c.name}  ${c.members} members, ${c.chunks} chunks", w), x, y + 3, Ui.TEXT, false)
    }
    private val invites = ScrollList<String>(0, 0, 0, 0) { g, n, x, y, _, _ -> g.drawString(s.text, n, x, y + 3, Ui.GOLD, false) }

    override fun snapshot() {
        confirm = false
        countries.items = s.snap.countries.filter { search.isBlank() || it.name.contains(search, true) }
        invites.items = s.snap.invites
    }

    override fun init() {
        confirm = false
        val i = s.info
        val x = s.ax
        val y = s.ay
        if (i == null) {
            s.edit(x, y, 150, name, 24, "Country name") { name = it }
            s.btn("Found country here", x + 156, y, 130, tip = "You receive ${s.snap.freeChunks} free chunks; this chunk becomes the capital.") { s.act("create", name) }
            s.edit(x, y + 40, 140, search, 20, "Search countries") { search = it; snapshot() }
            countries.x = x; countries.y = y + 60; countries.w = s.aw / 2 - 6; countries.h = s.ah - 64
            invites.x = x + s.aw / 2 + 6; invites.y = y + 60; invites.w = s.aw / 2 - 6; invites.h = s.ah - 84
            snapshot()
            s.btn("Request join", x + 146, y + 40, 100) { countries.current?.let { c -> s.act("join", c.name) } }
            s.btn("Accept invite", x + s.aw / 2 + 6, y + s.ah - 20, 100) { invites.current?.let { n -> s.act("accept", n) } }
            return
        }
        val rx = x + s.aw / 2 + 6
        val own = !i.delegated
        s.edit(rx, y + 66, 60, amount, 8, "Amount") { amount = it }
        s.btn("Deposit", rx + 64, y + 66, 56, own) { s.act("deposit", amount) }
        s.btn("Withdraw", rx + 124, y + 66, 60, s.can("withdraw"), "Requires ${s.snap.caps["withdraw"]} or higher") { s.act("withdraw", amount) }
        s.btn("All", rx + 188, y + 66, 26, own, "Deposit everything you carry") { s.act("deposit", s.snap.funds.coerceAtMost(Int.MAX_VALUE.toLong()).toString()) }
        val tax = s.can("tax")
        val row = y + s.ah - 58
        s.stepper(x, row, tax, dec = { s.act("tax", max(0, i.tax - 1).toString()) }, inc = { s.act("tax", (i.tax + 1).toString()) })
        s.stepper(x, row + 20, tax, dec = { s.act("lapse", max(0, i.shutdown - 1).toString(), i.release.toString()) }, inc = { s.act("lapse", (i.shutdown + 1).toString(), i.release.toString()) })
        s.stepper(x + 190, row + 20, tax, dec = { s.act("lapse", i.shutdown.toString(), max(0, i.release - 1).toString()) }, inc = { s.act("lapse", i.shutdown.toString(), (i.release + 1).toString()) })
        if (own) {
            s.btn("Leave", rx, y + s.ah - 20, 60) { s.act("leave") }
            if (s.rank == Rank.PRESIDENT) s.btn(if (confirm) "Really disband?" else "Disband", rx + 64, y + s.ah - 20, 96) {
                if (confirm) s.act("disband") else { confirm = true; s.show(ClaimsScreen.Tab.OVERVIEW) }
            }
        }
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        val x = s.ax
        val y = s.ay
        val i = s.info
        if (i == null) {
            s.label(g, "Found a country on the chunk you stand in, or join one.", x, y + 22, Ui.DIM)
            countries.draw(g, mx, my)
            s.label(g, "Invitations", invites.x, y + 50, Ui.GOLD)
            invites.draw(g, mx, my)
            return
        }
        g.fill(x, y + 1, x + 10, y + 11, Ui.rgb(i.color))
        s.label(g, i.name, x + 15, y + 2, Ui.GOLD)
        s.label(g, i.rank, x + 20 + s.text.width(i.name), y + 2, Ui.rankColor(i.rank))
        swatches.forEachIndexed { n, c ->
            val sx = x + n * 14
            g.fill(sx, y + 16, sx + 11, y + 27, Ui.rgb(c))
            if (c == i.color) g.renderOutline(sx - 1, y + 15, 13, 13, Ui.TEXT)
        }
        val net = i.upkeep + i.jobs - i.income
        s.label(g, "Chunks ${i.chunks} (${i.free} free)   Plots you own ${i.plots}/${s.snap.maxPlots}", x, y + 34, Ui.TEXT)
        s.label(g, "Upkeep", x, y + 48, Ui.DIM)
        val maxDay = max(0.01, i.breakdown.maxOfOrNull { it.perDay } ?: 0.0)
        i.breakdown.take(6).forEachIndexed { n, b ->
            val by = y + 60 + n * 12
            g.fill(x, by + 1, x + 6, by + 8, Ui.typeColor(b.type))
            s.label(g, "${b.type} x${b.count}", x + 10, by, Ui.TEXT)
            Ui.bar(g, x + 96, by + 2, 70, 5, b.perDay / maxDay, Ui.typeColor(b.type))
            s.label(g, String.format("%.1f/d", b.perDay), x + 170, by, Ui.DIM)
        }
        val row = y + s.ah - 58
        s.label(g, "Residential tax ${i.tax} spur/day", x + 44, row + 4, Ui.TEXT)
        s.label(g, "Plots shut down after ${i.shutdown}d", x + 44, row + 24, Ui.TEXT)
        s.label(g, "released ${i.release}d later", x + 232, row + 24, Ui.TEXT)

        val rx = x + s.aw / 2 + 6
        g.fill(rx, y, rx + s.aw / 2 - 6, y + 60, Ui.PANEL)
        s.label(g, "Treasury", rx + 6, y + 6, Ui.DIM)
        s.label(g, "${i.treasury} spur", rx + 6, y + 18, Ui.GOLD)
        s.label(g, "Runway ${i.runway}", rx + 6, y + 32, if (i.runway == "stable") Ui.GOOD else Ui.WARN)
        s.label(g, "Next billing in ${Ui.span(i.nextBilling)}", rx + 6, y + 44, Ui.DIM)
        s.label(g, "Taxes +${i.income}/d", rx + 110, y + 18, Ui.GOOD)
        s.label(g, "Upkeep -${i.upkeep}/d", rx + 110, y + 30, Ui.BAD)
        s.label(g, "Job pay -${i.jobs}/d", rx + 110, y + 42, Ui.BAD)
        val netText = if (net > 0) "Net -$net/day" else "Net +${-net}/day"
        s.label(g, netText, rx + 6, y + 54, if (net > 0) Ui.BAD else Ui.GOOD)
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        val i = s.info
        if (i != null && s.can("rules")) {
            val idx = ((mx - s.ax) / 14).toInt()
            if (mx >= s.ax && my >= s.ay + 16 && my < s.ay + 27 && idx in swatches.indices && mx - s.ax - idx * 14 < 11) {
                s.act("color", "%06x".format(swatches[idx]))
                return true
            }
        }
        if (i == null) return countries.click(mx, my) || invites.click(mx, my)
        return false
    }

    override fun scroll(mx: Double, my: Double, dy: Double) = s.info == null && (countries.scroll(mx, my, dy) || invites.scroll(mx, my, dy))
}
