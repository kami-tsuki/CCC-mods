package kami.essentials.client

import kami.essentials.KamiEssentials
import kami.essentials.skills.Effects
import kami.essentials.skills.NodeKind
import kami.essentials.skills.SkillEdge
import kami.essentials.skills.SkillNode
import kami.essentials.skills.SkillTree
import kami.essentials.skills.SkillView
import kami.libs.skills.SkillClass
import kami.libs.skills.Skills
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.graph.GraphLod
import kami.libs.ui.graph.GraphModel
import kami.libs.ui.graph.GraphView
import kami.libs.ui.graph.Lod
import kami.libs.ui.style.AtlasRegion
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.QuadBatch
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import net.minecraft.resources.ResourceLocation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val ATLAS_CELL = 256
private const val ATLAS_SIZE = 1024
private const val PAD = 8f / 7f
private fun cell(c: Int, r: Int) = AtlasRegion.cell(c, r, ATLAS_CELL, ATLAS_SIZE)
private val SOLID = cell(0, 0).let { AtlasRegion(it.uc, it.vc, it.uc, it.vc) }
private val DISC = cell(1, 0)
private val GLOW = cell(2, 0)
private val SOFT = cell(3, 0)
private val RING = cell(0, 1)
private val NOTABLE = cell(1, 1)
private val CORE = cell(2, 1)
private val KEYSTONE = cell(3, 1)
private val START = cell(0, 2)
private val DIAMOND = cell(1, 2)
private val DIAMOND_FRAME = cell(2, 2)
private val HEX = cell(3, 2)
private const val BONUS_ACCENT = 0xFFA58BFF.toInt()
private const val GOLD = 0xFFE8C76A.toInt()
private const val LOCKED_FILL = 0xFF16161D.toInt()
private const val LOCKED_FRAME = 0xFF6A6A76.toInt()
private const val MATCH = 0xFF6FE3FF.toInt()
private const val BLOCKED = 0xFFB04A4A.toInt()
private const val ICON_ZOOM = 0.42
private const val LABEL_ZOOM = 1.1
private const val MAX_ZOOM = 3.0
private const val HIT_SLACK = 3
private const val MARGIN = 40.0
private const val ARC_STEP = 14.0
private const val ARC_ANGLE = 0.1
private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val SHADE = 0xB0000000.toInt()
private const val BACKGROUND = 0xFF0A0B11.toInt()
private const val ICON_LOCKED = 0xFF9A9AA6.toInt()
private const val VIGNETTE = 36f
private const val WEDGE_NEAR = 60
private const val WEDGE_MID = 220
private const val WEDGE_STEPS = 24
private const val HALO = 260
private const val HUB_OUTER = 75
private const val HUB_INNER = 64
private const val HUB_SPOKE = 86
private const val CONSTELLATION_PAD = 34
private const val CLASS_LABEL_PAD = 26
private const val ACTIVE_EDGE = 2.2f
private const val LIT_EDGE = 1.6f
private val CLASS_STEP = 2 * PI / SkillClass.entries.size

fun SkillClass.icon(): Icon = when (this) {
    SkillClass.RESEARCHER -> Icons.SCROLL
    SkillClass.TRADER -> Icons.SCALES
    SkillClass.FARMER -> Icons.WHEAT
    SkillClass.FISHER -> Icons.WATER
    SkillClass.LUMBER -> Icons.PINE
    SkillClass.MINER -> Icons.PICKAXE
    SkillClass.SOLDIER -> Icons.SHIELD
    SkillClass.WORKER -> Icons.GEAR
}

