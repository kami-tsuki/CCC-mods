package kami.claims.client.ui.module

import kami.claims.client.ui.*
import kami.claims.net.PlayerLine
import net.minecraft.client.gui.GuiGraphics

class PlayersModule(s: ClaimsScreen) : Module(s) {
    private var search = ""
    private val list = ScrollList<PlayerLine>(0, 0, 0, 0) { g, pl, x, y, w, _ ->
        g.drawString(s.text, Ui.fit(pl.name, 120), x, y + 3, Ui.TEXT, false)
        val home = pl.citizenships.firstOrNull { it.via.isEmpty() }
        val text = home?.let { "${it.country} (${it.role})" } ?: "no country"
        g.drawString(s.text, Ui.fit(text, w - 126), x + 126, y + 3, if (home == null) Ui.DIM else Ui.GOLD, false)
    }

    override fun snapshot() {
        list.items = s.snap.players.filter { search.isBlank() || it.name.contains(search, true) }
    }

    override fun init() {
        val x = s.ax
        val y = s.ay
        s.edit(x, y, 200, search, 24, "Search player") { search = it; snapshot() }
        list.x = x; list.y = y + 22; list.w = s.aw - 156; list.h = s.ah - 22
        snapshot()
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        list.draw(g, mx, my)
        val rx = s.ax + s.aw - 144
        val y = s.ay + 22
        s.label(g, "${list.items.size} of ${s.snap.players.size} players", s.ax, s.ay + 6, Ui.DIM)
        val sel = list.current ?: return
        s.label(g, sel.name, rx, y, Ui.GOLD)
        s.label(g, "Citizenships", rx, y + 14, Ui.DIM)
        sel.citizenships.forEachIndexed { n, c ->
            val cy = y + 28 + n * 22
            val label = if (c.via.isEmpty()) c.country else "${c.country} (via ${c.via})"
            s.label(g, Ui.fit(label, 144), rx, cy, if (c.via.isEmpty()) Ui.TEXT else Ui.DIM)
            s.label(g, c.role, rx, cy + 10, Ui.rankColor(c.role))
        }
        if (sel.citizenships.isEmpty()) s.label(g, "No citizenships.", rx, y + 28, Ui.DIM)
    }

    override fun click(mx: Double, my: Double, button: Int) = list.click(mx, my)
    override fun scroll(mx: Double, my: Double, dy: Double) = list.scroll(mx, my, dy)
}
