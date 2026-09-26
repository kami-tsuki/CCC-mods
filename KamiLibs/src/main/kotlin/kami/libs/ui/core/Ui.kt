package kami.libs.ui.core

import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.UiSound
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import kotlin.math.exp
import kotlin.math.max

enum class Cursor(val shape: Int) { ARROW(GLFW.GLFW_ARROW_CURSOR), HAND(GLFW.GLFW_HAND_CURSOR), TEXT(GLFW.GLFW_IBEAM_CURSOR), MOVE(GLFW.GLFW_CROSSHAIR_CURSOR) }

class Tip(
    val title: String? = null,
    val lines: List<Pair<String, Int>> = emptyList(),
    val severity: Severity? = null,
    val icon: Icon? = null,
    val keys: String? = null,
    val extraHeight: Int = 0,
    val extra: ((GuiGraphics, Rect) -> Unit)? = null
) {
    companion object {
        fun text(text: String, title: String? = null) = Tip(title, listOf(text to Palette.textSecondary))
        fun disabled(reason: String) = Tip(null, listOf(reason to Palette.warning), Severity.WARNING)
    }
}

class Ui {
    lateinit var g: GuiGraphics
        private set
    val input = Input()
    var mouseX = 0
        private set
    var mouseY = 0
        private set
    var screen = Rect.ZERO
        private set
    var now = 0L
        private set
    var dt = 0f
        private set
    var frame = 0L
        private set
    var layer = 0
        private set
    var keyboardMode = false
    var reduceMotion = false
    var tooltipDelay = 400L
    var cursor = Cursor.ARROW
    var typing = false
        private set
    private var typingNext = false

    var focus: String? = null
    var active: String? = null
    private var hovered: String? = null
    private var hoverSince = 0L

    private val scopes = ArrayList<String>()
    private val states = HashMap<String, Any>()
    private val clips = ArrayList<Rect>()
    private var blockers = ArrayList<Pair<Int, Rect>>()
    private var nextBlockers = ArrayList<Pair<Int, Rect>>()
    private val overlays = sortedMapOf<Int, MutableList<() -> Unit>>()
    private val focusOrder = ArrayList<String>()
    private var escapes = listOf<Pair<Int, () -> Unit>>()
    private val nextEscapes = ArrayList<Pair<Int, () -> Unit>>()
    private val anchorsNext = HashMap<String, Rect>()
    var anchors: Map<String, Rect> = emptyMap()
        private set
    private var tip: Tip? = null
    private var tipKey: String? = null
    private var appliedCursor = Cursor.ARROW
    private val glfwCursors = HashMap<Cursor, Long>()

    fun id(key: Any): String = if (scopes.isEmpty()) key.toString() else scopes.joinToString("/") + "/" + key

    inline fun <R> scope(key: Any, block: () -> R): R {
        push(key.toString())
        try { return block() } finally { pop() }
    }

    fun push(key: String) { scopes += key }
    fun pop() { scopes.removeAt(scopes.lastIndex) }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> remember(key: Any, init: () -> T): T = states.getOrPut(id(key), init) as T

    fun forget(prefix: String) { states.keys.removeIf { it.startsWith(prefix) } }

    fun frame(graphics: GuiGraphics, mx: Int, my: Int, width: Int, height: Int, draw: () -> Unit) {
        g = graphics
        val t = System.currentTimeMillis()
        dt = if (now == 0L) 0f else ((t - now) / 1000f).coerceIn(0f, 0.1f)
        now = t
        frame++
        mouseX = mx
        mouseY = my
        screen = Rect(0, 0, width, height)
        layer = 0
        cursor = Cursor.ARROW
        tip = null
        focusOrder.clear()
        nextEscapes.clear()
        anchorsNext.clear()
        nextBlockers = ArrayList()
        if (input.presses.isNotEmpty()) keyboardMode = false
        draw()
        runOverlays()
        drawTooltip()
        handleTabbing()
        blockers = nextBlockers
        escapes = nextEscapes.sortedByDescending { it.first }
        anchors = HashMap(anchorsNext)
        applyCursor()
        typing = typingNext
        typingNext = false
        if (input.releases.isNotEmpty()) active = null
        input.endFrame(Screen.hasShiftDown(), Screen.hasControlDown())
    }

    private fun runOverlays() {
        while (overlays.isNotEmpty()) {
            val level = overlays.firstKey()
            val batch = overlays.remove(level)!!
            layer = level
            g.pose().pushPose()
            g.pose().translate(0f, 0f, 100f * level)
            batch.forEach { it() }
            g.pose().popPose()
        }
        layer = 0
    }

    fun overlay(offset: Int = 1, draw: () -> Unit) {
        val target = layer + offset
        val captured = ArrayList(scopes)
        overlays.getOrPut(target) { ArrayList() } += {
            val saved = ArrayList(scopes)
            val savedClips = ArrayList(clips)
            scopes.clear(); scopes.addAll(captured)
            clips.clear()
            draw()
            scopes.clear(); scopes.addAll(saved)
            clips.clear(); clips.addAll(savedClips)
        }
    }

    fun block(r: Rect) { nextBlockers += layer to r }

    fun canHit(x: Int, y: Int): Boolean {
        if (clips.isNotEmpty() && !clips.last().contains(x, y)) return false
        return blockers.none { (l, r) -> l > layer && r.contains(x, y) }
    }