fun SkillNode.icon(): Icon = if (kind == NodeKind.START) cls.icon() else if (kind == NodeKind.KEYSTONE) Icons.CROWN else when (effects.keys.first()) {
    Skills.RESEARCH_TIME, Effects.SECOND_WIND -> Icons.CLOCK
    Skills.RESEARCH_COST, Effects.ORE_FORTUNE, Skills.INFINITE_LOTS -> Icons.COIN
    Skills.RESEARCH_INSIGHT, Effects.DARK_SIGHT, Effects.FARMLAND_CALM, Effects.WATER_SIGHT, Effects.SLEEPLESS -> Icons.EYE
    Skills.SCAN_RANGE -> Icons.MAP
    Skills.AUCTION_FEE, Skills.AUCTION_SLOTS -> Icons.SCALES
    Skills.ORDER_SLOTS, Effects.EAT_PAPER -> Icons.SCROLL
    Effects.TRADE_XP, Effects.RAVAGER_TAMER, Effects.DROWNED_TRUCE -> Icons.HANDSHAKE
    Effects.CLASS_XP, Effects.KILL_XP, Effects.ORE_XP, Effects.FISH_XP -> Icons.STATS
    Effects.ANVIL_SAVE, Effects.DURABILITY_SAVE, Effects.ROD_SAVE, Effects.TOOL_PICKAXE, Effects.TOOL_HOE, Effects.TOOL_AXE, Effects.SAWMILL, Effects.PAPER_PRESS, Effects.POCKET_CRAFT -> Icons.TOOL
    Effects.CROP_FORTUNE, Effects.REPLANT, Effects.VEGGIE_FOOD, Effects.HOE_DAMAGE -> Icons.WHEAT
    Effects.CROP_AURA, Effects.SAPLING_AURA, Effects.LOG_YIELD, Effects.LEAF_DROPS, Effects.LEAF_LANDING -> Icons.TREE
    Effects.BREED -> Icons.PEOPLE
    Effects.BONEMEAL_SAVE, Effects.DEEPSLATE -> Icons.BLOCK
    Effects.DOUBLE_CATCH, Effects.FISH_BREATH, Effects.FISH_FOOD, Effects.SEA_GRACE, Effects.SEA_CHILD, Effects.BUCKET_FISHER, "swim", "oxygen" -> Icons.WATER
    Effects.AXE_SPEED, Effects.AXE_DAMAGE, Effects.LEAF_CUTTER, Effects.TREEHUGGER -> Icons.PINE
    "mining_speed", Effects.PICKAXE_DAMAGE, Effects.DEEP_SPEED, "submerged" -> Icons.PICKAXE
    Effects.VEIN, Effects.MAGNET -> Icons.CHAIN
    "armor", "toughness", "knockback", "blast_guard", Effects.BULWARK -> Icons.SHIELD
    Effects.SWORD_DAMAGE, Effects.DAMAGE_DEALT, Effects.BERSERK, Effects.BLOODLUST, "fire_guard", Effects.BREW_DURATION, Effects.BUTCHER, Effects.LAVA_WALKER -> Icons.FIRE
    "health", Effects.HEADHUNTER -> Icons.PERSON
    Effects.MACHINE_SPEED -> Icons.GEAR
    Effects.SHOWMAN -> Icons.CROWN
    Effects.ENDER_SATCHEL -> Icons.CHEST
    Effects.CANARY -> Icons.BELL
    "reach", Effects.HORSE_WHISPERER -> Icons.HAND
    Effects.LADDER_RUSH, "speed", "attack_speed", "step", "jump", "sneak_speed" -> Icons.FORWARD
    else -> Icons.STAR
}

private fun size(kind: NodeKind) = when (kind) {
    NodeKind.START -> 46.0
    NodeKind.KEYSTONE -> 40.0
    NodeKind.CORE -> 34.0
    NodeKind.NOTABLE -> 26.0
    NodeKind.MINOR -> 15.0
    NodeKind.TRAVEL -> 12.0
    NodeKind.BONUS -> 18.0
}

