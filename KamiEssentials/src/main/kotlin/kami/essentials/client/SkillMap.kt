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
import kami.libs.ui.map.Viewport
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
private const val ARC_STEP = 14.0
private const val ARC_ANGLE = 0.1

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
    Effects.SILVER_TONGUE, Effects.RAVAGER_TAMER, Effects.DROWNED_TRUCE -> Icons.HANDSHAKE
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

    fun fit(vp: Viewport) {
        val r = SkillTree.radius
        vp.fit(-r, -r, 2 * r, 2 * r, 0.96)
        vp.unitsPerPx = vp.unitsPerPx.coerceIn(vp.minUnitsPerPx, vp.maxUnitsPerPx)
    }

    fun hit(ui: Ui, r: Rect, vp: Viewport): SkillNode? {
        if (!ui.hovering(r)) return null
        val wx = vp.worldX(ui.mouseX.toDouble())
        val wy = vp.worldZ(ui.mouseY.toDouble())
        val slack = 3 * vp.unitsPerPx
        return SkillTree.nodes.firstOrNull { n -> hypot(n.x - wx, n.y - wy) <= size(n.kind) / 2 + slack }
    }

    fun draw(ui: Ui, r: Rect, vp: Viewport, v: SkillView, s: NodeState, selected: Int?, hovered: Int?) {
        val upp = vp.unitsPerPx
        val zoom = 1.0 / upp
        val mx = r.x + r.w / 2.0
        val my = r.y + r.h / 2.0
        fun sx(x: Double) = (mx + (x - vp.cx) * zoom).toFloat()
        fun sy(y: Double) = (my + (y - vp.cz) * zoom).toFloat()
        val ox = sx(0.0)
        val oy = sy(0.0)
        val pulse = (0.65 + 0.35 * sin(System.currentTimeMillis() / 260.0)).toFloat()
        val nodes = SkillTree.nodes
        val mine = SkillClass.of(v.cls)
        val left = r.x - 40f
        val right = r.right + 40f
        val top = r.y - 40f
        val bottom = r.bottom + 40f
        val step = 2 * PI / SkillClass.entries.size
        batch.begin(ui.g)
        try {
            batch.rect(r.x.toFloat(), r.y.toFloat(), r.right.toFloat(), r.bottom.toFloat(), SOLID, 0xFF0A0B11.toInt())
            batch.sprite(SOFT, ox, oy, (SkillTree.radius * 2.2 * zoom).toFloat() * PAD, 0x38141A2C)
            val rad = (SkillTree.radius * zoom).toFloat()
            val near = (60 * zoom).toFloat()
            val mid = (220 * zoom).toFloat()
            SkillClass.entries.forEachIndexed { i, cls ->
                val a = i * step - PI / 2
                val peak = Palette.alpha(cls.color, if (cls == mine) 0x30 else 0x16)
                val clear = Palette.alpha(cls.color, 0)
                batch.wedge(ox, oy, near, mid, a - step / 2, a + step / 2, 24, SOLID, clear, peak)
                batch.wedge(ox, oy, mid, rad, a - step / 2, a + step / 2, 24, SOLID, peak, clear)
            }
            batch.sprite(GLOW, ox, oy, (260 * zoom).toFloat() * PAD, Palette.alpha(mine?.color ?: GOLD, 0x40))
            val z = zoom.toFloat()
            ring(ox, oy, 75 * z, max(1.5f, 3 * z), Palette.alpha(GOLD, 0x80))
            ring(ox, oy, 64 * z, max(1f, 1.2f * z), Palette.alpha(GOLD, 0x50))
            for (k in 0 until 8) {
                val t = k * PI / 4
                batch.line(ox + (cos(t) * 64 * z).toFloat(), oy + (sin(t) * 64 * z).toFloat(), ox + (cos(t) * 86 * z).toFloat(), oy + (sin(t) * 86 * z).toFloat(), max(1f, 1.5f * z), SOLID, Palette.alpha(GOLD, 0x60))
            }
            val shade = 0xB0000000.toInt()
            val l = r.x.toFloat()
            val t = r.y.toFloat()
            val rt = r.right.toFloat()
            val bt = r.bottom.toFloat()
            batch.quad(l, t, rt, t + 36, SOLID, shade, 0, 0, shade)
            batch.quad(l, bt - 36, rt, bt, SOLID, 0, shade, shade, 0)
            batch.quad(l, t, l + 36, bt, SOLID, shade, shade, 0, 0)
            batch.quad(rt - 36, t, rt, bt, SOLID, 0, 0, shade, shade)
            SkillTree.constellations.forEach { c ->
                batch.sprite(SOFT, sx(c.x), sy(c.y), ((c.r * 2 + 34) * zoom).toFloat() * PAD, Palette.alpha(c.cls.color, if (s.done[c.id]) 0x55 else 0x1C))
            }
            val thin = max(1.0, 1.4 * zoom.coerceAtMost(1.5)).toFloat()
            SkillTree.edges.forEachIndexed { i, e ->
                val a = s.rank[e.a] > 0
                val b = s.rank[e.b] > 0
                val lit = (a && s.open[e.b]) || (b && s.open[e.a])
                val color = when {
                    a && b -> Palette.alpha(nodes[e.a].cls.color, 0xF0)
                    lit -> Palette.alpha(Palette.brass, (0x90 * pulse).toInt() + 0x40)
                    else -> 0x7048485A
                }
                val width = when {
                    a && b -> thin * 2.2f
                    lit -> thin * 1.6f
                    else -> thin
                }
                val pts = lines[i]
                val n = pts.size / 2
                var x0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE
                var y0 = Float.MAX_VALUE
                var y1 = -Float.MAX_VALUE
                for (k in 0 until n) {
                    val px = sx(pts[2 * k].toDouble())
                    val py = sy(pts[2 * k + 1].toDouble())
                    xs[k] = px
                    ys[k] = py
                    if (px < x0) x0 = px
                    if (px > x1) x1 = px
                    if (py < y0) y0 = py
                    if (py > y1) y1 = py
                }
                if (x1 < left || x0 > right || y1 < top || y0 > bottom) return@forEachIndexed
                batch.path(xs, ys, n, width, SOLID, color)
            }
            nodes.forEach { n ->
                val x = sx(n.x)
                val y = sy(n.y)
                if (x < left || x > right || y < top || y > bottom) return@forEach
                val d = max(3.0, size(n.kind) * zoom).toFloat()
                val rank = s.rank[n.id]
                val big = n.kind == NodeKind.CORE || n.kind == NodeKind.KEYSTONE || n.kind == NodeKind.START
                if (s.open[n.id]) batch.sprite(GLOW, x, y, d * 2.4f * PAD, Palette.alpha(Palette.brass, (0xB0 * pulse).toInt()))
                else if (rank > 0 && big) batch.sprite(GLOW, x, y, d * 2.6f * PAD, Palette.alpha(n.cls.color, 0x90))
                if (s.match[n.id]) batch.sprite(GLOW, x, y, d * 2.8f * PAD, Palette.alpha(MATCH, 0xD0))
                val fill = when {
                    rank > 0 -> Palette.alpha(if (rank >= n.ranks) n.cls.color else Palette.mix(n.cls.color, 0xFF000000.toInt(), 0.3f), 0xFF)
                    s.open[n.id] -> Palette.alpha(Palette.mix(n.cls.color, 0xFF000000.toInt(), 0.55f), 0xFF)
                    else -> LOCKED_FILL
                }
                val inner = if (n.kind == NodeKind.TRAVEL) DIAMOND else DISC
                val solid = n.kind == NodeKind.KEYSTONE || n.kind == NodeKind.START || n.kind == NodeKind.TRAVEL
                batch.sprite(inner, x, y, d * (if (solid) 0.9f else 0.84f) * PAD, fill)
                val edge = when {
                    n.id == selected -> 0xFFFFFFFF.toInt()
                    n.id == hovered -> Palette.mix(GOLD, 0xFFFFFFFF.toInt(), 0.5f)
                    s.match[n.id] -> MATCH
                    rank > 0 -> if (big) GOLD else if (n.kind == NodeKind.BONUS) Palette.mix(BONUS_ACCENT, 0xFFFFFFFF.toInt(), 0.35f) else Palette.mix(n.cls.color, 0xFFFFFFFF.toInt(), 0.35f)
                    s.open[n.id] -> Palette.brass
                    s.blocked[n.id] -> BLOCKED
                    n.kind == NodeKind.BONUS -> BONUS_ACCENT
                    big || n.kind == NodeKind.NOTABLE -> Palette.mix(LOCKED_FRAME, n.cls.color, 0.35f)
                    else -> LOCKED_FRAME
                }
                batch.sprite(frame(n.kind), x, y, d * PAD, edge)
            }
        } finally {
            batch.end()
        }
        if (zoom >= ICON_ZOOM) {
            icons.begin(ui.g)
            try {
                val phys = 1f / icons.px
                nodes.forEach { n ->
                    if (n.kind == NodeKind.MINOR || n.kind == NodeKind.TRAVEL) return@forEach
                    val x = sx(n.x)
                    val y = sy(n.y)
                    if (x < left || x > right || y < top || y > bottom) return@forEach
                    val d = (size(n.kind) * zoom * 0.5).toFloat().coerceAtMost(24f)
                    val k = floor(d * phys / 16f)
                    if (k < 1f) return@forEach
                    val dd = k * 16f / phys
                    val x0 = Math.round((x - dd / 2) * phys) / phys
                    val y0 = Math.round((y - dd / 2) * phys) / phys
                    val region = iconRegions.getOrPut(nodeIcons[n.id]) { AtlasRegion.gui(nodeIcons[n.id].sprite) }
                    icons.rect(x0, y0, x0 + dd, y0 + dd, region, if (s.rank[n.id] > 0) 0xFFFFFFFF.toInt() else 0xFF9A9AA6.toInt())
                }
            } finally {
                icons.end()
            }
        }
        SkillClass.entries.forEachIndexed { i, cls ->
            val a = i * step - PI / 2
            val rr = SkillTree.radius + 26
            val label = cls.label()
            val x = sx(cos(a) * rr).toInt()
            val y = sy(sin(a) * rr).toInt()
            Draw.text(ui.g, label, x - Draw.width(label, TextStyle.HEADING) / 2, y - 4, TextStyle.HEADING, if (cls == mine) cls.color else Palette.alpha(cls.color, 0xC0))
        }
        if (zoom >= LABEL_ZOOM) nodes.forEach { n ->
            if (n.kind == NodeKind.MINOR || n.kind == NodeKind.TRAVEL) return@forEach
            val x = sx(n.x).toInt()
            val y = sy(n.y).toInt()
            if (x < r.x || x > r.right || y < r.y || y > r.bottom) return@forEach
            val text = if (n.ranks > 1) "${s.rank[n.id]}/${n.ranks}" else n.title()
            val ty = y + (size(n.kind) * zoom / 2).toInt() + 2
            Draw.text(ui.g, text, x - Draw.width(text) / 2, ty, if (s.rank[n.id] > 0) GOLD else Palette.textMuted, shadow = true)
        }
    }
}

fun SkillClass.label() = tr("kami_libs.skill.class.$id")

fun SkillNode.title(): String = if (kind == NodeKind.START) cls.label() else tr("kami_essentials.skill.name.$name")
