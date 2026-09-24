package kami.claims.client.ui.module

import kami.claims.client.ClientClaims
import kami.claims.client.ClientHooks
import kami.claims.client.ui.*
import kami.claims.net.Detail
import kami.claims.service.View
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MapModule(s: ClaimsScreen) : Module(s) {
    private var cx = 0.0
    private var cz = 0.0
    private var cell = 12
    private var centered = false
    private var anchor: Pair<Int, Int>? = null
    private var focus: Pair<Int, Int>? = null
    private var pressed: Pair<Double, Double>? = null
    private var moved = false
    private var rect = false
    private var type = "civic"

    private val prefs get() = ClientClaims.prefs
    private val vx get() = s.ax
    private val vy get() = s.ay + 20
    private val panelW get() = (s.aw / 4).coerceIn(132, 200)
    private val vw get() = s.aw - panelW - 6
    private val vh get() = s.ah - 20 - 14
    private val dim get() = Minecraft.getInstance().level?.dimension()?.location()?.toString() ?: "minecraft:overworld"

    private fun chunkAt(px: Double, py: Double) = floor(cx + (px - (vx + vw / 2.0)) / cell).toInt() to floor(cz + (py - (vy + vh / 2.0)) / cell).toInt()
    private fun screenX(x: Int) = (vx + vw / 2.0 + (x - cx) * cell).roundToInt()
    private fun screenZ(z: Int) = (vy + vh / 2.0 + (z - cz) * cell).roundToInt()

    private fun bounds(): List<Int>? {
        val a = anchor ?: return null
        val b = focus ?: a
        return listOf(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
    }

    private fun selected() = bounds()?.let { (x1, z1, x2, z2) -> (x1..x2).flatMap { x -> (z1..z2).map { z -> x to z } } } ?: emptyList()

    private fun center(x: Int, z: Int) {
        cx = x + 0.5
        cz = z + 0.5
    }

    fun jump(x: Int, z: Int) {
        center(x, z)
        anchor = x to z
        focus = anchor
        ClientHooks.request("chunk", x.toString(), z.toString())
    }

    override fun init() {
        if (!centered) {
            center(s.snap.px, s.snap.pz)
            centered = true
            type = ClientClaims.types.firstOrNull() ?: "civic"
        }
        val w = (vw) / 6
        s.btn("Me", vx, s.ay, w - 2, tip = "Center on your position") { center(s.snap.px, s.snap.pz) }
        val capital = s.info?.claimList?.firstOrNull { it.capital }
        s.btn("Capital", vx + w, s.ay, w - 2, capital != null, "Center on your capital") { capital?.let { center(it.x, it.z) } }
        s.btn("Labels", vx + w * 2, s.ay, w - 2, tip = "Show chunk type letters") { prefs.labels = !prefs.labels; ClientClaims.savePrefs() }
        s.btn("Grid", vx + w * 3, s.ay, w - 2, tip = "Show chunk grid") { prefs.grid = !prefs.grid; ClientClaims.savePrefs() }
        s.btn("Xaero", vx + w * 4, s.ay, w - 2, tip = "Toggle the claims overlay on Xaero's maps") { prefs.overlay = !prefs.overlay; ClientClaims.savePrefs() }
        s.btn("HUD", vx + w * 5, s.ay, w - 2, tip = "Toggle the territory HUD") { prefs.hud = !prefs.hud; ClientClaims.savePrefs() }

        val px = vx + vw + 6
        val pw2 = panelW - 4
        val colW = (pw2 - 4) / 2
        val cells = selected()
        val kinds = cells.mapNotNull { (x, z) -> ClientClaims.at(dim, x, z) }
        val own = kinds.count { it.flags and View.OWN != 0 }
        val free = cells.size - kinds.size
        val single = cells.size == 1
        val one = if (single) kinds.firstOrNull() else null
        val (x1, z1, x2, z2) = bounds() ?: listOf(0, 0, 0, 0)
        val y = s.ay + 92
        val types = ClientClaims.types.ifEmpty { listOf(type) }
        s.btn("<", px, y, 16) { type = types[(types.indexOf(type) - 1 + types.size) % types.size]; s.show(ClaimsScreen.Tab.MAP) }
        s.btn(">", px + pw2 - 16, y, 16) { type = types[(types.indexOf(type) + 1) % types.size]; s.show(ClaimsScreen.Tab.MAP) }
        val claim = s.can("claim")
        val args = arrayOf(x1.toString(), z1.toString(), x2.toString(), z2.toString())
        s.btn("Claim", px, y + 20, colW, claim && free > 0, "Claim unclaimed chunks of the selection as $type") { s.act("claimrect", type, *args) }
        s.btn("Set type", px + colW + 4, y + 20, colW, claim && own > 0, "Change the type of your chunks in the selection") { s.act("typerect", type, *args) }
        s.btn("Unclaim", px, y + 40, colW, claim && own > 0, "Release your chunks in the selection") { s.act("unclaimrect", *args) }
        s.btn("Capital", px + colW + 4, y + 40, colW, s.can("capital") && single && one != null && one.flags and View.CAPITAL == 0 && own == 1, "Move the capital here") { s.act("capital", args[0], args[1]) }
        val plot = single && one != null && one.flags and View.OWN != 0
        s.btn("Claim plot", px, y + 60, colW, s.can("plot") && plot && one!!.flags and View.CLAIMABLE != 0, "Take this residential plot; you pay its tax") { s.act("plot_claim", args[0], args[1]) }
        s.btn("Release", px + colW + 4, y + 60, colW, plot && one!!.flags and View.MINE != 0) { s.act("plot_release", args[0], args[1]) }
    }

    private fun tint(e: View.Entry): Int {
        val base = ClientClaims.country(e)?.color ?: 0x777777
        val friendly = e.flags and (View.OWN or View.ALLY) != 0
        return Ui.alpha(base, if (friendly) 0xE0 else 0x80)
    }

    override fun draw(g: GuiGraphics, mx: Int, my: Int) {
        val d = dim
        val bounds = bounds()
        val hoverCell = if (mx in vx until vx + vw && my in vy until vy + vh) chunkAt(mx.toDouble(), my.toDouble()) else null
        g.fill(vx, vy, vx + vw, vy + vh, Ui.PANEL)
        g.enableScissor(vx, vy, vx + vw, vy + vh)
        val x0 = floor(cx - vw / 2.0 / cell).toInt() - 1
        val z0 = floor(cz - vh / 2.0 / cell).toInt() - 1
        for (x in x0..x0 + vw / cell + 3) for (z in z0..z0 + vh / cell + 3) {
            val sx = screenX(x)
            val sz = screenZ(z)
            val e = ClientClaims.at(d, x, z)
            if (e == null) {
                if (prefs.grid && cell >= 8) g.fill(sx, sz, sx + cell, sz + cell, if ((x + z) and 1 == 0) 0xFF202027.toInt() else 0xFF23232B.toInt())
                continue
            }
            g.fill(sx, sz, sx + cell, sz + cell, tint(e))
            val color = Ui.rgb(ClientClaims.country(e)?.color ?: 0x777777)
            listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).forEachIndexed { side, (dx, dz) ->
                if (ClientClaims.at(d, x + dx, z + dz)?.country == e.country) return@forEachIndexed
                when (side) {
                    0 -> g.fill(sx, sz, sx + cell, sz + 1, color)
                    1 -> g.fill(sx + cell - 1, sz, sx + cell, sz + cell, color)
                    2 -> g.fill(sx, sz + cell - 1, sx + cell, sz + cell, color)
                    else -> g.fill(sx, sz, sx + 1, sz + cell, color)
                }
            }
            if (e.flags and View.MINE != 0) g.fill(sx + 2, sz + 2, sx + cell - 2, sz + cell - 2, Ui.alpha(Ui.GOLD, 0xC0))
            else if (e.flags and View.CLAIMABLE != 0 && prefs.claimable) g.fill(sx + cell / 3, sz + cell / 3, sx + cell - cell / 3, sz + cell - cell / 3, Ui.alpha(Ui.GOOD, 0xE0))
            else if (e.flags and View.TAKEN != 0 && cell >= 8) g.fill(sx + cell / 2 - 1, sz + cell / 2 - 1, sx + cell / 2 + 1, sz + cell / 2 + 1, 0xFF15151A.toInt())
            if (e.flags and View.DEBT != 0) g.renderOutline(sx, sz, cell, cell, Ui.BAD)
            if (prefs.labels && cell >= 10 && e.type >= 0) {
                val letter = if (e.flags and View.CAPITAL != 0) "*" else (ClientClaims.typeName(e)?.firstOrNull()?.uppercase() ?: "")
                g.drawString(s.text, letter, sx + cell / 2 - 2, sz + cell / 2 - 4, Ui.TEXT, false)
            }
        }
        bounds?.let { (a, b, c, e) ->
            val sx = screenX(a)
            val sz = screenZ(b)
            g.fill(sx, sz, screenX(c + 1), screenZ(e + 1), Ui.alpha(Ui.GOLD, 0x30))
            g.renderOutline(sx, sz, screenX(c + 1) - sx, screenZ(e + 1) - sz, Ui.GOLD)
        }
        hoverCell?.let { (hx, hz) -> g.renderOutline(screenX(hx), screenZ(hz), cell, cell, Ui.TEXT) }
        val mine = s.snap
        g.renderOutline(screenX(mine.px), screenZ(mine.pz), cell, cell, Ui.ACCENT)
        g.disableScissor()
        if (ClientClaims.dims.isEmpty() || d !in ClientClaims.dims) s.label(g, "Claims are not active in this dimension.", vx + 8, vy + 8, Ui.WARN)

        legend(g)
        panel(g)
        hoverCell?.let { (hx, hz) -> tooltip(g, mx, my, hx, hz, d) }
    }

    private fun legend(g: GuiGraphics) {
        var x = vx
        val y = vy + vh + 3
        listOf(Ui.ACCENT to "you", Ui.GOLD to "your plot", Ui.GOOD to "free plot", Ui.BAD to "debt").forEach { (c, t) ->
            g.fill(x, y + 1, x + 6, y + 7, c)
            s.label(g, t, x + 9, y, Ui.DIM)
            x += 18 + s.text.width(t)
        }
        s.label(g, "drag: pan  wheel: zoom  shift+drag: select", vx + vw - s.text.width("drag: pan  wheel: zoom  shift+drag: select"), y, Ui.DIM)
    }

    private fun panel(g: GuiGraphics) {
        val px = vx + vw + 6
        val pw2 = panelW - 4
        val y = s.ay
        g.fill(px, y, px + pw2, s.ay + s.ah, Ui.PANEL)
        val b = bounds()
        if (b == null) {
            s.label(g, "Click a chunk", px + 4, y + 4, Ui.DIM)
            s.label(g, "Shift+drag selects an area", px + 4, y + 16, Ui.DIM)
        } else {
            val single = b[0] == b[2] && b[1] == b[3]
            s.label(g, if (single) "Chunk ${b[0]}, ${b[1]}" else "${(b[2] - b[0] + 1) * (b[3] - b[1] + 1)} chunks", px + 4, y + 4, Ui.TEXT)
            val d = s.snap.detail?.takeIf { single && it.x == b[0] && it.z == b[1] }
            if (d != null) detail(g, d, px + 4, y + 16, pw2 - 8) else if (!single) summary(g, px + 4, y + 16)
        }
        val t = s.snap.types.firstOrNull { it.name == type }
        val name = Ui.fit(type, pw2 - 32)
        s.label(g, name, px + 16 + (pw2 - 32 - s.text.width(name)) / 2, s.ay + 96, Ui.typeColor(type))
        t?.let { s.label(g, "${it.price} spur / ${it.period}d", px + 4, s.ay + 82, Ui.GOOD) }
    }

    private fun summary(g: GuiGraphics, x: Int, y: Int) {
        val cells = selected()
        val entries = cells.mapNotNull { (a, b) -> ClientClaims.at(dim, a, b) }
        val own = entries.count { it.flags and View.OWN != 0 }
        val other = entries.size - own
        val t = s.snap.types.firstOrNull { it.name == type }
        s.label(g, "free: ${cells.size - entries.size}", x, y, Ui.GOOD)
        s.label(g, "yours: $own", x, y + 12, Ui.GOLD)
        s.label(g, "others: $other", x, y + 24, Ui.DIM)
        t?.let { s.label(g, "claim ~${(cells.size - entries.size) * it.price} spur", x, y + 40, Ui.WARN) }
    }

    private fun detail(g: GuiGraphics, d: Detail, x: Int, y: Int, w: Int) {
        var row = y
        fun line(text: String, color: Int = Ui.TEXT) { s.label(g, Ui.fit(text, w), x, row, color); row += 11 }
        if (d.country.isEmpty()) return line(d.note, Ui.DIM)
        line(d.country, Ui.GOLD)
        if (d.relation == 0) return line(d.note, Ui.DIM)
        line("${d.type}${if (d.capital) " (capital)" else ""}", Ui.typeColor(d.type))
        if (d.owner.isNotEmpty()) line("plot: ${d.owner}", Ui.TEXT)
        if (d.tax >= 0) line("tax ${d.tax}/d lapse ${d.lapse}", Ui.DIM)
        if (d.debt > 0) line("debt ${d.debt}", Ui.BAD)
        if (d.free) line("free chunk", Ui.GOOD)
    }

    private fun tooltip(g: GuiGraphics, mx: Int, my: Int, x: Int, z: Int, d: String) {
        val e = ClientClaims.at(d, x, z)
        val lines = ArrayList<Component>()
        lines += Component.literal("$x, $z")
        if (e == null) lines += Component.literal("§7Nomansland")
        else {
            lines += Component.literal("§6${ClientClaims.country(e)?.name ?: "?"}")
            ClientClaims.typeName(e)?.let { lines += Component.literal("§7$it${if (e.flags and View.CAPITAL != 0) " (capital)" else ""}") }
            if (e.flags and View.CLAIMABLE != 0) lines += Component.literal("§aFree plot - claim it in the panel")
            if (e.flags and View.MINE != 0) lines += Component.literal("§eYour plot")
            if (e.flags and View.DEBT != 0) lines += Component.literal("§cIn debt")
        }
        g.renderComponentTooltip(s.text, lines, mx, my)
    }

    override fun click(mx: Double, my: Double, button: Int): Boolean {
        if (mx < vx || mx >= vx + vw || my < vy || my >= vy + vh || button != 0) return false
        pressed = mx to my
        moved = false
        rect = net.minecraft.client.gui.screens.Screen.hasShiftDown()
        if (rect) {
            anchor = chunkAt(mx, my)
            focus = anchor
        }
        return true
    }

    override fun drag(mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean {
        val p = pressed ?: return false
        if (abs(mx - p.first) + abs(my - p.second) > 3) moved = true
        if (rect) focus = chunkAt(mx, my)
        else if (moved) {
            cx -= dx / cell
            cz -= dy / cell
        }
        return true
    }

    override fun release(mx: Double, my: Double, button: Int): Boolean {
        pressed ?: return false
        pressed = null
        if (rect) {
            s.show(ClaimsScreen.Tab.MAP)
            return true
        }
        if (!moved) {
            val c = chunkAt(mx, my)
            anchor = c
            focus = c
            ClientHooks.request("chunk", c.first.toString(), c.second.toString())
            s.show(ClaimsScreen.Tab.MAP)
        }
        return true
    }

    override fun scroll(mx: Double, my: Double, dy: Double): Boolean {
        if (mx < vx || mx >= vx + vw || my < vy || my >= vy + vh) return false
        cell = (cell + dy.toInt() * 2).coerceIn(4, 32)
        return true
    }

    override fun key(key: Int): Boolean {
        val step = 2.0
        when (key) {
            GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> cx -= step
            GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> cx += step
            GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> cz -= step
            GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> cz += step
            GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> cell = min(32, cell + 2)
            GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> cell = max(4, cell - 2)
            GLFW.GLFW_KEY_ESCAPE -> if (anchor != null) { anchor = null; focus = null; s.show(ClaimsScreen.Tab.MAP) } else return false
            else -> return false
        }
        return true
    }
}
