package kami.libs.ui.core

import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.UiSound
import kami.libs.ui.anim.reveal
import kami.libs.ui.anim.Effects
import kami.libs.ui.anim.Floaters
import kami.libs.ui.anim.Glows
import kami.libs.ui.anim.MotionStore
import kami.libs.ui.anim.Sparks
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW

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

private const val TIP_FADE_MS = 90

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
    var wallMillis = 0L
        private set
    var time = 0.0
        private set
    var dt = 0f
        private set
    var frame = 0L
        private set
    var layer = 0
        private set
    var keyboardMode = false
    var reduceMotion = false
    var tooltipDelay = 500L
    var cursor = Cursor.ARROW
    var typing = false
        private set
    private var typingNext = false

    var focus: String? = null
    var active: String? = null
    private var hovered: String? = null
    private var hoverSince = 0L

    private val prefixes = ArrayList<String>()
    private var prefix = ""
    private val states = HashMap<String, Any>()
    val motion = MotionStore()
    val sparks = Sparks()
    val floaters = Floaters()
    val glows = Glows()
    private val startNanos = System.nanoTime()
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
    private var shownTipKey: String? = null
    private var tipEpoch = 0L
    private var appliedCursor = Cursor.ARROW
    private val glfwCursors = HashMap<Cursor, Long>()

    fun id(key: Any): String = if (prefix.isEmpty()) key.toString() else prefix + key

    inline fun <R> scope(key: Any, block: () -> R): R {
        push(key.toString())
        try { return block() } finally { pop() }
    }

    fun push(key: String) { prefixes += prefix; prefix = "$prefix$key/" }
    fun pop() { prefix = prefixes.removeAt(prefixes.lastIndex) }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> remember(key: Any, init: () -> T): T = states.getOrPut(id(key), init) as T

    class FilterCache<T>(var inputs: Any? = null, var result: List<T> = emptyList())

    fun <T> filtered(key: Any, inputs: Any, compute: () -> List<T>): List<T> {
        val cache = remember(key) { FilterCache<T>() }
        if (cache.inputs != inputs) { cache.inputs = inputs; cache.result = compute() }
        return cache.result
    }

    fun forget(prefix: String) {
        states.keys.removeIf { it.startsWith(prefix) }
        motion.forget(prefix)
    }

    fun frame(graphics: GuiGraphics, mx: Int, my: Int, width: Int, height: Int, draw: () -> Unit) {
        g = graphics
        val elapsed = (System.nanoTime() - startNanos) / 1e9
        dt = if (frame == 0L) 0f else (elapsed - time).toFloat().coerceIn(0f, 0.1f)
        time = elapsed
        wallMillis = System.currentTimeMillis()
        frame++
        motion.sweep(frame)
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
        drawParticles()
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
        val captured = prefix
        overlays.getOrPut(target) { ArrayList() } += {
            val saved = prefix
            val savedClips = ArrayList(clips)
            prefix = captured
            clips.clear()
            draw()
            prefix = saved
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
            if (hovered != full) { hovered = full; hoverSince = wallMillis }
        }
        return over
    }

    fun hoverTime(key: Any): Long = if (hovered == id(key)) wallMillis - hoverSince else 0

    fun pressed(r: Rect, button: Int = 0): Click? =
        input.presses.firstOrNull { !it.consumed && it.button == button && r.contains(it.x, it.y) && canHit(it.x, it.y) }?.also { it.consumed = true }

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

    fun tooltip(key: Any, r: Rect, delay: Long = tooltipDelay, build: () -> Tip?) {
        if (!hover("tip:$key", r) && !(keyboardMode && focused(key))) return
        if (hoverTime("tip:$key") < delay && !(keyboardMode && focused(key))) return
        val t = build() ?: return
        tip = t
        tipKey = id(key)
    }

    fun tooltip(key: Any, r: Rect, text: String?) = tooltip(key, r) { text?.let { Tip.text(it) } }

    fun tooltip(key: Any, r: Rect, tip: Tip) = tooltip(key, r) { tip }

    private fun drawTooltip() {
        val t = tip
        if (t == null) { shownTipKey = null; return }
        if (tipKey != shownTipKey) { shownTipKey = tipKey; tipEpoch++ }
        val appear = reveal("tooltip", tipEpoch, ms = TIP_FADE_MS)
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 900f)
        Tooltips.draw(g, t, mouseX, mouseY, screen, appear)
        g.pose().popPose()
    }

    private fun drawParticles() {
        sparks.update(dt)
        floaters.update(dt)
        glows.update(dt)
        g.pose().pushPose()
        g.pose().translate(0f, 0f, 850f)
        Effects.drawParticles(g, sparks, floaters, glows)
        g.pose().popPose()
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
}
