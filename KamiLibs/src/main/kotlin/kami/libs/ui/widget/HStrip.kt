package kami.libs.ui.widget

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max

private const val WHEEL_STEP = 48
private const val DRAG_THRESHOLD = 4
private const val TRACK_H = 3
private const val SNAP_DELAY = 0.25

class HStripState {
    val scroll = ScrollState()
    var dragging = false
    var grabX = 0
    var grabTarget = 0.0
    var moved = false
    var idleSince = 0.0
}

fun Ui.hstrip(key: Any, r: Rect, contentWidth: Int, step: Int = 100, snap: Boolean = false, draw: (Rect) -> Unit): ScrollState {
    val state = remember("hstrip:$key") { HStripState() }
    val s = state.scroll
    s.content = contentWidth
    s.view = r.w
    s.center?.let { s.target = (it - r.w / 2).toDouble().coerceIn(0.0, s.max.toDouble()); s.center = null }
    val wheel = wheel(r)
    if (wheel != 0.0) { s.target -= wheel * WHEEL_STEP; state.idleSince = time }
    if (hovering(r) && !typing) {
        if (input.takeKey(GLFW.GLFW_KEY_LEFT) { !it.ctrl } != null) { s.target -= step; state.idleSince = time }
        if (input.takeKey(GLFW.GLFW_KEY_RIGHT) { !it.ctrl } != null) { s.target += step; state.idleSince = time }
    }
    dragStrip(state, r)
    if (state.dragging) state.idleSince = time
    if (snap && step > 0 && time - state.idleSince > SNAP_DELAY) s.target = (Math.round(s.target / step) * step.toDouble()).coerceIn(0.0, s.max.toDouble())
    ease(s)
    clip(r) { draw(Rect(r.x - s.offset.toInt(), r.y, max(contentWidth, r.w), r.h - if (s.max > 0) TRACK_H + 1 else 0)) }
    if (s.max > 0) scrollTrack(s, Rect(r.x, r.bottom - TRACK_H, r.w, TRACK_H))
    return s
}

private fun Ui.dragStrip(state: HStripState, r: Rect) {
    val s = state.scroll
    val id = id("hstrip:drag")
    if (input.presses.any { !it.consumed && it.button == 0 && r.contains(it.x, it.y) && canHit(it.x, it.y) }) {
        state.dragging = true
        state.moved = false
        state.grabX = mouseX
        state.grabTarget = s.target
    }
    if (state.dragging && !isDown()) state.dragging = false
    if (!state.dragging) return
    val dx = mouseX - state.grabX
    if (abs(dx) > DRAG_THRESHOLD) state.moved = true
    if (state.moved) {
        active = id
        s.target = (state.grabTarget - dx).coerceIn(0.0, s.max.toDouble())
        cursor = Cursor.MOVE
    }
}

private fun Ui.scrollTrack(s: ScrollState, track: Rect) {
    Draw.fill(g, track, Palette.alpha(Palette.border, 0x60))
    val thumbW = max(16, track.w * s.view / max(1, s.content))
    val thumbX = track.x + ((track.w - thumbW) * (s.offset / s.max)).toInt()
    Draw.fill(g, Rect(thumbX, track.y, thumbW, track.h), if (abs(s.target - s.offset) > 0.5) Palette.textSecondary else Palette.textMuted)
}
