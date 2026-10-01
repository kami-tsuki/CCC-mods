package kami.libs.ui.widget

import kami.libs.ui.anim.Countdown
import kami.libs.ui.anim.Ease
import kami.libs.ui.anim.burst
import kami.libs.ui.anim.flowDots
import kami.libs.ui.anim.pulse
import kami.libs.ui.core.Click
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.graph.Band
import kami.libs.ui.graph.Cell
import kami.libs.ui.graph.Lane
import kami.libs.ui.graph.TechLayout
import kami.libs.ui.map.Viewport
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.UiSound
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max

const val TECH_NODE_W = 108
const val TECH_NODE_H = 28
private const val GAP_X = 30
private const val GAP_Y = 10
private const val BAND_CURRENT_ALPHA = 0x14
private const val BAND_LOCKED_ALPHA = 0x60
private const val BAND_LABEL_Y = 4
private const val BAND_LABEL_PAD = 5
private const val BAND_LOCK_Y = 8
private const val FIT_FILL = 0.94
private const val LANE_ALPHA = 0x0C
private const val LANE_LABEL_PAD = 6
private const val LANE_LABEL_LIFT = 3
private const val LOD_ZOOM = 0.6
private const val MOTION_SPEED = 16f
private const val BURST_SECONDS = 0.6f
private const val BURST_REACH = 12
private const val PULSE_MS = 1600L
private const val PULSE_PHASE = 97L
private const val NOT_TRACED = -2
private const val TEXT_PAD = 6
private const val ICON_ROOM = 14
private const val ITEM_X = 7
private const val ITEM_Y = 3
private const val ITEM_TEXT_X = 27
private const val BADGE_Z = 200f
private const val VEIL_Z = 250f
private const val LOCK_SIZE = 9
private const val LOCK_X = 3
private const val LOCK_Y = 1
private const val LABEL_Y = 4
private const val DOUBLE_CLICK_MS = 350L
private const val CLICK_SLOP = 4
private const val FOCUS_VEIL = 0.6f
private const val FOCUS_EDGE_FADE = 0.65f
private const val STATUS_ICON_X = 13
private const val STATUS_ICON_Y = 8
private const val BAR_H = 4
private const val BAR_BOTTOM = 6
private const val TIME_Y = 13
private const val HATCH_SPACING = 3
private const val PAUSED_ALPHA = 0xB0
private val TASK_BAR = Palette.chart[1]
private const val READY_GLOW = 0.3f
private const val READY_GLOW_SWING = 0.4f
private const val LIFT_SHADOW_AT = 0.5f
private val LOCAL = Rect(0, 0, TECH_NODE_W, TECH_NODE_H)
private val LOCK_BADGE = Rect(LOCK_X, LOCK_Y, LOCK_SIZE, LOCK_SIZE)
private val BAR = Rect(TEXT_PAD, TECH_NODE_H - BAR_BOTTOM, TECH_NODE_W - 2 * TEXT_PAD, BAR_H)

class TechNode(
    val label: String,
    val accent: Int,
    val parents: List<Int>,
    val dashed: Set<Int> = emptySet(),
    val done: Boolean = false,
    val dim: Boolean = false,
    val status: Icon? = null,
    val progress: Double? = null,
    val highlight: Boolean = false,
    val pinned: Cell? = null,
    val tip: (() -> Tip)? = null,
    val ready: Boolean = false,
    val flowing: Boolean = false,
    val countdown: Countdown? = null,
    val band: Int = 0,
    val item: ItemStack = ItemStack.EMPTY,
    val hidden: Set<Int> = emptySet(),
    val group: Int = 0
) {
    val locked get() = status == Icons.LOCK
    val textX get() = if (item.isEmpty) TEXT_PAD else ITEM_TEXT_X
}

class TechBands(val labels: Map<Int, String>, val current: Int)