private fun frame(kind: NodeKind) = when (kind) {
    NodeKind.START -> START
    NodeKind.KEYSTONE -> KEYSTONE
    NodeKind.CORE -> CORE
    NodeKind.NOTABLE -> NOTABLE
    NodeKind.MINOR -> RING
    NodeKind.TRAVEL -> DIAMOND_FRAME
    NodeKind.BONUS -> HEX
}

class NodeState(val rank: IntArray, val open: BooleanArray, val match: BooleanArray, val done: BooleanArray, val blocked: BooleanArray)

object SkillMap {
    private val atlas = ResourceLocation.fromNamespaceAndPath(KamiEssentials.ID, "textures/gui/skill_atlas.png")
    private val batch = QuadBatch(atlas, mips = 4)
    private val icons = QuadBatch(AtlasRegion.GUI_ATLAS)
    private val iconRegions = HashMap<Icon, AtlasRegion>()
    private val nodeIcons by lazy { SkillTree.nodes.map { it.icon() } }
    private val lines: List<FloatArray> by lazy { SkillTree.edges.map(::polyline) }
    private val xs = FloatArray(160)
    private val ys = FloatArray(160)

    private fun polyline(e: SkillEdge): FloatArray {
        val a = SkillTree.nodes[e.a]
        val b = SkillTree.nodes[e.b]
        if (!e.arc) return floatArrayOf(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
        val a0 = atan2(a.y - e.cy, a.x - e.cx)
        var d = atan2(b.y - e.cy, b.x - e.cx) - a0
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        val r = (hypot(a.x - e.cx, a.y - e.cy) + hypot(b.x - e.cx, b.y - e.cy)) / 2
        val steps = max(ceil(abs(d) / ARC_ANGLE), ceil(abs(d) * r / ARC_STEP)).toInt().coerceIn(2, 48)
        return FloatArray((steps + 1) * 2) { i -> val t = a0 + d * (i / 2).toDouble() / steps; (if (i % 2 == 0) e.cx + cos(t) * r else e.cy + sin(t) * r).toFloat() }
    }

    private fun ring(x: Float, y: Float, rad: Float, w: Float, color: Int) {
        val n = (rad / 2).toInt().coerceIn(64, 157)
        for (i in 0..n + 1) {
            val t = 2 * PI * i / n
            xs[i] = x + (cos(t) * rad).toFloat()
            ys[i] = y + (sin(t) * rad).toFloat()
        }
        batch.path(xs, ys, n + 2, w, SOLID, color)
    }

    fun state(v: SkillView, query: String, label: (SkillNode) -> String): NodeState {
        val nodes = SkillTree.nodes
        val q = query.trim().lowercase()
        val mine = SkillClass.of(v.cls)
        val free = v.points - v.spent
        val done = BooleanArray(SkillTree.constellations.size) { c -> SkillTree.constellations[c].nodes.all { it in v.nodes } }
        return NodeState(
            IntArray(nodes.size) { v.nodes[it] ?: 0 },
            BooleanArray(nodes.size) { SkillTree.denial(v.nodes, mine, v.level, free, nodes[it]) == null },
            BooleanArray(nodes.size) { q.isNotEmpty() && label(nodes[it]).lowercase().contains(q) },
            done,
            BooleanArray(nodes.size) { v.cls != null && (v.nodes[it] ?: 0) == 0 && SkillTree.blocked(v.nodes, nodes[it]) }
        )
    }

    private val model by lazy {
        val nodes = SkillTree.nodes
        val box = DoubleArray(lines.size * 4)
        lines.forEachIndexed { e, pts ->
            var x0 = Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for (k in 0 until pts.size / 2) {
                x0 = min(x0, pts[2 * k].toDouble()); x1 = max(x1, pts[2 * k].toDouble())
                y0 = min(y0, pts[2 * k + 1].toDouble()); y1 = max(y1, pts[2 * k + 1].toDouble())
            }
            box[4 * e] = x0; box[4 * e + 1] = y0; box[4 * e + 2] = x1; box[4 * e + 3] = y1
        }
        GraphModel(
            DoubleArray(nodes.size) { nodes[it].x }, DoubleArray(nodes.size) { nodes[it].y },
            DoubleArray(nodes.size) { size(nodes[it].kind) }, DoubleArray(nodes.size) { size(nodes[it].kind) },
            IntArray(lines.size) { SkillTree.edges[it].a }, IntArray(lines.size) { SkillTree.edges[it].b }, box, margin = MARGIN
        )
    }

    fun view() = GraphView(GraphLod(ICON_ZOOM, LABEL_ZOOM), MAX_ZOOM, HIT_SLACK).also { it.load(model) }

    fun draw(ui: Ui, r: Rect, view: GraphView, v: SkillView, s: NodeState) {
        val mine = SkillClass.of(v.cls)
        val pulse = (0.65 + 0.35 * sin(System.currentTimeMillis() / 260.0)).toFloat()
        batch.begin(ui.g)
        try {
            drawBackdrop(r, view, mine)
            drawVignette(r)
            drawConstellations(view, s)
            drawEdges(view, s, pulse)
            drawNodes(view, s, pulse)
        } finally {
            batch.end()
        }
        if (view.lod != Lod.OVERVIEW) drawIcons(ui, view, s)
        drawClassLabels(ui, view, mine)
        if (view.lod == Lod.DETAIL) drawNodeLabels(ui, view, s)
    }

    private fun GraphView.fx(x: Double) = sx(x).toFloat()

    private fun GraphView.fy(y: Double) = sy(y).toFloat()

    private fun drawBackdrop(r: Rect, view: GraphView, mine: SkillClass?) {
        val zoom = view.zoom
        val z = zoom.toFloat()
        val ox = view.fx(0.0)
        val oy = view.fy(0.0)
        batch.rect(r.x.toFloat(), r.y.toFloat(), r.right.toFloat(), r.bottom.toFloat(), SOLID, BACKGROUND)
        batch.sprite(SOFT, ox, oy, (SkillTree.radius * 2.2 * zoom).toFloat() * PAD, 0x38141A2C)
        val rad = (SkillTree.radius * zoom).toFloat()
        val near = (WEDGE_NEAR * zoom).toFloat()
        val mid = (WEDGE_MID * zoom).toFloat()
        SkillClass.entries.forEachIndexed { i, cls ->
            val a = i * CLASS_STEP - PI / 2
            val peak = Palette.alpha(cls.color, if (cls == mine) 0x30 else 0x16)
            val clear = Palette.alpha(cls.color, 0)
            batch.wedge(ox, oy, near, mid, a - CLASS_STEP / 2, a + CLASS_STEP / 2, WEDGE_STEPS, SOLID, clear, peak)
            batch.wedge(ox, oy, mid, rad, a - CLASS_STEP / 2, a + CLASS_STEP / 2, WEDGE_STEPS, SOLID, peak, clear)
        }
        batch.sprite(GLOW, ox, oy, (HALO * zoom).toFloat() * PAD, Palette.alpha(mine?.color ?: GOLD, 0x40))
        ring(ox, oy, HUB_OUTER * z, max(1.5f, 3 * z), Palette.alpha(GOLD, 0x80))
        ring(ox, oy, HUB_INNER * z, max(1f, 1.2f * z), Palette.alpha(GOLD, 0x50))
        for (k in 0 until 8) {
            val t = k * PI / 4
            val c = cos(t).toFloat()
            val sn = sin(t).toFloat()
            batch.line(ox + c * HUB_INNER * z, oy + sn * HUB_INNER * z, ox + c * HUB_SPOKE * z, oy + sn * HUB_SPOKE * z, max(1f, 1.5f * z), SOLID, Palette.alpha(GOLD, 0x60))
        }
    }

    private fun drawVignette(r: Rect) {
        val l = r.x.toFloat()
        val t = r.y.toFloat()
        val rt = r.right.toFloat()
        val bt = r.bottom.toFloat()
        batch.quad(l, t, rt, t + VIGNETTE, SOLID, SHADE, 0, 0, SHADE)
        batch.quad(l, bt - VIGNETTE, rt, bt, SOLID, 0, SHADE, SHADE, 0)
        batch.quad(l, t, l + VIGNETTE, bt, SOLID, SHADE, SHADE, 0, 0)
        batch.quad(rt - VIGNETTE, t, rt, bt, SOLID, 0, 0, SHADE, SHADE)
    }

    private fun drawConstellations(view: GraphView, s: NodeState) {
        SkillTree.constellations.forEach { c ->
            batch.sprite(SOFT, view.fx(c.x), view.fy(c.y), ((c.r * 2 + CONSTELLATION_PAD) * view.zoom).toFloat() * PAD, Palette.alpha(c.cls.color, if (s.done[c.id]) 0x55 else 0x1C))
        }
    }

    private fun drawEdges(view: GraphView, s: NodeState, pulse: Float) {
        val thin = max(1.0, 1.4 * view.zoom.coerceAtMost(1.5)).toFloat()
        for (k in 0 until view.edgeCount) {
            val i = view.edges[k]
            val e = SkillTree.edges[i]
            val a = s.rank[e.a] > 0
            val b = s.rank[e.b] > 0
            val lit = (a && s.open[e.b]) || (b && s.open[e.a])
            val color = when {
                a && b -> Palette.alpha(SkillTree.nodes[e.a].cls.color, 0xF0)
                lit -> Palette.alpha(Palette.brass, (0x90 * pulse).toInt() + 0x40)
                else -> 0x7048485A
            }
            val width = when {
                a && b -> thin * ACTIVE_EDGE
                lit -> thin * LIT_EDGE
                else -> thin
            }
            val pts = lines[i]
            val n = pts.size / 2
            for (j in 0 until n) {
                xs[j] = view.fx(pts[2 * j].toDouble())
                ys[j] = view.fy(pts[2 * j + 1].toDouble())
            }
            batch.path(xs, ys, n, width, SOLID, color)
        }
    }

    private fun drawNodes(view: GraphView, s: NodeState, pulse: Float) {
        for (k in 0 until view.nodeCount) {
            val n = SkillTree.nodes[view.nodes[k]]
            val x = view.fx(n.x)
            val y = view.fy(n.y)
            val d = max(3.0, size(n.kind) * view.zoom).toFloat()
            val rank = s.rank[n.id]
            val big = n.kind == NodeKind.CORE || n.kind == NodeKind.KEYSTONE || n.kind == NodeKind.START
            if (s.open[n.id]) batch.sprite(GLOW, x, y, d * 2.4f * PAD, Palette.alpha(Palette.brass, (0xB0 * pulse).toInt()))
            else if (rank > 0 && big) batch.sprite(GLOW, x, y, d * 2.6f * PAD, Palette.alpha(n.cls.color, 0x90))
            if (s.match[n.id]) batch.sprite(GLOW, x, y, d * 2.8f * PAD, Palette.alpha(MATCH, 0xD0))
            val inner = if (n.kind == NodeKind.TRAVEL) DIAMOND else DISC
            val solid = n.kind == NodeKind.KEYSTONE || n.kind == NodeKind.START || n.kind == NodeKind.TRAVEL
            batch.sprite(inner, x, y, d * (if (solid) 0.9f else 0.84f) * PAD, fillColor(n, s))
            batch.sprite(frame(n.kind), x, y, d * PAD, frameColor(n, s, big, view.selected, view.hovered))
        }
    }

    private fun fillColor(n: SkillNode, s: NodeState): Int {
        val rank = s.rank[n.id]
        return when {
            rank > 0 -> Palette.opaque(if (rank >= n.ranks) n.cls.color else Palette.mix(n.cls.color, BLACK, 0.3f))
            s.open[n.id] -> Palette.opaque(Palette.mix(n.cls.color, BLACK, 0.55f))
            else -> LOCKED_FILL
        }
    }

    private fun frameColor(n: SkillNode, s: NodeState, big: Boolean, selected: Int, hovered: Int): Int = when {
        n.id == selected -> WHITE
        n.id == hovered -> Palette.mix(GOLD, WHITE, 0.5f)
        s.match[n.id] -> MATCH
        s.rank[n.id] > 0 -> if (big) GOLD else Palette.mix(if (n.kind == NodeKind.BONUS) BONUS_ACCENT else n.cls.color, WHITE, 0.35f)
        s.open[n.id] -> Palette.brass
        s.blocked[n.id] -> BLOCKED
        n.kind == NodeKind.BONUS -> BONUS_ACCENT
        big || n.kind == NodeKind.NOTABLE -> Palette.mix(LOCKED_FRAME, n.cls.color, 0.35f)
        else -> LOCKED_FRAME
    }

    private fun drawIcons(ui: Ui, view: GraphView, s: NodeState) {
        icons.begin(ui.g)
        try {
            val phys = 1f / icons.px
            for (j in 0 until view.nodeCount) {
                val n = SkillTree.nodes[view.nodes[j]]
                if (n.kind == NodeKind.MINOR || n.kind == NodeKind.TRAVEL) continue
                val x = view.fx(n.x)
                val y = view.fy(n.y)
                val d = (size(n.kind) * view.zoom * 0.5).toFloat().coerceAtMost(24f)
                val k = floor(d * phys / 16f)
                if (k < 1f) continue
                val dd = k * 16f / phys
                val x0 = Math.round((x - dd / 2) * phys) / phys
                val y0 = Math.round((y - dd / 2) * phys) / phys
                val region = iconRegions.getOrPut(nodeIcons[n.id]) { AtlasRegion.gui(nodeIcons[n.id].sprite) }
                icons.rect(x0, y0, x0 + dd, y0 + dd, region, if (s.rank[n.id] > 0) WHITE else ICON_LOCKED)
            }
        } finally {
            icons.end()
        }
    }

    private fun drawClassLabels(ui: Ui, view: GraphView, mine: SkillClass?) {
        SkillClass.entries.forEachIndexed { i, cls ->
            val a = i * CLASS_STEP - PI / 2
            val rr = SkillTree.radius + CLASS_LABEL_PAD
            val label = cls.label()
            val x = view.sx(cos(a) * rr).toInt()
            val y = view.sy(sin(a) * rr).toInt()
            Draw.text(ui.g, label, x - Draw.width(label, TextStyle.HEADING) / 2, y - 4, TextStyle.HEADING, if (cls == mine) cls.color else Palette.alpha(cls.color, 0xC0))
        }
    }

    private fun drawNodeLabels(ui: Ui, view: GraphView, s: NodeState) {
        for (j in 0 until view.nodeCount) {
            val n = SkillTree.nodes[view.nodes[j]]
            if (n.kind == NodeKind.MINOR || n.kind == NodeKind.TRAVEL) continue
            val x = view.sx(n.x).toInt()
            val y = view.sy(n.y).toInt()
            val text = if (n.ranks > 1) "${s.rank[n.id]}/${n.ranks}" else n.title()
            val ty = y + (size(n.kind) * view.zoom / 2).toInt() + 2
            Draw.text(ui.g, text, x - Draw.width(text) / 2, ty, if (s.rank[n.id] > 0) GOLD else Palette.textMuted, shadow = true)
        }
    }
}

fun SkillClass.label() = tr("kami_libs.skill.class.$id")

fun nameText(name: String) = tr("kami_essentials.skill.name.$name")

fun SkillNode.title(): String = if (kind == NodeKind.START) cls.label() else nameText(name)
