package kami.libs.ui.graph

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.map.Viewport
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val OVERZOOM = 1.6
private const val CULL_MARGIN = 48
private const val DOUBLE_CLICK_MS = 350L
private const val NOTHING = -2
private const val DETAIL_ROOM = 1.05

class GraphPick(val id: Int, val twice: Boolean)

class GraphView(private val levels: GraphLod, private val maxZoom: Double, private val hitSlackPx: Int = 0, private val fill: Double = 0.94) {
    val viewport = Viewport()
    var model = GraphModel.EMPTY
        private set
    var lod = Lod.OVERVIEW
        private set
    var zoom = 1.0
        private set
    var hovered = -1
        private set
    var selected = -1
        private set
    var trace: Trace? = null
        private set
    var nodes = IntArray(0)
        private set
    var nodeCount = 0
        private set
    var edges = IntArray(0)
        private set
    var edgeCount = 0
        private set
    private var needFit = true
    private var smooth = false
    private var atFit = false
    private var everFit = false
    private var fitUpp = 1.0
    private var viewW = -1
    private var viewH = -1
    private var pending = -1
    private var traced = NOTHING
    private var cleared = false
    private var lastTap = -1
    private var lastTapAt = 0L

    fun load(next: GraphModel) {
        model = next
        nodes = IntArray(next.size)
        edges = IntArray(next.edgeCount)
        traced = NOTHING
        hovered = -1
        needFit = true
        smooth = false
    }

    fun refit() { needFit = true; smooth = true }

    fun centerOn(id: Int) { pending = id }

    fun takeClear() = cleared.also { cleared = false }

    fun lit(parent: Int, child: Int) = trace?.lit(parent, child) == true

    fun sx(x: Double) = viewport.screenX(x)

    fun sy(y: Double) = viewport.screenY(y)

    fun px(x: Double) = sx(x).roundToInt()

    fun py(y: Double) = sy(y).roundToInt()

    fun box(i: Int) = Rect(px(model.cx[i] - model.w[i] / 2), py(model.cy[i] - model.h[i] / 2), (model.w[i] * zoom).roundToInt(), (model.h[i] * zoom).roundToInt())

    fun frame(ui: Ui, r: Rect, selected: Int, key: String, paint: (GraphView) -> Unit): GraphPick? {
        this.selected = selected
        val first = settleCamera(r)
        if (viewport.interact(ui, r, key = key, arrows = selected < 0) || viewport.dragging) atFit = false
        applyPending(first, ui.reduceMotion)
        viewport.step(ui.dt, ui.reduceMotion)
        zoom = 1.0 / viewport.unitsPerPx
        lod = levels.at(zoom, lod)
        val live = ui.hovering(r) && !viewport.dragging
        hovered = if (live) model.nearest(viewport.worldX(ui.mouseX.toDouble()), viewport.worldZ(ui.mouseY.toDouble()), hitSlackPx * viewport.unitsPerPx) else -1
        if (hovered >= 0) ui.cursor = Cursor.HAND
        var pick: GraphPick? = null
        if (viewport.tapped) if (hovered >= 0) pick = tap(hovered, ui.wallMillis) else cleared = true
        if (live && !ui.typing) pick = keys(ui) ?: pick
        val focus = if (selected >= 0) selected else hovered
        if (focus != traced) { traced = focus; trace = if (focus in 0 until model.size) model.trace(focus) else null }
        val margin = CULL_MARGIN * viewport.unitsPerPx
        val x0 = viewport.worldX(r.x.toDouble()) - margin
        val x1 = viewport.worldX(r.right.toDouble()) + margin
        val y0 = viewport.worldZ(r.y.toDouble()) - margin
        val y1 = viewport.worldZ(r.bottom.toDouble()) + margin
        nodeCount = model.visibleNodes(x0, y0, x1, y1, nodes)
        edgeCount = model.visibleEdges(x0, y0, x1, y1, edges)
        ui.clip(r.inset(1)) { paint(this) }
        return pick
    }

    private fun tap(id: Int, now: Long): GraphPick {
        val twice = id == lastTap && now - lastTapAt <= DOUBLE_CLICK_MS
        lastTap = if (twice) -1 else id
        lastTapAt = now
        return GraphPick(id, twice)
    }

    private fun keys(ui: Ui): GraphPick? {
        if (ui.input.takeKey(GLFW.GLFW_KEY_HOME) != null) refit()
        if (selected !in 0 until model.size) return null
        if (ui.input.takeKey(GLFW.GLFW_KEY_ENTER) != null || ui.input.takeKey(GLFW.GLFW_KEY_KP_ENTER) != null) return GraphPick(selected, true)
        val dx = axis(ui, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT)
        val dy = axis(ui, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN)
        if (dx == 0 && dy == 0) return null
        val next = model.neighbour(selected, dx, dy)
        if (next < 0) return null
        centerOn(next)
        return GraphPick(next, false)
    }

    private fun axis(ui: Ui, negative: Int, positive: Int) =
        if (ui.input.takeKey(negative) { !it.ctrl } != null) -1 else if (ui.input.takeKey(positive) { !it.ctrl } != null) 1 else 0

    private fun applyPending(instant: Boolean, reduceMotion: Boolean) {
        val id = pending
        pending = -1
        if (id !in 0 until model.size) return
        val upp = min(viewport.unitsPerPx, 1.0 / (levels.detail * DETAIL_ROOM)).coerceIn(viewport.minUnitsPerPx, viewport.maxUnitsPerPx)
        atFit = false
        if (instant || reduceMotion) {
            viewport.cx = model.cx[id]
            viewport.cz = model.cy[id]
            viewport.unitsPerPx = upp
            viewport.clamp()
            viewport.cancelGlide()
        } else viewport.glide(model.cx[id], model.cy[id], upp)
    }

    private fun settleCamera(r: Rect): Boolean {
        val resized = r.w != viewW || r.h != viewH
        if (!resized && !needFit) return false
        viewW = r.w
        viewH = r.h
        viewport.view = r
        if (model.size == 0) return false
        val keep = !needFit && !atFit
        val x = viewport.cx
        val z = viewport.cz
        val upp = viewport.unitsPerPx
        val ratio = upp / fitUpp
        val glide = needFit && smooth && everFit
        fitCamera()
        when {
            keep -> {
                viewport.cx = x
                viewport.cz = z
                viewport.unitsPerPx = (ratio * fitUpp).coerceIn(viewport.minUnitsPerPx, viewport.maxUnitsPerPx)
                viewport.clamp()
            }
            glide -> {
                val toX = viewport.cx
                val toZ = viewport.cz
                val toUpp = viewport.unitsPerPx
                viewport.cx = x
                viewport.cz = z
                viewport.unitsPerPx = upp
                viewport.glide(toX, toZ, toUpp)
            }
        }
        needFit = false
        smooth = false
        everFit = true
        return !keep && !glide
    }

    private fun fitCamera() {
        val w = model.maxX - model.minX
        val h = model.maxY - model.minY
        viewport.fit(model.minX, model.minY, w, h, fill)
        val minUpp = 1.0 / maxZoom
        fitUpp = max(viewport.unitsPerPx, minUpp)
        viewport.range(min(minUpp, fitUpp), fitUpp * OVERZOOM)
        viewport.unitsPerPx = fitUpp
        viewport.limit(model.minX, model.minY, w, h)
        atFit = true
    }
}