class TechTreeState {
    val viewport = Viewport(1.0, 0.5, 2.5)
    var bands: List<Band> = emptyList()
        private set
    var cells: List<Cell> = emptyList()
        private set
    var lanes: List<Lane> = emptyList()
        private set
    private var signature = 0
    private var lastNodes: List<TechNode>? = null
    private var labels = arrayOfNulls<String>(0)
    private var times = arrayOfNulls<String>(0)
    private var timeMinutes = LongArray(0)
    private var fitted = false
    private var everFitted = false
    private var centerOn: Int? = null
    private var hover = FloatArray(0)
    private var select = FloatArray(0)
    private var dim = FloatArray(0)
    private var fade = FloatArray(0)
    private var path = BooleanArray(0)
    private var unlocks = BooleanArray(0)
    private var primary = IntArray(0)
    private var traced = NOT_TRACED
    private var focusMix = 0f
    private var armed = false
    private var armX = 0
    private var armY = 0
    private var cleared = false
    private var lastClick = -1
    private var lastClickAt = 0L
    private var burstAge = FloatArray(0)
    private val bursts = ArrayList<Int>()
    internal var xs = IntArray(0)
        private set
    internal var ys = IntArray(0)
        private set

    fun refit() { fitted = false }

    fun center(node: Int) { centerOn = node }

    fun burst(node: Int) { bursts += node }

    fun takeClear(): Boolean = cleared.also { cleared = false }

    internal fun arm(click: Click?) {
        if (click == null) return
        armed = true
        armX = click.x
        armY = click.y
    }

    internal fun release(click: Click) {
        if (armed && abs(click.x - armX) <= CLICK_SLOP && abs(click.y - armY) <= CLICK_SLOP) cleared = true
        armed = false
    }

    internal fun doubleClicked(node: Int, now: Long): Boolean {
        val twice = node == lastClick && now - lastClickAt <= DOUBLE_CLICK_MS
        lastClick = if (twice) -1 else node
        lastClickAt = now
        return twice
    }

