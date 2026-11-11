package kami.libs.ui.widget

import kami.libs.ui.anim.Ease
import kami.libs.ui.anim.burst
import kami.libs.ui.anim.flowDots
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.graph.Cell
import kami.libs.ui.graph.GraphView
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.UiSound
import kotlin.math.roundToInt

private const val BURST_REACH = 12

fun Ui.techTree(r: Rect, nodes: List<TechNode>, state: TechTreeState, selected: Int?, key: Any = "tree", bands: TechBands? = null, lanes: Map<Int, String> = emptyMap()): Int? {
    panel(r, sunken = true)
    val cells = state.layout(nodes)
    val view = state.view
    val pick = view.frame(this, r, selected ?: -1, key.toString()) { paintTree(r, it, nodes, cells, state, bands, lanes) }
    if (view.hovered >= 0) nodes[view.hovered].tip?.let { tip -> tooltip("$key:node:${view.hovered}", view.box(view.hovered).intersect(r)) { tip() } }
    if (pick == null) return null
    UiSound.click()
    if (pick.twice) state.center(pick.id)
    return pick.id
}

private fun Ui.paintTree(r: Rect, v: GraphView, nodes: List<TechNode>, cells: List<Cell>, state: TechTreeState, bands: TechBands?, lanes: Map<Int, String>) {
    val model = v.model
    val zoom = v.zoom
    val w = (TECH_NODE_W * zoom).roundToInt()
    val h = (TECH_NODE_H * zoom).roundToInt()
    fun left(i: Int) = v.px(model.cx[i] - TECH_NODE_W / 2.0)
    fun top(i: Int) = v.py(model.cy[i] - TECH_NODE_H / 2.0)
    for (i in nodes.indices) state.settle(i, v.hovered == i, v.selected == i, nodes[i].dim, dt, reduceMotion)
    val spawned = state.startBursts()
    val gapX = (GAP_X * zoom).toInt() / 4
    val gapY = (GAP_Y * zoom).toInt() / 2
    drawLanes(r, v.viewport, state.lanes, lanes, zoom)
    if (bands != null) drawBands(r, v.viewport, state.bands, bands)
    for (pass in 0..1) for (k in 0 until v.edgeCount) {
        val e = v.edges[k]
        val p = model.from[e]
        val c = model.to[e]
        val lit = v.lit(p, c)
        if (lit != (pass == 1)) continue
        val color = edgeColor(nodes[p].done, lit)
        val dashed = p in nodes[c].dashed
        val x0 = left(p) + w
        val y0 = top(p) + h / 2
        val x1 = left(c)
        val y1 = top(c) + h / 2
        if (state.isPrimary(p, c) || cells[c].col - cells[p].col <= 1) {
            edge(x0, y0, x1, y1, color, dashed, nodes[c].flowing && nodes[p].done, nodes[c].accent)
        } else {
            val channel = if (cells[c].row >= cells[p].row) y1 - h / 2 - gapY else y1 + h / 2 + gapY
            detour(x0, y0, x1, y1, gapX, channel, color, dashed)
        }
    }
    for (k in 0 until v.nodeCount) {
        val i = v.nodes[k]
        drawNode(nodes[i], i, left(i), top(i), zoom, v.lod, state)
    }
    for (i in nodes.indices) {
        val age = state.ringAge(i, dt)
        if (age < 0f) continue
        val reach = (Ease.outCubic.at(age / BURST_SECONDS) * BURST_REACH * zoom).toInt()
        Draw.outline(g, Rect(left(i), top(i), w, h).grow(reach), Palette.fade(Palette.brass, 1f - age / BURST_SECONDS))
    }
    spawned.forEach { burst(left(it) + w / 2, top(it) + h / 2, nodes[it].accent) }
}

private fun edgeColor(met: Boolean, lit: Boolean): Int = when {
    lit -> Palette.brass
    met -> Palette.alpha(Palette.success, 0xC8)
    else -> Palette.alpha(Palette.textSecondary, 0xA0)
}

private fun Ui.edge(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, dashed: Boolean, flowing: Boolean, accent: Int) {
    val mid = (x0 + x1) / 2
    segment(x0, y0, mid, y0, color, dashed)
    segment(mid, y0, mid, y1, color, dashed)
    segment(mid, y1, x1, y1, color, dashed)
    if (flowing) flowDots(x0, y0, mid, x1, y1, accent)
}

private fun Ui.detour(x0: Int, y0: Int, x1: Int, y1: Int, gutter: Int, channel: Int, color: Int, dashed: Boolean) {
    val out = x0 + gutter
    val into = x1 - gutter
    segment(x0, y0, out, y0, color, dashed)
    segment(out, y0, out, channel, color, dashed)
    segment(out, channel, into, channel, color, dashed)
    segment(into, channel, into, y1, color, dashed)
    segment(into, y1, x1, y1, color, dashed)
}

private fun Ui.segment(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, dashed: Boolean) {
    when {
        dashed -> Draw.dashed(g, x0, y0, x1, y1, color)
        y0 == y1 -> g.fill(minOf(x0, x1), y0, maxOf(x0, x1) + 1, y0 + 1, color)
        else -> g.fill(x0, minOf(y0, y1), x0 + 1, maxOf(y0, y1) + 1, color)
    }
}
