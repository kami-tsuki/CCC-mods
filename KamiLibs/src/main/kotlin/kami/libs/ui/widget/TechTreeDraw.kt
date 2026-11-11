package kami.libs.ui.widget

import kami.libs.ui.anim.pulse
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.graph.Band
import kami.libs.ui.graph.Cell
import kami.libs.ui.graph.Lane
import kami.libs.ui.graph.Lod
import kami.libs.ui.map.Viewport
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kotlin.math.max
import kotlin.math.roundToInt

private const val BAND_CURRENT_ALPHA = 0x14
private const val BAND_LOCKED_ALPHA = 0x60
private const val BAND_LABEL_Y = 4
private const val BAND_LABEL_PAD = 5
private const val BAND_LOCK_Y = 8
private const val LANE_ALPHA = 0x0C
private const val LANE_LABEL_PAD = 6
private const val LANE_LABEL_LIFT = 3
private const val PULSE_MS = 1600L
private const val PULSE_PHASE = 97L
private const val ITEM_X = 7
private const val ITEM_Y = 3
private const val BADGE_Z = 200f
private const val LOCK_SIZE = 9
private const val LOCK_X = 3
private const val LOCK_Y = 1
private const val LABEL_Y = 4
private const val STATUS_ICON_X = 13
private const val STATUS_ICON_Y = 8
private const val BAR_H = 4
private const val BAR_BOTTOM = 6
private const val TIME_Y = 13
private const val HATCH_SPACING = 3
private const val PAUSED_ALPHA = 0xB0
private val TASK_BAR get() = Palette.chart[1]
private const val READY_GLOW = 0.3f
private const val READY_GLOW_SWING = 0.4f
private const val LIFT_SHADOW_AT = 0.5f
private val LOCAL = Rect(0, 0, TECH_NODE_W, TECH_NODE_H)
private val LOCK_BADGE = Rect(LOCK_X, LOCK_Y, LOCK_SIZE, LOCK_SIZE)
private val LINK_BADGE = Rect(TECH_NODE_W - LOCK_SIZE - LOCK_X, LOCK_Y, LOCK_SIZE, LOCK_SIZE)
private val BAR = Rect(TEXT_PAD, TECH_NODE_H - BAR_BOTTOM, TECH_NODE_W - 2 * TEXT_PAD, BAR_H)

internal fun Ui.drawNode(node: TechNode, index: Int, x: Int, y: Int, zoom: Double, lod: Lod, state: TechTreeState) {
    val hover = state.hoverOf(index)
    val select = state.selectOf(index)
    val dim = state.dimOf(index)
    val overview = lod == Lod.OVERVIEW
    g.pose().pushPose()
    g.pose().translate(x.toFloat(), (y - (hover * zoom).roundToInt()).toFloat(), 0f)
    g.pose().scale(zoom.toFloat(), zoom.toFloat(), 1f)
    Draw.shadow(g, LOCAL, if (hover > LIFT_SHADOW_AT) 2 else 1)
    if (node.ready) Draw.glow(g, LOCAL, node.accent, READY_GLOW + READY_GLOW_SWING * pulse(PULSE_MS, index * PULSE_PHASE))
    Draw.glow(g, LOCAL, Palette.brass, select)
    val fill = Palette.mix(Palette.mix(Palette.raised, Palette.surface, dim), Palette.hover, hover)
    Draw.box(g, LOCAL, fill, Palette.mix(Palette.alpha(node.accent, 0xA0), Palette.brass, select))
    if (node.highlight) Draw.outline(g, LOCAL.grow(1), Palette.warning)
    Draw.fill(g, LOCAL.inset(1, 1, 0, 1).left(2), node.accent)
    val textColor = Palette.mix(Palette.text, Palette.textMuted, dim)
    if (!overview) Draw.text(g, state.label(index, node), node.textX, LABEL_Y, textColor)
    if (!node.item.isEmpty) g.renderItem(node.item, ITEM_X, ITEM_Y)
    if (node.locked) {
        g.pose().pushPose()
        g.pose().translate(0f, 0f, BADGE_Z)
        Draw.tinted(g, Icons.LOCK.sprite, LOCK_BADGE, Palette.warning)
        g.pose().popPose()
    }
    if (!overview) {
        if (!node.locked) node.status?.let { Draw.leadIcon(g, it, TECH_NODE_W - STATUS_ICON_X, STATUS_ICON_Y, node.accent) }
        node.badge?.let { Draw.tinted(g, it.sprite, LINK_BADGE, Palette.textSecondary) }
    }
    val countdown = node.countdown
    if (countdown != null) {
        val paused = !countdown.running
        val fraction = countdown.fraction(wallMillis)
        val tone = if (paused) Palette.textMuted else Palette.brass
        Draw.thinBar(g, BAR, fraction, tone)
        if (paused) Draw.hatch(g, BAR.withWidth((BAR.w * fraction).toInt()), Palette.alpha(Palette.canvas, PAUSED_ALPHA), HATCH_SPACING)
        if (lod == Lod.DETAIL) Draw.text(g, state.timeLeft(index, countdown.remaining(wallMillis)), node.textX, TIME_Y, tone)
    } else node.progress?.let { Draw.thinBar(g, BAR, it.toFloat(), TASK_BAR) }
    g.pose().popPose()
    if (overview && !node.locked) node.status?.let { Draw.tintedIcon(g, it, x + (TECH_NODE_W * zoom).roundToInt() - Draw.ICON, y + (TECH_NODE_H * zoom).roundToInt() / 2 - Draw.ICON / 2, Draw.ICON, node.accent) }
}

internal fun Ui.drawLanes(r: Rect, vp: Viewport, list: List<Lane>, labels: Map<Int, String>, zoom: Double) {
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

internal fun Ui.drawBands(r: Rect, vp: Viewport, list: List<Band>, bands: TechBands) {
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