    internal fun trace(focus: Int, nodes: List<TechNode>) {
        if (focus == traced) return
        traced = focus
        path.fill(false)
        unlocks.fill(false)
        if (focus < 0) return
        val pending = ArrayDeque<Int>()
        path[focus] = true
        pending.add(focus)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            nodes[node].parents.forEach { if (it in nodes.indices && !path[it]) { path[it] = true; pending.add(it) } }
        }
        nodes.forEachIndexed { i, node -> if (focus in node.parents) unlocks[i] = true }
    }

    internal fun inFocus(i: Int) = path[i] || unlocks[i]

    internal fun edgeInFocus(parent: Int, child: Int) = traced >= 0 && ((path[parent] && path[child]) || parent == traced)

    internal fun isPrimary(parent: Int, child: Int) = primary[child] == parent

    internal fun focusAmount() = focusMix

    fun applyCenter(instant: Boolean) {
        val node = centerOn?.let { cells.getOrNull(it) } ?: return
        val x = worldX(node) + TECH_NODE_W / 2.0
        val y = worldY(node) + TECH_NODE_H / 2.0
        if (instant) { viewport.cx = x; viewport.cz = y } else viewport.glide(x, y)
        centerOn = null
    }

    fun neighbour(from: Int, dx: Int, dy: Int): Int? {
        val here = cells.getOrNull(from) ?: return null
        return cells.indices.filter { it != from }.filter { cells[it].col == here.col + dx && (dx != 0 || (cells[it].row - here.row) * dy > 0) }
            .minByOrNull { abs(cells[it].row - here.row) }
    }

    fun layout(nodes: List<TechNode>): List<Cell> {
        if (nodes !== lastNodes) {
            lastNodes = nodes
            labels.fill(null)
            traced = NOT_TRACED
            val next = nodes.map { listOf(it.parents, it.pinned, it.band, it.hidden, it.group) }.hashCode()
            if (next != signature || cells.size != nodes.size) {
                signature = next
                val parents = nodes.map { it.parents }
                val hidden = nodes.map { it.hidden }
                val placement = TechLayout.place(parents, nodes.map { it.pinned }, nodes.map { it.band }, hidden, nodes.map { it.group })
                cells = placement.cells
                bands = placement.bands
                lanes = placement.lanes
                primary = TechLayout.primaries(parents, hidden, nodes.map { it.group }, nodes.map { it.band })
                fitted = false
            }
        }
        if (hover.size != nodes.size) {
            hover = FloatArray(nodes.size) { -1f }
            select = FloatArray(nodes.size) { -1f }
            dim = FloatArray(nodes.size) { -1f }
            fade = FloatArray(nodes.size) { -1f }
            path = BooleanArray(nodes.size)
            unlocks = BooleanArray(nodes.size)
            traced = NOT_TRACED
            burstAge = FloatArray(nodes.size) { -1f }
            labels = arrayOfNulls(nodes.size)
            times = arrayOfNulls(nodes.size)
            timeMinutes = LongArray(nodes.size) { -1L }
            xs = IntArray(nodes.size)
            ys = IntArray(nodes.size)
        }
        return cells
    }

    internal fun label(i: Int, node: TechNode): String =
        labels[i] ?: Draw.fit(node.label, TECH_NODE_W - node.textX - TEXT_PAD - if (node.status != null && !node.locked) ICON_ROOM else 0).also { labels[i] = it }

    internal fun timeLeft(i: Int, ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / 60_000
        val cached = times[i]
        if (cached != null && timeMinutes[i] == minutes) return cached
        timeMinutes[i] = minutes
        return Format.duration(ms).also { times[i] = it }
    }

    internal fun settle(i: Int, hovered: Boolean, selected: Boolean, dimmed: Boolean, faded: Boolean, dt: Float, instant: Boolean) {
        hover[i] = approach(hover[i], hovered, dt, instant)
        select[i] = approach(select[i], selected, dt, instant)
        dim[i] = approach(dim[i], dimmed, dt, instant)
        fade[i] = approach(fade[i], faded, dt, instant)
    }

    internal fun settleFocus(active: Boolean, dt: Float, instant: Boolean) { focusMix = approach(focusMix, active, dt, instant) }

    private fun approach(value: Float, on: Boolean, dt: Float, instant: Boolean): Float {
        val target = if (on) 1f else 0f
        return if (value < 0f || instant) target else Ease.approach(value, target, MOTION_SPEED, dt)
    }

    internal fun hoverOf(i: Int) = hover[i]
    internal fun selectOf(i: Int) = select[i]
    internal fun dimOf(i: Int) = dim[i]
    internal fun fadeOf(i: Int) = fade[i]

    internal fun startBursts(): List<Int> {
        if (bursts.isEmpty()) return emptyList()
        val started = bursts.filter { it in burstAge.indices }
        started.forEach { burstAge[it] = 0f }
        bursts.clear()
        return started
    }

    internal fun ringAge(i: Int, dt: Float): Float {
        val age = burstAge[i]
        if (age < 0f) return -1f
        burstAge[i] = if (age + dt >= BURST_SECONDS) -1f else age + dt
        return age
    }

    fun fitIfNeeded(view: Rect): Boolean {
        if (fitted || cells.isEmpty()) return false
        viewport.view = view
        val width = (cells.maxOf { it.col } + 1) * (TECH_NODE_W + GAP_X) - GAP_X
        val height = (cells.maxOf { it.row } + 1) * (TECH_NODE_H + GAP_Y) - GAP_Y
        val fromX = viewport.cx
        val fromZ = viewport.cz
        val fromScale = viewport.unitsPerPx
        viewport.fit(0.0, 0.0, width.toDouble(), height.toDouble(), FIT_FILL)
        viewport.unitsPerPx = max(viewport.unitsPerPx, 1.0)
        val first = !everFitted
        if (!first) {
            val toX = viewport.cx
            val toZ = viewport.cz
            val toScale = viewport.unitsPerPx
            viewport.cx = fromX; viewport.cz = fromZ; viewport.unitsPerPx = fromScale
            viewport.glide(toX, toZ, toScale)
        }
        fitted = true
        everFitted = true
        return first
    }
}

private fun worldX(cell: Cell) = cell.col * (TECH_NODE_W + GAP_X)
private fun worldY(cell: Cell) = cell.row * (TECH_NODE_H + GAP_Y)
private fun bandEdge(col: Int) = col * (TECH_NODE_W + GAP_X) - GAP_X / 2.0

