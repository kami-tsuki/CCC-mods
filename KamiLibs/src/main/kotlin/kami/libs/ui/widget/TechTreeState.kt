package kami.libs.ui.widget

import kami.libs.ui.anim.Ease
import kami.libs.ui.graph.Band
import kami.libs.ui.graph.Cell
import kami.libs.ui.graph.GraphLod
import kami.libs.ui.graph.GraphModel
import kami.libs.ui.graph.GraphView
import kami.libs.ui.graph.Lane
import kami.libs.ui.graph.TechLayout
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kotlin.math.max

private const val MOTION_SPEED = 16f
internal const val BURST_SECONDS = 0.6f
private const val ICON_ROOM = 14
private const val ZOOM_IN = 2.0
private val LEVELS = GraphLod(0.3, 0.6)

class TechTreeState {
    val view = GraphView(LEVELS, ZOOM_IN)
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
    private var hover = FloatArray(0)
    private var select = FloatArray(0)
    private var dim = FloatArray(0)
    private var primary = IntArray(0)
    private var burstAge = FloatArray(0)
    private val bursts = ArrayList<Int>()

    fun refit() = view.refit()

    fun center(node: Int) = view.centerOn(node)

    fun burst(node: Int) { bursts += node }

    fun takeClear() = view.takeClear()

    internal fun isPrimary(parent: Int, child: Int) = primary[child] == parent

    fun layout(nodes: List<TechNode>): List<Cell> {
        if (nodes !== lastNodes) {
            lastNodes = nodes
            labels.fill(null)
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
                view.load(model(nodes))
            }
        }
        if (hover.size != nodes.size) {
            hover = FloatArray(nodes.size) { -1f }
            select = FloatArray(nodes.size) { -1f }
            dim = FloatArray(nodes.size) { -1f }
            burstAge = FloatArray(nodes.size) { -1f }
            labels = arrayOfNulls(nodes.size)
            times = arrayOfNulls(nodes.size)
            timeMinutes = LongArray(nodes.size) { -1L }
        }
        return cells
    }

    private fun model(nodes: List<TechNode>): GraphModel {
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        nodes.forEachIndexed { i, node -> node.parents.forEach { p -> if (p in nodes.indices && p !in node.hidden) { from += p; to += i } } }
        val n = nodes.size
        return GraphModel(
            DoubleArray(n) { worldX(cells[it]) + TECH_NODE_W / 2.0 },
            DoubleArray(n) { worldY(cells[it]) + TECH_NODE_H / 2.0 },
            DoubleArray(n) { TECH_NODE_W.toDouble() },
            DoubleArray(n) { TECH_NODE_H.toDouble() },
            from.toIntArray(), to.toIntArray(),
            edgePad = max(GAP_X, GAP_Y).toDouble(), margin = GAP_X / 2.0
        )
    }

    internal fun label(i: Int, node: TechNode): String =
        labels[i] ?: Draw.fit(node.label, TECH_NODE_W - node.textX - TEXT_PAD - if (node.status != null && !node.locked) ICON_ROOM else 0).also { labels[i] = it }

    internal fun timeLeft(i: Int, ms: Long): String {
        val remaining = ms.coerceAtLeast(0)
        val tick = if (remaining < 60_000) remaining / 1000 else 1000 + remaining / 60_000
        val cached = times[i]
        if (cached != null && timeMinutes[i] == tick) return cached
        timeMinutes[i] = tick
        return Format.duration(ms).also { times[i] = it }
    }

    internal fun settle(i: Int, hovered: Boolean, selected: Boolean, dimmed: Boolean, dt: Float, instant: Boolean) {
        hover[i] = approach(hover[i], hovered, dt, instant)
        select[i] = approach(select[i], selected, dt, instant)
        dim[i] = approach(dim[i], dimmed, dt, instant)
    }

    private fun approach(value: Float, on: Boolean, dt: Float, instant: Boolean): Float {
        val target = if (on) 1f else 0f
        return if (value < 0f || instant) target else Ease.approach(value, target, MOTION_SPEED, dt)
    }

    internal fun hoverOf(i: Int) = hover[i]
    internal fun selectOf(i: Int) = select[i]
    internal fun dimOf(i: Int) = dim[i]

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
}