    fun hovering(r: Rect) = r.contains(mouseX, mouseY) && canHit(mouseX, mouseY)

    fun hover(key: Any, r: Rect): Boolean {
        val over = hovering(r)
        if (over) {
            val full = id(key)
            if (hovered != full) { hovered = full; hoverSince = now }
        }
        return over
    }

    fun hoverTime(key: Any): Long = if (hovered == id(key)) now - hoverSince else 0

    fun pressed(r: Rect, button: Int = 0): Click? =
        input.presses.firstOrNull { !it.consumed && it.button == button && r.contains(it.x, it.y) && canHit(it.x, it.y) }?.also { it.consumed = true }

    fun pressedOutside(r: Rect): Boolean = input.presses.any { !r.contains(it.x, it.y) }

    fun released(button: Int = 0): Click? = input.releases.firstOrNull { it.button == button }

    fun isDown(button: Int = 0) = input.isDown(button)

    fun wheel(r: Rect): Double = if (hovering(r)) input.takeWheel() else 0.0

    fun focusable(key: Any): String = id(key).also { focusOrder += it }
    fun focused(key: Any) = focus == id(key)
    fun focusRing(key: Any, r: Rect) { if (keyboardMode && focused(key)) Draw.outline(g, r.grow(1), Palette.focus) }

    fun activatedByKey(key: Any): Boolean {
        if (!focused(key)) return false
        return (input.takeKey(GLFW.GLFW_KEY_ENTER) ?: input.takeKey(GLFW.GLFW_KEY_SPACE) ?: input.takeKey(GLFW.GLFW_KEY_KP_ENTER)) != null
    }

    private fun handleTabbing() {
        val tab = input.takeKey(GLFW.GLFW_KEY_TAB) ?: return
        if (focusOrder.isEmpty()) return
        keyboardMode = true
        val i = focusOrder.indexOf(focus)
        focus = focusOrder[((if (i < 0) -1 else i) + (if (tab.shift) -1 else 1)).mod(focusOrder.size)]
    }

    fun markTyping() { typingNext = true }

    fun onEscape(priority: Int, handler: () -> Unit) { nextEscapes += priority to handler }

    fun escape(): Boolean {
        val handler = escapes.firstOrNull() ?: return false
        handler.second()
        return true
    }

    fun anchor(name: String, r: Rect) { anchorsNext[name] = r }

    fun clip(r: Rect, draw: () -> Unit) {
        val effective = if (clips.isEmpty()) r else clips.last().intersect(r)
        clips += effective
        g.enableScissor(r.x, r.y, r.right, r.bottom)
        try { draw() } finally {
            g.disableScissor()
            clips.removeAt(clips.lastIndex)
        }
    }

    fun tooltip(key: Any, r: Rect, build: () -> Tip?) {
        if (!hover("tip:$key", r) && !(keyboardMode && focused(key))) return
        if (hoverTime("tip:$key") < tooltipDelay && !(keyboardMode && focused(key))) return
        val t = build() ?: return
        tip = t
        tipKey = id(key)
    }

    fun tooltip(key: Any, r: Rect, text: String?) = tooltip(key, r) { text?.let { Tip.text(it) } }

    fun tooltip(key: Any, r: Rect, tip: Tip) = tooltip(key, r) { tip }

    private fun drawTooltip() {
        val t = tip ?: return
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 900f)
        Tooltips.draw(g, t, mouseX, mouseY, screen)
        g.pose().popPose()
    }

    fun animate(key: Any, target: Float, speed: Float = 14f, start: Float = target): Float {
        val state = remember("anim:$key") { floatArrayOf(start) }
        if (reduceMotion) { state[0] = target; return target }
        state[0] += (target - state[0]) * (1 - exp(-speed * dt))
        if (kotlin.math.abs(target - state[0]) < 0.001f) state[0] = target
        return state[0]
    }

    fun flash(key: Any, value: Long): Int {
        val state = remember("flash:$key") { longArrayOf(value, 0L, 0L) }
        if (state[0] != value) {
            state[2] = if (value > state[0]) 1 else -1
            state[0] = value
            state[1] = now
        }
        val age = now - state[1]
        if (state[1] == 0L || age > 900) return 0
        val alpha = ((1 - age / 900f) * 0x60).toInt()
        return Palette.alpha(if (state[2] > 0) Palette.success else Palette.danger, alpha)
    }

    fun pulse(periodMs: Long = 1200): Float {
        if (reduceMotion) return 0.5f
        val t = (now % periodMs) / periodMs.toFloat()
        return (kotlin.math.sin(t * Math.PI * 2).toFloat() + 1f) / 2f
    }

    fun click(sound: Boolean = true) { if (sound) UiSound.click() }

    private fun applyCursor() {
        if (cursor == appliedCursor) return
        appliedCursor = cursor
        val window = Minecraft.getInstance().window.window
        val handle = if (cursor == Cursor.ARROW) 0L else glfwCursors.getOrPut(cursor) { GLFW.glfwCreateStandardCursor(cursor.shape) }
        GLFW.glfwSetCursor(window, handle)
    }

    fun close() {
        GLFW.glfwSetCursor(Minecraft.getInstance().window.window, 0L)
        appliedCursor = Cursor.ARROW
        input.reset()
        active = null
    }

    fun textWidth(s: String) = Draw.font.width(s)
}