fun Ui.techTree(r: Rect, nodes: List<TechNode>, state: TechTreeState, selected: Int?, key: Any = "tree", bands: TechBands? = null, lanes: Map<Int, String> = emptyMap()): Int? {
    panel(r, sunken = true)
    val cells = state.layout(nodes)
    val first = state.fitIfNeeded(r)
    state.applyCenter(first)
    val vp = state.viewport
    vp.view = r
    vp.step(dt, reduceMotion)
    val zoom = 1.0 / vp.unitsPerPx
    val w = (TECH_NODE_W * zoom).toInt()
    val h = (TECH_NODE_H * zoom).toInt()
    val xs = state.xs
    val ys = state.ys
    for (i in nodes.indices) {
        xs[i] = vp.screenX(worldX(cells[i]).toDouble()).toInt()
        ys[i] = vp.screenY(worldY(cells[i]).toDouble()).toInt()
    }
    var clicked: Int? = null
    var hovered = -1
    if (hovering(r)) for (i in nodes.indices) {
        if (mouseX >= xs[i] && mouseX < xs[i] + w && mouseY >= ys[i] && mouseY < ys[i] + h) hovered = i
    }
    if (hovered >= 0) {
        cursor = Cursor.HAND
        if (pressed(Rect(xs[hovered], ys[hovered], w, h).intersect(r)) != null) {
            clicked = hovered
            UiSound.click()
            if (state.doubleClicked(hovered, wallMillis)) { state.center(hovered); state.applyCenter(reduceMotion) }
        }
    }
    state.arm(input.presses.firstOrNull { !it.consumed && it.button == 0 && r.contains(it.x, it.y) && canHit(it.x, it.y) })
    released()?.let(state::release)
    val focus = if (hovered >= 0) hovered else selected ?: -1
    state.trace(focus, nodes)
    state.settleFocus(focus >= 0, dt, reduceMotion)
    for (i in nodes.indices) state.settle(i, hovered == i, selected == i, nodes[i].dim, focus >= 0 && !state.inFocus(i), dt, reduceMotion)
    val spawned = state.startBursts()
    val gapX = (GAP_X * zoom).toInt() / 4
    val gapY = (GAP_Y * zoom).toInt() / 2
    clip(r) {
        drawLanes(r, state, lanes, zoom)
        if (bands != null) drawBands(r, state, bands)
        for (pass in 0..1) for (i in nodes.indices) {
            val parents = nodes[i].parents
            for (k in parents.indices) {
                val p = parents[k]
                if (p !in nodes.indices || p in nodes[i].hidden || !edgeVisible(r, xs[p], ys[p], xs[i], ys[i], w, h)) continue
                val lit = state.edgeInFocus(p, i)
                if (lit != (pass == 1)) continue
                val color = edgeColor(nodes[p].done, lit, state.focusAmount())
                val dashed = p in nodes[i].dashed
                if (state.isPrimary(p, i) || cells[i].col - cells[p].col <= 1) {
                    edge(xs[p] + w, ys[p] + h / 2, xs[i], ys[i] + h / 2, color, dashed, nodes[i].flowing && nodes[p].done, nodes[i].accent)
                } else {
                    val channel = if (cells[i].row >= cells[p].row) ys[i] - gapY else ys[i] + h + gapY
                    detour(xs[p] + w, ys[p] + h / 2, xs[i], ys[i] + h / 2, gapX, channel, color, dashed)
                }
            }
        }
        for (i in nodes.indices) {
            if (xs[i] + w < r.x || xs[i] > r.right || ys[i] + h < r.y || ys[i] > r.bottom) continue
            drawNode(nodes[i], i, xs[i], ys[i], zoom, state)
        }
        for (i in nodes.indices) {
            val age = state.ringAge(i, dt)
            if (age < 0f) continue
            val reach = (Ease.outCubic.at(age / BURST_SECONDS) * BURST_REACH * zoom).toInt()
            Draw.outline(g, Rect(xs[i], ys[i], w, h).grow(reach), Palette.fade(Palette.brass, 1f - age / BURST_SECONDS))
        }
    }
    spawned.forEach { burst(xs[it] + w / 2, ys[it] + h / 2, nodes[it].accent) }
    if (hovered >= 0) nodes[hovered].tip?.let { tip -> tooltip("$key:node:$hovered", Rect(xs[hovered], ys[hovered], w, h).intersect(r)) { tip() } }
    if (hovering(r) && selected != null) {
        val dx = if (input.takeKey(GLFW.GLFW_KEY_LEFT) { it.ctrl } != null) -1 else if (input.takeKey(GLFW.GLFW_KEY_RIGHT) { it.ctrl } != null) 1 else 0
        val dy = if (input.takeKey(GLFW.GLFW_KEY_UP) { it.ctrl } != null) -1 else if (input.takeKey(GLFW.GLFW_KEY_DOWN) { it.ctrl } != null) 1 else 0
        if (dx != 0 || dy != 0) state.neighbour(selected, dx, dy)?.let { clicked = it; state.center(it) }
    }
    vp.interact(this, r, key = key)
    return clicked
}

private fun edgeVisible(r: Rect, px: Int, py: Int, cx: Int, cy: Int, w: Int, h: Int): Boolean {
    val left = minOf(px, cx)
    val right = maxOf(px, cx) + w
    val top = minOf(py, cy)
    val bottom = maxOf(py, cy) + h
    return right >= r.x && left <= r.right && bottom >= r.y && top <= r.bottom
}

private fun edgeColor(met: Boolean, lit: Boolean, focusAmount: Float): Int {
    if (lit) return Palette.brass
    val base = if (met) Palette.alpha(Palette.success, 0xC8) else Palette.alpha(Palette.textSecondary, 0xA0)
    return Palette.fade(base, 1f - FOCUS_EDGE_FADE * focusAmount)
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

private fun Ui.drawNode(node: TechNode, index: Int, x: Int, y: Int, zoom: Double, state: TechTreeState) {
    val hover = state.hoverOf(index)
    val select = state.selectOf(index)
    val dim = state.dimOf(index)
    val overview = zoom < LOD_ZOOM
    g.pose().pushPose()
    g.pose().translate(x.toFloat(), y - hover * zoom.toFloat(), 0f)
    g.pose().scale(zoom.toFloat(), zoom.toFloat(), 1f)
    Draw.shadow(g, LOCAL, if (hover > LIFT_SHADOW_AT) 2 else 1)
    if (node.ready) Draw.glow(g, LOCAL, node.accent, READY_GLOW + READY_GLOW_SWING * pulse(PULSE_MS, index * PULSE_PHASE))
    Draw.glow(g, LOCAL, Palette.brass, select)
    val fill = Palette.mix(Palette.mix(Palette.raised, Palette.surface, dim), Palette.hover, hover)
    Draw.box(g, LOCAL, fill, Palette.mix(Palette.alpha(node.accent, 0xA0), Palette.brass, select))
    if (node.highlight) Draw.outline(g, LOCAL.grow(1), Palette.warning)
    Draw.fill(g, LOCAL.inset(1, 1, 0, 1).left(2), node.accent)
    val textColor = Palette.mix(Palette.text, Palette.textMuted, dim)
    Draw.text(g, state.label(index, node), node.textX, LABEL_Y, textColor)
    if (!node.item.isEmpty) g.renderItem(node.item, ITEM_X, ITEM_Y)
    if (node.locked) {
        g.pose().pushPose()
        g.pose().translate(0f, 0f, BADGE_Z)
        Draw.tinted(g, Icons.LOCK.sprite, LOCK_BADGE, Palette.warning)
        g.pose().popPose()
    }
    if (!overview) {
        if (!node.locked) node.status?.let { Draw.leadIcon(g, it, TECH_NODE_W - STATUS_ICON_X, STATUS_ICON_Y, node.accent) }
    }
    val countdown = node.countdown
    if (countdown != null) {
        val paused = !countdown.running
        val fraction = countdown.fraction(wallMillis)
        val tone = if (paused) Palette.textMuted else Palette.brass
        Draw.thinBar(g, BAR, fraction, tone)
        if (paused) Draw.hatch(g, BAR.withWidth((BAR.w * fraction).toInt()), Palette.alpha(Palette.canvas, PAUSED_ALPHA), HATCH_SPACING)
        if (!overview) Draw.text(g, state.timeLeft(index, countdown.remaining(wallMillis)), node.textX, TIME_Y, tone)
    } else node.progress?.let { Draw.thinBar(g, BAR, it.toFloat(), TASK_BAR) }
    val faded = state.fadeOf(index)
    if (faded > 0.02f) {
        g.pose().pushPose()
        g.pose().translate(0f, 0f, VEIL_Z)
        Draw.veil(g, LOCAL, Palette.canvas, faded * FOCUS_VEIL)
        g.pose().popPose()
    }
    g.pose().popPose()
    if (overview && !node.locked) node.status?.let { Draw.tintedIcon(g, it, x + (TECH_NODE_W * zoom).toInt() - Draw.ICON, y + (TECH_NODE_H * zoom).toInt() / 2 - Draw.ICON / 2, Draw.ICON, node.accent) }
}

private fun Ui.drawLanes(r: Rect, state: TechTreeState, labels: Map<Int, String>, zoom: Double) {
    val vp = state.viewport
    val list = state.lanes
    val gap = ((TECH_NODE_H + GAP_Y) * zoom).toInt()
    for (i in list.indices) {
        val lane = list[i]
        val top = vp.screenY(worldY(Cell(0, lane.firstRow)) - GAP_Y / 2.0).toInt()
        val bottom = vp.screenY(worldY(Cell(0, lane.firstRow + lane.rows)) - GAP_Y / 2.0).toInt()
        if (bottom < r.y || top - gap > r.bottom) continue
        if (i % 2 == 1) g.fill(r.x, max(top, r.y), r.right, minOf(bottom, r.bottom), Palette.alpha(Palette.text, LANE_ALPHA))
        if (i > 0 && top - gap / 2 in r.y..r.bottom) g.fill(r.x, top - gap / 2, r.right, top - gap / 2 + 1, Palette.border)
        val label = labels[lane.id] ?: continue
        val y = top - Draw.LINE - LANE_LABEL_LIFT
        if (gap / 2 >= Draw.LINE && y >= r.y && y < r.bottom) Draw.text(g, label, r.x + LANE_LABEL_PAD, y, Palette.textSecondary)
    }
}

private fun Ui.drawBands(r: Rect, state: TechTreeState, bands: TechBands) {
    val vp = state.viewport
    val list = state.bands
    for (i in list.indices) {
        val band = list[i]
        val left = vp.screenX(bandEdge(band.firstCol)).toInt()
        val right = vp.screenX(bandEdge(band.firstCol + band.cols)).toInt()
        if (right < r.x || left > r.right) continue
        val reached = band.id <= bands.current
        val current = band.id == bands.current
        val from = max(left, r.x)
        val to = minOf(right, r.right)
        if (current) g.fill(from, r.y, to, r.bottom, Palette.alpha(Palette.brass, BAND_CURRENT_ALPHA))
        else if (!reached) g.fill(from, r.y, to, r.bottom, Palette.alpha(Palette.canvas, BAND_LOCKED_ALPHA))
        if (i > 0) g.fill(left, r.y, left + 1, r.bottom, if (reached) Palette.borderStrong else Palette.border)
        val label = bands.labels[band.id] ?: continue
        val color = when { current -> Palette.brass; reached -> Palette.textSecondary; else -> Palette.textMuted }
        val room = Draw.width(label) + if (reached) 0 else Draw.ICON
        val x = minOf(max(left + BAND_LABEL_PAD, r.x + BAND_LABEL_PAD), right - room - BAND_LABEL_PAD)
        if (x < left) continue
        val after = if (reached) x else x + Draw.leadIcon(g, Icons.LOCK, x, r.y + BAND_LOCK_Y, Palette.warning)
        Draw.text(g, label, after, r.y + BAND_LABEL_Y, color)
        val labelBox = Rect(x, r.y, room, BAND_LABEL_Y + Draw.LINE)
        if (!reached && hovering(labelBox)) tooltip("band:${band.id}", labelBox, Lock.level(band.id).how)
    }
}
